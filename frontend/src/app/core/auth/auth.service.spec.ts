import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { environment } from '../../../environments/environment';
import { failWithStatus } from '../../shared/testing/http-testing';
import { errorInterceptor } from '../interceptors/error.interceptor';
import { HANDLES_UNAUTHORIZED } from '../interceptors/handles-unauthorized';
import { jwtInterceptor } from '../interceptors/jwt.interceptor';
import { TokenResponse } from '../models/token-response.model';
import { User } from '../models/user.model';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  const ACCESS_TOKEN_KEY = 'cafe.accessToken';
  const REFRESH_TOKEN_KEY = 'cafe.refreshToken';
  const loginUrl = `${environment.apiBaseUrl}/auth/login`;
  const meUrl = `${environment.apiBaseUrl}/auth/me`;
  const logoutUrl = `${environment.apiBaseUrl}/auth/logout`;

  // Placeholder values only - never real credentials, this file is committed to source control.
  const user: User = {
    id: 1,
    username: 'testuser',
    fullName: 'Test User',
    role: 'ADMIN',
    active: true,
  };
  const otherUser: User = {
    id: 2,
    username: 'otheruser',
    fullName: 'Other User',
    role: 'CASHIER',
    active: true,
  };
  const tokens: TokenResponse = {
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    tokenType: 'Bearer',
    expiresInSeconds: 900,
  };
  /** Statuses a failed request can come back with: rejected, server error, network failure. */
  const failureStatuses = [401, 500, 0];
  /** Statuses with which the server rejects the session; 400 and 499 end the 4xx range. */
  const sessionRejectingStatuses = [400, 401, 403, 404, 499];
  /**
   * Statuses that say nothing about the session: network failure, timeout, throttling and server
   * error, plus 399, just below the 4xx range.
   */
  const sessionNeutralStatuses = [0, 399, 408, 429, 500, 503];

  let httpMock: HttpTestingController;
  let router: { navigate: ReturnType<typeof vi.fn> };

  /** `withAppInterceptors` runs requests through the same interceptors as app.config.ts. */
  function createService(withAppInterceptors = false): AuthService {
    router = { navigate: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
      providers: [
        withAppInterceptors
          ? provideHttpClient(withInterceptors([jwtInterceptor, errorInterceptor]))
          : provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    return TestBed.inject(AuthService);
  }

  /** A service restored from stored tokens, with its user already loaded. */
  function createSignedInService(withAppInterceptors = false): AuthService {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
    localStorage.setItem(REFRESH_TOKEN_KEY, 'stored-refresh');
    const service = createService(withAppInterceptors);
    service.loadCurrentUserIfNeeded().subscribe();
    httpMock.expectOne(meUrl).flush(user);
    return service;
  }

  /** Lets a pending navigation settle, so whatever runs once it ends has run. */
  function navigationSettled(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve));
  }

  function expectSessionKept(service: AuthService): void {
    expect(service.isAuthenticated()).toBe(true);
    expect(service.getAccessToken()).toBe('stored-access');
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBe('stored-access');
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('stored-refresh');
  }

  function expectSessionCleared(service: AuthService): void {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.getAccessToken()).toBeNull();
    expect(service.currentUser()).toBeNull();
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  }

  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  describe('startup', () => {
    it('starts signed out when no token is stored', () => {
      const service = createService();

      expect(service.isAuthenticated()).toBe(false);
      expect(service.currentUser()).toBeNull();
    });

    it('restores the session from a stored token without calling /auth/me on its own', async () => {
      localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
      const service = createService();
      // Let any microtask queued while the service was created run first, so a deferred
      // /auth/me call would be caught below.
      await Promise.resolve();

      expect(service.isAuthenticated()).toBe(true);
      expect(service.getAccessToken()).toBe('stored-access');
      expect(service.currentUser()).toBeNull();
      httpMock.expectNone(meUrl);
    });
  });

  describe('loadCurrentUserIfNeeded()', () => {
    it('emits null without any request when signed out', () => {
      const service = createService();
      const results: (User | null)[] = [];

      service.loadCurrentUserIfNeeded().subscribe((u) => results.push(u));

      expect(results).toEqual([null]);
      httpMock.expectNone(meUrl);
    });

    it('waits for /auth/me, then emits the user and keeps it as the current user', () => {
      localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
      const service = createService();
      const results: (User | null)[] = [];

      service.loadCurrentUserIfNeeded().subscribe((u) => results.push(u));
      expect(results).toEqual([]);

      const req = httpMock.expectOne(meUrl);
      expect(req.request.method).toBe('GET');
      req.flush(user);

      expect(results).toEqual([user]);
      expect(service.currentUser()).toEqual(user);
    });

    it('emits the already-loaded user without another request', () => {
      const service = createSignedInService();
      const results: (User | null)[] = [];

      service.loadCurrentUserIfNeeded().subscribe((u) => results.push(u));

      expect(results).toEqual([user]);
      httpMock.expectNone(meUrl);
    });

    it('shares one /auth/me request between callers that ask while it is in flight', () => {
      localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
      const service = createService();
      const first: (User | null)[] = [];
      const second: (User | null)[] = [];

      service.loadCurrentUserIfNeeded().subscribe((u) => first.push(u));
      service.loadCurrentUserIfNeeded().subscribe((u) => second.push(u));
      httpMock.expectOne(meUrl).flush(user);

      expect(first).toEqual([user]);
      expect(second).toEqual([user]);
    });

    it('keeps /auth/me in flight for later callers when the caller that started it unsubscribes', () => {
      localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
      const service = createService();
      const results: (User | null)[] = [];

      service.loadCurrentUserIfNeeded().subscribe().unsubscribe();
      service.loadCurrentUserIfNeeded().subscribe((u) => results.push(u));
      const req = httpMock.expectOne(meUrl);
      expect(req.cancelled).toBe(false);
      req.flush(user);

      expect(results).toEqual([user]);
      expect(service.currentUser()).toEqual(user);
    });

    it('starts a new /auth/me once the earlier one has settled, instead of replaying it', () => {
      const service = createSignedInService();
      service.currentUser.set(null);
      const results: (User | null)[] = [];

      service.loadCurrentUserIfNeeded().subscribe((u) => results.push(u));
      expect(results).toEqual([]);
      httpMock.expectOne(meUrl).flush(otherUser);

      expect(results).toEqual([otherUser]);
      expect(service.currentUser()).toEqual(otherUser);
    });

    it.each(sessionRejectingStatuses)(
      'emits null and clears the session when /auth/me rejects it (%i)',
      (status) => {
        localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
        localStorage.setItem(REFRESH_TOKEN_KEY, 'stored-refresh');
        const service = createService();
        const results: (User | null)[] = [];
        let failed = false;

        service
          .loadCurrentUserIfNeeded()
          .subscribe({ next: (u) => results.push(u), error: () => (failed = true) });
        failWithStatus(httpMock.expectOne(meUrl), status);

        expect(results).toEqual([null]);
        expect(failed).toBe(false);
        expectSessionCleared(service);
      },
    );

    it.each(sessionNeutralStatuses)(
      'passes the failure on and keeps the session when /auth/me fails with %i',
      (status) => {
        localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
        localStorage.setItem(REFRESH_TOKEN_KEY, 'stored-refresh');
        const service = createService();
        const results: (User | null)[] = [];
        let error: HttpErrorResponse | undefined;

        service
          .loadCurrentUserIfNeeded()
          .subscribe({ next: (u) => results.push(u), error: (e) => (error = e) });
        failWithStatus(httpMock.expectOne(meUrl), status);

        expect(error?.status).toBe(status);
        expect(results).toEqual([]);
        expectSessionKept(service);
        expect(service.currentUser()).toBeNull();

        // A later call sends a fresh request instead of replaying the failure.
        service.loadCurrentUserIfNeeded().subscribe((u) => results.push(u));
        httpMock.expectOne(meUrl).flush(user);

        expect(results).toEqual([user]);
      },
    );

    it('passes the same failure to every caller sharing the request', () => {
      localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
      const service = createService();
      let firstError: HttpErrorResponse | undefined;
      let secondError: HttpErrorResponse | undefined;

      service.loadCurrentUserIfNeeded().subscribe({ error: (e) => (firstError = e) });
      service.loadCurrentUserIfNeeded().subscribe({ error: (e) => (secondError = e) });
      failWithStatus(httpMock.expectOne(meUrl), 503);

      expect(firstError?.status).toBe(503);
      expect(secondError).toBe(firstError);
    });
  });

  describe('login()', () => {
    it('posts the credentials, stores both tokens, then loads and returns the current user', () => {
      const service = createService();
      let result: User | undefined;

      service.login('testuser', 'test-password').subscribe((u) => (result = u));

      const loginReq = httpMock.expectOne(loginUrl);
      expect(loginReq.request.method).toBe('POST');
      expect(loginReq.request.body).toEqual({ username: 'testuser', password: 'test-password' });
      loginReq.flush(tokens);

      expect(service.isAuthenticated()).toBe(true);
      expect(service.getAccessToken()).toBe('access-1');
      expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBe('access-1');
      expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-1');

      httpMock.expectOne(meUrl).flush(user);

      expect(result).toEqual(user);
      expect(service.currentUser()).toEqual(user);
    });

    it('marks the login request, but not the /auth/me that follows, as handling its own 401', () => {
      const service = createService();

      service.login('testuser', 'test-password').subscribe();
      const loginReq = httpMock.expectOne(loginUrl);
      expect(loginReq.request.context.get(HANDLES_UNAUTHORIZED)).toBe(true);
      loginReq.flush(tokens);

      const meReq = httpMock.expectOne(meUrl);
      expect(meReq.request.context.get(HANDLES_UNAUTHORIZED)).toBe(false);
      meReq.flush(user);
    });

    it.each(failureStatuses)(
      'stores nothing, skips /auth/me and passes the error on when the login fails (%i)',
      (status) => {
        const service = createService();
        let error: HttpErrorResponse | undefined;

        service.login('testuser', 'wrong-password').subscribe({ error: (e) => (error = e) });
        failWithStatus(httpMock.expectOne(loginUrl), status);

        expect(error?.status).toBe(status);
        httpMock.expectNone(meUrl);
        expectSessionCleared(service);
      },
    );

    it.each(failureStatuses)(
      'undoes the stored tokens before passing the error on when /auth/me fails after login (%i)',
      (status) => {
        const service = createSignedInService();
        let error: HttpErrorResponse | undefined;
        let signedInWhenErrorArrived: boolean | undefined;

        service.login('otheruser', 'test-password').subscribe({
          error: (e) => {
            error = e;
            signedInWhenErrorArrived = service.isAuthenticated();
          },
        });
        httpMock.expectOne(loginUrl).flush(tokens);
        failWithStatus(httpMock.expectOne(meUrl), status);

        expect(error?.status).toBe(status);
        expect(signedInWhenErrorArrived).toBe(false);
        expectSessionCleared(service);
      },
    );

    it('fetches the new user even when another one is already loaded', () => {
      const service = createSignedInService();

      service.login('otheruser', 'test-password').subscribe();
      httpMock.expectOne(loginUrl).flush(tokens);
      httpMock.expectOne(meUrl).flush(otherUser);

      expect(service.currentUser()).toEqual(otherUser);
    });
  });

  describe('logout()', () => {
    it('revokes the refresh token, then goes to /login and clears the session', async () => {
      const service = createSignedInService();
      let signedInWhenNavigating: boolean | undefined;
      router.navigate.mockImplementation(() => {
        signedInWhenNavigating = service.isAuthenticated();
        return Promise.resolve(true);
      });

      service.logout();

      const req = httpMock.expectOne(logoutUrl);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ refreshToken: 'stored-refresh' });
      expect(req.request.context.get(HANDLES_UNAUTHORIZED)).toBe(true);
      // Nothing is torn down until the server has answered.
      expect(service.isAuthenticated()).toBe(true);
      expect(router.navigate).not.toHaveBeenCalled();

      req.flush(null);
      await navigationSettled();

      expect(router.navigate).toHaveBeenCalledWith(['/login']);
      expect(signedInWhenNavigating).toBe(true);
      expectSessionCleared(service);
    });

    it.each(failureStatuses)(
      'still goes to /login and clears the session when the logout call fails (%i)',
      async (status) => {
        const service = createSignedInService();

        service.logout();
        failWithStatus(httpMock.expectOne(logoutUrl), status);
        await navigationSettled();

        expect(router.navigate).toHaveBeenCalledWith(['/login']);
        expectSessionCleared(service);
      },
    );

    it('calls no endpoint when there is no refresh token, and still clears the session', async () => {
      localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
      const service = createService();

      service.logout();
      await navigationSettled();

      httpMock.expectNone(logoutUrl);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
      expectSessionCleared(service);
    });

    it("clears the session even when the navigation to /login doesn't happen", async () => {
      const service = createSignedInService();
      router.navigate.mockResolvedValue(false);

      service.logout();
      httpMock.expectOne(logoutUrl).flush(null);
      await navigationSettled();

      expectSessionCleared(service);
    });
  });

  describe('clearSession()', () => {
    it('drops both tokens and the current user, in memory and in localStorage', () => {
      const service = createSignedInService();

      service.clearSession();

      expectSessionCleared(service);
    });
  });

  describe('with the app interceptors', () => {
    it('goes to /login exactly once when the logout call is rejected with a 401', async () => {
      const service = createSignedInService(true);

      service.logout();
      failWithStatus(httpMock.expectOne(logoutUrl), 401);
      await navigationSettled();

      expectSessionCleared(service);
      expect(router.navigate).toHaveBeenCalledTimes(1);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });

    it('keeps an existing session when a login attempt is rejected for wrong credentials', () => {
      const service = createSignedInService(true);

      service.login('testuser', 'wrong-password').subscribe({ error: () => undefined });
      failWithStatus(httpMock.expectOne(loginUrl), 401);

      expect(service.isAuthenticated()).toBe(true);
      expect(service.currentUser()).toEqual(user);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('clears the session and goes to /login exactly once when /auth/me rejects a fresh login', () => {
      const service = createService(true);
      let error: HttpErrorResponse | undefined;

      service.login('testuser', 'test-password').subscribe({ error: (e) => (error = e) });
      httpMock.expectOne(loginUrl).flush(tokens);
      failWithStatus(httpMock.expectOne(meUrl), 401);

      expect(error?.status).toBe(401);
      expectSessionCleared(service);
      expect(router.navigate).toHaveBeenCalledTimes(1);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });

    it('clears the session and goes to /login exactly once when /auth/me rejects the stored token', () => {
      localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
      const service = createService(true);
      const results: (User | null)[] = [];

      service.loadCurrentUserIfNeeded().subscribe((u) => results.push(u));
      failWithStatus(httpMock.expectOne(meUrl), 401);

      expect(results).toEqual([null]);
      expectSessionCleared(service);
      expect(router.navigate).toHaveBeenCalledTimes(1);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });

    it('passes a 503 from /auth/me on without clearing the session or leaving the page', () => {
      localStorage.setItem(ACCESS_TOKEN_KEY, 'stored-access');
      localStorage.setItem(REFRESH_TOKEN_KEY, 'stored-refresh');
      const service = createService(true);
      let error: HttpErrorResponse | undefined;

      service.loadCurrentUserIfNeeded().subscribe({ error: (e) => (error = e) });
      failWithStatus(httpMock.expectOne(meUrl), 503);

      expect(error?.status).toBe(503);
      expectSessionKept(service);
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });
});
