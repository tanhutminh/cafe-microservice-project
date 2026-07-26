import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Category, CategoryRequest } from '../models/category.model';
import { MenuItem, MenuItemRequest } from '../models/menu-item.model';

@Injectable({ providedIn: 'root' })
export class MenuApiService {
  private readonly http = inject(HttpClient);

  listCategories(): Observable<Category[]> {
    return this.http.get<Category[]>(`${environment.apiBaseUrl}/categories`);
  }

  createCategory(request: CategoryRequest): Observable<Category> {
    return this.http.post<Category>(`${environment.apiBaseUrl}/categories`, request);
  }

  updateCategory(id: number, request: CategoryRequest): Observable<Category> {
    return this.http.put<Category>(`${environment.apiBaseUrl}/categories/${id}`, request);
  }

  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${environment.apiBaseUrl}/categories/${id}`);
  }

  listMenuItems(): Observable<MenuItem[]> {
    return this.http.get<MenuItem[]>(`${environment.apiBaseUrl}/menu-items`);
  }

  createMenuItem(request: MenuItemRequest): Observable<MenuItem> {
    return this.http.post<MenuItem>(`${environment.apiBaseUrl}/menu-items`, request);
  }

  updateMenuItem(id: number, request: MenuItemRequest): Observable<MenuItem> {
    return this.http.put<MenuItem>(`${environment.apiBaseUrl}/menu-items/${id}`, request);
  }

  updateMenuItemAvailability(id: number, available: boolean): Observable<MenuItem> {
    return this.http.patch<MenuItem>(`${environment.apiBaseUrl}/menu-items/${id}/availability`, { available });
  }
}
