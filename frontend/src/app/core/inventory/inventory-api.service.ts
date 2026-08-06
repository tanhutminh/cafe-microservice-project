import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Ingredient, IngredientRequest, StockInRequest } from '../models/ingredient.model';
import { RecipeItem, RecipeItemRequest } from '../models/recipe-item.model';
import { StockMovement } from '../models/stock-movement.model';

@Injectable({ providedIn: 'root' })
export class InventoryApiService {
  private readonly http = inject(HttpClient);

  listIngredients(): Observable<Ingredient[]> {
    return this.http.get<Ingredient[]>(`${environment.apiBaseUrl}/ingredients`);
  }

  createIngredient(request: IngredientRequest): Observable<Ingredient> {
    return this.http.post<Ingredient>(`${environment.apiBaseUrl}/ingredients`, request);
  }

  updateIngredient(id: number, request: IngredientRequest): Observable<Ingredient> {
    return this.http.put<Ingredient>(`${environment.apiBaseUrl}/ingredients/${id}`, request);
  }

  deleteIngredient(id: number): Observable<void> {
    return this.http.delete<void>(`${environment.apiBaseUrl}/ingredients/${id}`);
  }

  stockIn(id: number, request: StockInRequest): Observable<Ingredient> {
    return this.http.post<Ingredient>(`${environment.apiBaseUrl}/ingredients/${id}/stock-in`, request);
  }

  listMovements(id: number): Observable<StockMovement[]> {
    return this.http.get<StockMovement[]>(`${environment.apiBaseUrl}/ingredients/${id}/movements`);
  }

  getRecipe(menuItemId: number): Observable<RecipeItem[]> {
    return this.http.get<RecipeItem[]>(`${environment.apiBaseUrl}/menu-items/${menuItemId}/recipe`);
  }

  replaceRecipe(menuItemId: number, lines: RecipeItemRequest[]): Observable<RecipeItem[]> {
    return this.http.put<RecipeItem[]>(`${environment.apiBaseUrl}/menu-items/${menuItemId}/recipe`, lines);
  }
}
