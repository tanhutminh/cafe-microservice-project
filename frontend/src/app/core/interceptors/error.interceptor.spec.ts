import {
  HttpClient,
  HttpContext,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { environment } from '../../../environments/environment';
import { failWithStatus } from '../../shared/testing/http-testing';
import { AuthService } from '../auth/auth.service';
import { errorInterceptor } from './error.interceptor';
import { HANDLES_UNAUTHORIZED } from './handles-unauthorized';

describe('errorInterceptor', () => {
  let httpMock: HttpTestingController;
  let authService: { clearSession: ReturnType<typeof vi.fn> };
  let router: { navigate: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    authService = { clearSession: vi.fn() };
    router = { navigate: vi.fn().mockReturnValue(new Promise<boolean>(() => {})) };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authService },
        { provide: Router, useValue: router },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  /** Sends a GET the backend answers with `status`; returns the error the caller receives. */
  function sendAndFail(
    path: string,
    status: number,
    context = new HttpContext(),
  ): HttpErrorResponse | undefined {
    const url = `${environment.apiBaseUrl}${path}`;
    let received: HttpErrorResponse | undefined;

    TestBed.inject(HttpClient)
      .get(url, { context })
      .subscribe({ error: (e: HttpErrorResponse) => (received = e) });
    failWithStatus(httpMock.expectOne(url), status);
    return received;
  }

  function expectSessionUntouched(): void {
    expect(authService.clearSession).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  }

  // Auth endpoints are listed on purpose: only HANDLES_UNAUTHORIZED opts a request out, never
  // its URL.
  const paths = ['/orders', '/auth/me', '/auth/login', '/auth/logout'];

  it.each(paths)(
    'treats a 401 from %s as an expired session: clears it before going to /login, without waiting for the navigation, and still passes the error on',
    (path) => {
      expect(sendAndFail(path, 401)?.status).toBe(401);
      expect(authService.clearSession).toHaveBeenCalledTimes(1);
      expect(router.navigate).toHaveBeenCalledTimes(1);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
      expect(authService.clearSession).toHaveBeenCalledBefore(router.navigate);
    },
  );

  it.each(paths)(
    'leaves the session alone on a 401 from %s when the request sets HANDLES_UNAUTHORIZED',
    (path) => {
      const context = new HttpContext().set(HANDLES_UNAUTHORIZED, true);

      expect(sendAndFail(path, 401, context)?.status).toBe(401);
      expectSessionUntouched();
    },
  );

  it.each([0, 400, 403, 404, 500])(
    'passes a %i error on without touching the session',
    (status) => {
      expect(sendAndFail('/orders', status)?.status).toBe(status);
      expectSessionUntouched();
    },
  );

  it('lets a successful response through unchanged', () => {
    const url = `${environment.apiBaseUrl}/orders`;
    let body: unknown;

    TestBed.inject(HttpClient)
      .get(url)
      .subscribe((b) => (body = b));
    httpMock.expectOne(url).flush([{ id: 1 }]);

    expect(body).toEqual([{ id: 1 }]);
    expectSessionUntouched();
  });
});
