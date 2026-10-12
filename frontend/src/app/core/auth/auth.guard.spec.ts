import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
import {
  configureGuardTesting,
  decisionsOf,
  redirectUrlOf,
  runGuard,
} from '../../shared/testing/guard-testing';
import { User } from '../models/user.model';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  const user = { role: 'CASHIER' } as User;

  it('waits for the user to load, then allows the navigation', () => {
    const user$ = new Subject<User | null>();
    configureGuardTesting(user$);
    const decisions = decisionsOf(runGuard(authGuard));

    expect(decisions).toEqual([]);
    user$.next(user);

    expect(decisions).toEqual([true]);
  });

  it('redirects to /login when there is no user', () => {
    configureGuardTesting(of(null));
    const decisions = decisionsOf(runGuard(authGuard));

    expect(decisions).toHaveLength(1);
    expect(redirectUrlOf(decisions[0])).toBe('/login');
  });

  it("redirects to /login when the user can't be loaded", () => {
    configureGuardTesting(throwError(() => new HttpErrorResponse({ status: 503 })));
    const decisions = decisionsOf(runGuard(authGuard));

    expect(decisions).toHaveLength(1);
    expect(redirectUrlOf(decisions[0])).toBe('/login');
  });
});
