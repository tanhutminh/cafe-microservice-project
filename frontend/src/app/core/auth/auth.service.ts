import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  Observable,
  catchError,
  finalize,
  of,
  shareReplay,
  switchMap,
  tap,
  throwError,
} from 'rxjs';
import { environment } from '../../../environments/environment';
import { HANDLES_UNAUTHORIZED } from '../interceptors/handles-unauthorized';
import { TokenResponse } from '../models/token-response.model';
import { User } from '../models/user.model';

const ACCESS_TOKEN_KEY = 'cafe.accessToken';
const REFRESH_TOKEN_KEY = 'cafe.refreshToken';

/**
 * Whether a failed request's status is the server rejecting the session: any 4xx except
 * 408 Request Timeout and 429 Too Many Requests, which only ask to try again later. A network
 * failure (status 0) or a 5xx says nothing about the session.
 */
function rejectsSession(status: number): boolean {
  return status >= 400 && status < 500 && status !== 408 && status !== 429;
}

/**
 * Owns the client's session: the access and refresh tokens, kept in localStorage where any
 * script on the page can read them, and the signed-in user loaded from /auth/me. The refresh
 * token is sent only to /auth/logout, to revoke it, so a session lasts no longer than its
 * access token.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly accessToken = signal<string | null>(localStorage.getItem(ACCESS_TOKEN_KEY));
  private readonly refreshTokenValue = signal<string | null>(
    localStorage.getItem(REFRESH_TOKEN_KEY),
  );
  readonly currentUser = signal<User | null>(null);

  readonly isAuthenticated = computed(() => this.accessToken() !== null);

  /** The /auth/me load in flight, if any, shared by every caller until it settles. */
  private pendingUser$?: Observable<User | null>;

  getAccessToken(): string | null {
    return this.accessToken();
  }

  login(username: string, password: string): Observable<User> {
    return this.http
      .post<TokenResponse>(
        `${environment.apiBaseUrl}/auth/login`,
        { username, password },
        {
          // A 401 here means wrong credentials, not an expired session: /login has no guest guard,
          // so whoever is retyping a password may still hold a valid session that must survive.
          context: new HttpContext().set(HANDLES_UNAUTHORIZED, true),
        },
      )
      .pipe(
        tap((tokens) => this.storeTokens(tokens)),
        switchMap(() =>
          this.loadCurrentUser().pipe(
            // A session without its user is unusable (the role guards need the role),
            // so a failed /auth/me undoes the tokens just stored.
            catchError((error) => {
              this.clearSession();
              return throwError(() => error);
            }),
          ),
        ),
      );
  }

  /**
   * Emits the signed-in user, then completes: the already-loaded one, or else the one fetched
   * from /auth/me (concurrent calls share one request). Emits null when there is no session or
   * the server rejects it, in which case the session is cleared. Any other failure says nothing
   * about the session: it is passed on as an error and the session is kept, so a later call
   * sends a fresh request.
   */
  loadCurrentUserIfNeeded(): Observable<User | null> {
    if (!this.isAuthenticated()) {
      return of(null);
    }
    const user = this.currentUser();
    if (user) {
      return of(user);
    }
    this.pendingUser$ ??= this.loadCurrentUser().pipe(
      catchError((error: HttpErrorResponse) => {
        if (!rejectsSession(error.status)) {
          return throwError(() => error);
        }
        this.clearSession();
        return of(null);
      }),
      finalize(() => (this.pendingUser$ = undefined)),
      // A caller that unsubscribes must not cancel the load the other callers share.
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.pendingUser$;
  }

  logout(): void {
    const refreshToken = this.refreshTokenValue();
    // Navigate first so the guarded page never renders without its user.
    const finish = () => {
      this.router.navigate(['/login']).finally(() => this.clearSession());
    };
    if (refreshToken) {
      this.http
        .post(
          `${environment.apiBaseUrl}/auth/logout`,
          { refreshToken },
          {
            // Logging out ends the session whatever the response, so a 401 needs no extra handling.
            context: new HttpContext().set(HANDLES_UNAUTHORIZED, true),
          },
        )
        .subscribe({
          complete: finish,
          error: finish,
        });
    } else {
      finish();
    }
  }

  clearSession(): void {
    this.accessToken.set(null);
    this.refreshTokenValue.set(null);
    this.currentUser.set(null);
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
  }

  private loadCurrentUser(): Observable<User> {
    return this.http
      .get<User>(`${environment.apiBaseUrl}/auth/me`)
      .pipe(tap((user) => this.currentUser.set(user)));
  }

  private storeTokens(tokens: TokenResponse): void {
    this.accessToken.set(tokens.accessToken);
    this.refreshTokenValue.set(tokens.refreshToken);
    localStorage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
  }
}
