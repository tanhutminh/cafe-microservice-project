import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from '../models/user.model';
import { AuthService } from './auth.service';

/** Route factory guard — usage: canActivate: [roleGuard('ADMIN')] */
export function roleGuard(...allowedRoles: Role[]): CanActivateFn {
  return () => {
    const authService = inject(AuthService);
    const router = inject(Router);

    if (!authService.isAuthenticated()) {
      return router.parseUrl('/login');
    }
    const role = authService.currentUser()?.role;
    if (role && allowedRoles.includes(role)) {
      return true;
    }
    return router.parseUrl('/');
  };
}
