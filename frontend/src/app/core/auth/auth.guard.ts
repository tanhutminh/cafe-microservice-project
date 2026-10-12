import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Allows the route once the signed-in user is loaded; redirects to /login when there is no user
 * or it can't be loaded.
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.loadCurrentUserIfNeeded().pipe(
    map((user) => (user ? true : router.parseUrl('/login'))),
    catchError(() => of(router.parseUrl('/login'))),
  );
};
