import { Type } from '@angular/core';
import { CanActivateFn, Route } from '@angular/router';
import { firstValueFrom, of } from 'rxjs';
import { routes } from './app.routes';
import { Role, User } from './core/models/user.model';
import { Login } from './features/auth/login/login';
import { Home } from './features/home/home';
import { InventoryAdmin } from './features/inventory-admin/inventory-admin';
import { MenuAdmin } from './features/menu-admin/menu-admin';
import { Pos } from './features/pos/pos';
import { configureGuardTesting, runGuard } from './shared/testing/guard-testing';

type Visitor = 'anonymous' | Role;

describe('app routes', () => {
  function routeFor(path: string): Route {
    const route = routes.find((r) => r.path === path);
    if (!route) {
      throw new Error(`no route for '${path}'`);
    }
    return route;
  }

  /** Whether every canActivate guard of the route lets the given visitor in. */
  async function canVisit(path: string, visitor: Visitor): Promise<boolean> {
    configureGuardTesting(of(visitor === 'anonymous' ? null : ({ role: visitor } as User)));
    const guards = (routeFor(path).canActivate ?? []) as CanActivateFn[];
    for (const guard of guards) {
      if ((await firstValueFrom(runGuard(guard))) !== true) {
        return false;
      }
    }
    return true;
  }

  it.each<[string, Type<unknown>]>([
    ['login', Login],
    ['', Home],
    ['menu', MenuAdmin],
    ['inventory', InventoryAdmin],
    ['pos', Pos],
  ])("lazy-loads the right component for '%s'", async (path, component) => {
    expect(await routeFor(path).loadComponent!()).toBe(component);
  });

  it.each<[string, Visitor, boolean]>([
    ['login', 'anonymous', true],
    ['login', 'CASHIER', true],
    ['login', 'ADMIN', true],
    ['', 'anonymous', false],
    ['', 'CASHIER', true],
    ['', 'ADMIN', true],
    ['menu', 'anonymous', false],
    ['menu', 'CASHIER', false],
    ['menu', 'ADMIN', true],
    ['inventory', 'anonymous', false],
    ['inventory', 'CASHIER', false],
    ['inventory', 'ADMIN', true],
    ['pos', 'anonymous', false],
    ['pos', 'CASHIER', true],
    ['pos', 'ADMIN', true],
  ])("'%s' as %s is allowed: %s", async (path, visitor, allowed) => {
    expect(await canVisit(path, visitor)).toBe(allowed);
  });

  it('sends any unknown path to the home page, as the last route', () => {
    expect(routes.at(-1)).toEqual({ path: '**', redirectTo: '' });
  });
});
