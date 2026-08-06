import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { User } from '../../../core/models/user.model';
import { provideTranslocoTesting } from '../../../shared/testing/transloco-testing';
import { Login } from './login';

describe('Login', () => {
  // Placeholder values only - never real credentials, this file is committed to source control.
  const fakeUser: User = { id: 1, username: 'testuser', fullName: 'Test User', role: 'ADMIN', active: true };

  let router: { navigate: ReturnType<typeof vi.fn> };

  function createComponent(loginResult: Observable<User> = of(fakeUser)) {
    const authService = { login: vi.fn().mockReturnValue(loginResult) };
    router = { navigate: vi.fn() };

    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [
        { provide: AuthService, useValue: authService },
        { provide: Router, useValue: router }
      ]
    });
    const fixture = TestBed.createComponent(Login);
    return { fixture, component: fixture.componentInstance, authService };
  }

  it('submit() does nothing while the form is invalid', () => {
    const { component, authService } = createComponent();

    component.submit();

    expect(authService.login).not.toHaveBeenCalled();
  });

  it('submit() trims the username but leaves the password untouched', () => {
    const { component, authService } = createComponent();
    component.form.patchValue({ username: '  testuser  ', password: '  s3cret pw  ' });

    component.submit();

    expect(authService.login).toHaveBeenCalledWith('testuser', '  s3cret pw  ');
  });

  it('submit() sets loading while the request is pending and navigates home on success', () => {
    const { component } = createComponent();
    component.form.patchValue({ username: 'testuser', password: 'placeholder' });

    component.submit();

    expect(component.loading()).toBe(false);
    expect(component.loginFailed()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/']);
  });

  it('submit() surfaces a failure without navigating', () => {
    const { component } = createComponent(throwError(() => new Error('invalid credentials')));
    component.form.patchValue({ username: 'testuser', password: 'wrong-placeholder' });

    component.submit();

    expect(component.loading()).toBe(false);
    expect(component.loginFailed()).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('renders the sign-in form, then the failure banner after a failed attempt', () => {
    const { fixture, component } = createComponent(throwError(() => new Error('invalid credentials')));
    fixture.detectChanges();

    component.form.patchValue({ username: 'testuser', password: 'wrong-placeholder' });
    component.submit();
    fixture.detectChanges();

    expect(component.loginFailed()).toBe(true);
  });
});
