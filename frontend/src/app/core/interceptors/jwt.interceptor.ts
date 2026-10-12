import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';

/** Endpoints this app calls that the gateway serves without a JWT, so none is attached to them. */
const PUBLIC_PATHS = ['/auth/login'];

export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);

  if (PUBLIC_PATHS.some((path) => req.url.startsWith(`${environment.apiBaseUrl}${path}`))) {
    return next(req);
  }

  const token = authService.getAccessToken();
  if (!token) {
    return next(req);
  }

  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
