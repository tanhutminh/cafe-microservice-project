import { HarnessLoader } from '@angular/cdk/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatButtonHarness } from '@angular/material/button/testing';
import { By } from '@angular/platform-browser';
import { RouterLink, provideRouter } from '@angular/router';
import { Translation } from '@jsverse/transloco';
import { AuthService } from '../../core/auth/auth.service';
import { User } from '../../core/models/user.model';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { Home } from './home';

describe('Home', () => {
  const admin: User = {
    id: 1,
    username: 'admin',
    fullName: 'Admin User',
    role: 'ADMIN',
    active: true,
  };
  const cashier: User = {
    id: 2,
    username: 'cashier',
    fullName: 'Cashier User',
    role: 'CASHIER',
    active: true,
  };

  let authService: {
    currentUser: WritableSignal<User | null>;
    logout: ReturnType<typeof vi.fn>;
  };

  function render(
    user: User,
    translations: Translation = {},
  ): { fixture: ComponentFixture<Home>; page: HTMLElement; loader: HarnessLoader } {
    authService = { currentUser: signal<User | null>(user), logout: vi.fn() };
    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting(translations)],
      providers: [provideRouter([]), { provide: AuthService, useValue: authService }],
    });
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();
    return {
      fixture,
      page: fixture.nativeElement as HTMLElement,
      loader: TestbedHarnessEnvironment.loader(fixture),
    };
  }

  /** The URL each RouterLink in the page leads to, in DOM order. */
  function linkTargets(fixture: ComponentFixture<Home>): (string | undefined)[] {
    return fixture.debugElement
      .queryAll(By.directive(RouterLink))
      .map((link) => link.injector.get(RouterLink).urlTree?.toString());
  }

  it('shows POS, Menu and Inventory to an ADMIN', () => {
    expect(linkTargets(render(admin).fixture)).toEqual(['/pos', '/menu', '/inventory']);
  });

  it('shows only POS to a CASHIER', () => {
    expect(linkTargets(render(cashier).fixture)).toEqual(['/pos']);
  });

  it('welcomes the loaded user by full name and translated role', () => {
    const { page } = render(admin, {
      home: { welcome: 'Hello {{name}} ({{role}})' },
      role: { ADMIN: 'Administrator' },
    });

    expect(page.textContent).toContain('Hello Admin User (Administrator)');
  });

  it('shows no welcome line and only POS when the session ends while the page is shown', () => {
    const { fixture, page } = render(admin);

    expect(page.textContent).toContain('home.welcome');
    expect(linkTargets(fixture)).toEqual(['/pos', '/menu', '/inventory']);

    authService.currentUser.set(null);
    fixture.detectChanges();

    expect(page.textContent).not.toContain('home.welcome');
    expect(linkTargets(fixture)).toEqual(['/pos']);
  });

  it('logs out when the logout button is clicked', async () => {
    const { loader } = render(admin);

    await (await loader.getHarness(MatButtonHarness.with({ text: 'auth.logout' }))).click();

    expect(authService.logout).toHaveBeenCalled();
  });
});
