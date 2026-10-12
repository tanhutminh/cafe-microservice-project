import { HttpClient, HttpRequest, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { jwtInterceptor } from './jwt.interceptor';

describe('jwtInterceptor', () => {
  let httpMock: HttpTestingController;

  /** Sends a GET through the interceptor and returns the request as it reached the backend. */
  function send(path: string, token: string | null): HttpRequest<unknown> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([jwtInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { getAccessToken: () => token } },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    const url = `${environment.apiBaseUrl}${path}`;

    TestBed.inject(HttpClient).get(url).subscribe();
    const req = httpMock.expectOne(url);
    req.flush({});
    return req.request;
  }

  afterEach(() => {
    httpMock.verify();
  });

  it('adds a Bearer Authorization header when a token is available', () => {
    expect(send('/orders', 'access-1').headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('sends the request unchanged when there is no token', () => {
    expect(send('/orders', null).headers.has('Authorization')).toBe(false);
  });

  it('never adds the header to the public endpoint /auth/login', () => {
    expect(send('/auth/login', 'access-1').headers.has('Authorization')).toBe(false);
  });

  it.each(['/auth/me', '/auth/logout'])(
    'adds the header to the authenticated auth endpoint %s',
    (path) => {
      expect(send(path, 'access-1').headers.get('Authorization')).toBe('Bearer access-1');
    },
  );

  it('matches public endpoints by path prefix, not anywhere in the URL', () => {
    expect(send('/orders?next=/auth/login', 'access-1').headers.get('Authorization')).toBe(
      'Bearer access-1',
    );
  });
});
