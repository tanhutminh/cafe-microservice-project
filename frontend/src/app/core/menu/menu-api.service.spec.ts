import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { CategoryRequest } from '../models/category.model';
import { MenuItemRequest } from '../models/menu-item.model';
import { MenuApiService } from './menu-api.service';

describe('MenuApiService', () => {
  const categoriesUrl = `${environment.apiBaseUrl}/categories`;
  const menuItemsUrl = `${environment.apiBaseUrl}/menu-items`;

  const categoryRequest: CategoryRequest = { name: 'Coffee', displayOrder: 1, active: true };
  const menuItemRequest: MenuItemRequest = {
    categoryId: 1,
    name: 'Latte',
    description: null,
    price: 45000,
    imageUrl: null,
    available: true,
    active: true,
  };

  let service: MenuApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MenuApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('listCategories() GETs /categories', () => {
    service.listCategories().subscribe();

    const req = httpMock.expectOne(categoriesUrl);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('createCategory() POSTs the request to /categories', () => {
    service.createCategory(categoryRequest).subscribe();

    const req = httpMock.expectOne(categoriesUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(categoryRequest);
    req.flush({});
  });

  it('updateCategory() PUTs the request to /categories/{id}', () => {
    service.updateCategory(3, categoryRequest).subscribe();

    const req = httpMock.expectOne(`${categoriesUrl}/3`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(categoryRequest);
    req.flush({});
  });

  it('deleteCategory() DELETEs /categories/{id}', () => {
    service.deleteCategory(3).subscribe();

    const req = httpMock.expectOne(`${categoriesUrl}/3`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('listMenuItems() without a category GETs /menu-items with no query parameter', () => {
    service.listMenuItems().subscribe();

    const req = httpMock.expectOne((r) => r.url === menuItemsUrl);
    expect(req.request.method).toBe('GET');
    expect(req.request.urlWithParams).toBe(menuItemsUrl);
    req.flush([]);
  });

  // 0 is falsy, so this pins the `!= null` check: a real category id 0 must still filter.
  it.each([5, 0])('listMenuItems(%i) filters by categoryId', (categoryId) => {
    service.listMenuItems(categoryId).subscribe();

    const req = httpMock.expectOne((r) => r.url === menuItemsUrl);
    expect(req.request.urlWithParams).toBe(`${menuItemsUrl}?categoryId=${categoryId}`);
    req.flush([]);
  });

  it('createMenuItem() POSTs the request to /menu-items', () => {
    service.createMenuItem(menuItemRequest).subscribe();

    const req = httpMock.expectOne(menuItemsUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(menuItemRequest);
    req.flush({});
  });

  it('updateMenuItem() PUTs the request to /menu-items/{id}', () => {
    service.updateMenuItem(7, menuItemRequest).subscribe();

    const req = httpMock.expectOne(`${menuItemsUrl}/7`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(menuItemRequest);
    req.flush({});
  });

  it.each([true, false])(
    'updateMenuItemAvailability() PATCHes { available: %s } to /menu-items/{id}/availability',
    (available) => {
      service.updateMenuItemAvailability(7, available).subscribe();

      const req = httpMock.expectOne(`${menuItemsUrl}/7/availability`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ available });
      req.flush({});
    },
  );
});
