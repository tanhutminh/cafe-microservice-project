import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  GuardResult,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { Observable, isObservable } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { User } from '../../core/models/user.model';

/** Sets up TestBed with a router and an AuthService whose user load emits on `user$`. */
export function configureGuardTesting(user$: Observable<User | null>): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: AuthService, useValue: { loadCurrentUserIfNeeded: () => user$ } },
    ],
  });
}

/** Runs `guard` in TestBed's injection context; throws unless it returns an Observable. */
export function runGuard(guard: CanActivateFn): Observable<GuardResult> {
  const result = TestBed.runInInjectionContext(() =>
    guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
  );
  if (!isObservable(result)) {
    throw new Error('Expected the guard to return an Observable');
  }
  return result;
}

/** Subscribes to a guard's result and collects every decision it emits, as they arrive. */
export function decisionsOf(result$: Observable<GuardResult>): GuardResult[] {
  const decisions: GuardResult[] = [];
  result$.subscribe((decision) => decisions.push(decision));
  return decisions;
}

/** The URL a guard decision redirects to; throws unless the decision is a UrlTree. */
export function redirectUrlOf(decision: GuardResult): string {
  if (!(decision instanceof UrlTree)) {
    throw new Error(`Expected the guard to redirect with a UrlTree, got ${String(decision)}`);
  }
  return TestBed.inject(Router).serializeUrl(decision);
}
