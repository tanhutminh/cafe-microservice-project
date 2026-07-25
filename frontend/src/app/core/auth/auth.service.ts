import { HttpClient } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, switchMap, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TokenResponse } from '../models/token-response.model';
import { User } from '../models/user.model';

const ACCESS_TOKEN_KEY = 'cafe.accessToken';
const REFRESH_TOKEN_KEY = 'cafe.refreshToken';

/**
 * Tokens are kept in localStorage for MVP simplicity (single internal POS app,
 * no third-party scripts). A production-hardened build would prefer httpOnly
 * cookies to reduce XSS exposure — noted here as a deliberate scope tradeoff.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly accessToken = signal<string | null>(localStorage.getItem(ACCESS_TOKEN_KEY));
  private readonly refreshTokenValue = signal<string | null>(localStorage.getItem(REFRESH_TOKEN_KEY));
  readonly currentUser = signal<User | null>(null);

  readonly isAuthenticated = computed(() => this.accessToken() !== null);

  constructor(private readonly http: HttpClient, private readonly router: Router) {
    if (this.isAuthenticated()) {
      this.loadCurrentUser().subscribe({ error: () => this.clearSession() });
    }
  }

  getAccessToken(): string | null {
    return this.accessToken();
  }

  login(username: string, password: string): Observable<User> {
    return this.http.post<TokenResponse>(`${environment.apiBaseUrl}/auth/login`, { username, password }).pipe(
      tap((tokens) => this.storeTokens(tokens)),
      switchMap(() => this.loadCurrentUser())
    );
  }

  loadCurrentUser(): Observable<User> {
    return this.http.get<User>(`${environment.apiBaseUrl}/auth/me`).pipe(
      tap((user) => this.currentUser.set(user))
    );
  }

  logout(): void {
    const refreshToken = this.refreshTokenValue();
    const finish = () => {
      this.clearSession();
      this.router.navigate(['/login']);
    };
    if (refreshToken) {
      this.http.post(`${environment.apiBaseUrl}/auth/logout`, { refreshToken }).subscribe({
        complete: finish,
        error: finish
      });
    } else {
      finish();
    }
  }

  refresh(): Observable<TokenResponse> {
    const refreshToken = this.refreshTokenValue();
    return this.http.post<TokenResponse>(`${environment.apiBaseUrl}/auth/refresh`, { refreshToken }).pipe(
      tap((tokens) => this.storeTokens(tokens))
    );
  }

  clearSession(): void {
    this.accessToken.set(null);
    this.refreshTokenValue.set(null);
    this.currentUser.set(null);
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
  }

  private storeTokens(tokens: TokenResponse): void {
    this.accessToken.set(tokens.accessToken);
    this.refreshTokenValue.set(tokens.refreshToken);
    localStorage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
  }
}
