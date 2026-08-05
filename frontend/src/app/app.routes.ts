import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login)
  },
  {
    path: '',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
    canActivate: [authGuard]
  },
  {
    path: 'menu',
    loadComponent: () => import('./features/menu-admin/menu-admin').then((m) => m.MenuAdmin),
    canActivate: [roleGuard('ADMIN')]
  },
  {
    path: 'inventory',
    loadComponent: () => import('./features/inventory-admin/inventory-admin').then((m) => m.InventoryAdmin),
    canActivate: [roleGuard('ADMIN')]
  },
  {
    path: 'pos',
    loadComponent: () => import('./features/pos/pos').then((m) => m.Pos),
    canActivate: [roleGuard('ADMIN', 'CASHIER')]
  },
  { path: '**', redirectTo: '' }
];
