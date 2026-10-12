import { HttpErrorResponse } from '@angular/common/http';
import { GuardResult } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import {
  configureGuardTesting,
  decisionsOf,
  redirectUrlOf,
  runGuard,
} from '../../shared/testing/guard-testing';
import { Role, User } from '../models/user.model';
import { roleGuard } from './role.guard';

describe('roleGuard', () => {
  function userWith(role: Role): User {
    return { role } as User;
  }

  /** The decisions roleGuard(...allowedRoles) emits against a user load that emits on `user$`. */
  function decide(allowedRoles: Role[], user$: Observable<User | null>): GuardResult[] {
    configureGuardTesting(user$);
    return decisionsOf(runGuard(roleGuard(...allowedRoles)));
  }

  it.each<[Role[], Role]>([
    [['ADMIN'], 'ADMIN'],
    [['ADMIN', 'CASHIER'], 'ADMIN'],
    [['ADMIN', 'CASHIER'], 'CASHIER'],
  ])(
    'allows a user whose role is in the allowed list (allowed: %j, role: %s)',
    (allowedRoles, role) => {
      expect(decide(allowedRoles, of(userWith(role)))).toEqual([true]);
    },
  );

  it('sends a user whose role is not allowed to /', () => {
    const decisions = decide(['ADMIN'], of(userWith('CASHIER')));

    expect(decisions).toHaveLength(1);
    expect(redirectUrlOf(decisions[0])).toBe('/');
  });

  it.each<[Role[]]>([[['ADMIN']], [['ADMIN', 'CASHIER']]])(
    'redirects to /login when there is no user (allowed: %j)',
    (allowedRoles) => {
      const decisions = decide(allowedRoles, of(null));

      expect(decisions).toHaveLength(1);
      expect(redirectUrlOf(decisions[0])).toBe('/login');
    },
  );

  it.each<[Role[]]>([[['ADMIN']], [['ADMIN', 'CASHIER']]])(
    "redirects to /login when the user can't be loaded (allowed: %j)",
    (allowedRoles) => {
      const decisions = decide(
        allowedRoles,
        throwError(() => new HttpErrorResponse({ status: 503 })),
      );

      expect(decisions).toHaveLength(1);
      expect(redirectUrlOf(decisions[0])).toBe('/login');
    },
  );

  it('waits for the user to load before checking the role', () => {
    const user$ = new Subject<User | null>();
    const decisions = decide(['CASHIER'], user$);

    expect(decisions).toEqual([]);
    user$.next(userWith('CASHIER'));

    expect(decisions).toEqual([true]);
  });
});
