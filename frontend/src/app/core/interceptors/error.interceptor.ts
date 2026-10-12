import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { HANDLES_UNAUTHORIZED } from './handles-unauthorized';

/**
 * Treats a 401 as the end of the session: clears it and redirects to /login, unless the request
 * sets HANDLES_UNAUTHORIZED. Every error, a 401 included, is passed on to the caller.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && !req.context.get(HANDLES_UNAUTHORIZED)) {
        // Clear right away rather than once /login is shown: the server has rejected this
        // session, so no guard or request may go on using it meanwhile.
        authService.clearSession();
        router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
