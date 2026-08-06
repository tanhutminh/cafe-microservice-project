import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { Ingredient, IngredientRequest, StockInRequest } from '../models/ingredient.model';
import { RecipeItemRequest } from '../models/recipe-item.model';
import { InventoryApiService } from './inventory-api.service';

describe('InventoryApiService', () => {
  let service: InventoryApiService;
  let httpMock: HttpTestingController;

  const ingredient: Ingredient = {
    id: 1,
    name: 'Coffee beans',
    unit: 'g',
    currentStock: 100,
    minStock: 10,
    reservedQuantity: 0,
    lowStock: false,
    active: true
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(InventoryApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('listIngredients() GETs /ingredients', () => {
    service.listIngredients().subscribe((result) => {
      expect(result).toEqual([ingredient]);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/ingredients`);
    expect(req.request.method).toBe('GET');
    req.flush([ingredient]);
  });

  it('createIngredient() POSTs to /ingredients with the request body', () => {
    const request: IngredientRequest = { name: 'Milk', unit: 'ml', minStock: 5, active: true };

    service.createIngredient(request).subscribe((result) => {
      expect(result).toEqual(ingredient);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/ingredients`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(ingredient);
  });

  it('updateIngredient() PUTs to /ingredients/{id} with the request body', () => {
    const request: IngredientRequest = { name: 'Coffee beans', unit: 'g', minStock: 10, active: true };

    service.updateIngredient(1, request).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/ingredients/1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(request);
    req.flush(ingredient);
  });

  it('deleteIngredient() DELETEs /ingredients/{id}', () => {
    service.deleteIngredient(1).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/ingredients/1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('stockIn() POSTs to /ingredients/{id}/stock-in with the quantity', () => {
    const request: StockInRequest = { quantity: 25 };

    service.stockIn(1, request).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/ingredients/1/stock-in`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(ingredient);
  });

  it('listMovements() GETs /ingredients/{id}/movements', () => {
    service.listMovements(1).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/ingredients/1/movements`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('getRecipe() GETs /menu-items/{menuItemId}/recipe', () => {
    service.getRecipe(7).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/menu-items/7/recipe`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('replaceRecipe() PUTs to /menu-items/{menuItemId}/recipe with the line list', () => {
    const lines: RecipeItemRequest[] = [{ ingredientId: 1, quantityRequired: 20 }];

    service.replaceRecipe(7, lines).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/menu-items/7/recipe`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(lines);
    req.flush([]);
  });
});
