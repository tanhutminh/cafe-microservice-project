import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { Role } from '../models/user.model';
import { AuthService } from './auth.service';

/**
 * Route factory guard — usage: canActivate: [roleGuard('ADMIN')]. Waits for the signed-in user to
 * be loaded, then allows the route for the listed roles, sends any other role to / and redirects
 * to /login when there is no user or it can't be loaded.
 */
export function roleGuard(...allowedRoles: Role[]): CanActivateFn {
  return () => {
    const authService = inject(AuthService);
    const router = inject(Router);

    return authService.loadCurrentUserIfNeeded().pipe(
      map((user) => {
        if (!user) {
          return router.parseUrl('/login');
        }
        return allowedRoles.includes(user.role) ? true : router.parseUrl('/');
      }),
      catchError(() => of(router.parseUrl('/login'))),
    );
  };
}
