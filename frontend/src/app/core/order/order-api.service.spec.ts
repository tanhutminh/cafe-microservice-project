import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import {
  CheckoutRequest,
  CreateOrderRequest,
  MoveTableRequest,
  Order,
  PayRequest,
} from '../models/order.model';
import { OrderApiService } from './order-api.service';

describe('OrderApiService', () => {
  let service: OrderApiService;
  let httpMock: HttpTestingController;

  const order: Order = {
    id: 101,
    tableId: 3,
    tableNumber: 'T3',
    status: 'OPEN',
    paymentMethod: null,
    failureReason: null,
    items: [],
    grandTotal: 0,
    createdAt: '2026-08-18T00:00:00Z',
    closedAt: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(OrderApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('listTables() GETs /tables', () => {
    service.listTables().subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/tables`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('occupyTable() POSTs to /tables/{id}/occupy', () => {
    service.occupyTable(3).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/tables/3/occupy`);
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('getOrder() GETs /orders/{id}', () => {
    service.getOrder(101).subscribe((result) => {
      expect(result).toEqual(order);
    });

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/orders/101`);
    expect(req.request.method).toBe('GET');
    req.flush(order);
  });

  it('getCurrentOrderForTable() GETs /orders?tableId=', () => {
    service.getCurrentOrderForTable(3).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/orders?tableId=3`);
    expect(req.request.method).toBe('GET');
    req.flush(order);
  });

  it('createOrder() POSTs to /orders with the table and item list', () => {
    const request: CreateOrderRequest = { tableId: 3, items: [{ menuItemId: 7, quantity: 2 }] };

    service.createOrder(request).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/orders`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(order);
  });

  it('cancelOrder() POSTs to /orders/{id}/cancel', () => {
    service.cancelOrder(101).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/orders/101/cancel`);
    expect(req.request.method).toBe('POST');
    req.flush(order);
  });

  it('checkout() POSTs to /orders/{id}/checkout with the item list', () => {
    const request: CheckoutRequest = { items: [{ menuItemId: 7, quantity: 3 }] };

    service.checkout(101, request).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/orders/101/checkout`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(order);
  });

  it('pay() POSTs to /orders/{id}/pay with the payment method', () => {
    const request: PayRequest = { paymentMethod: 'CASH' };

    service.pay(101, request).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/orders/101/pay`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(order);
  });

  it('moveTable() POSTs to /orders/{id}/move with the target table', () => {
    const request: MoveTableRequest = { tableId: 5 };

    service.moveTable(101, request).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/orders/101/move`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(order);
  });

  it('releaseTable() POSTs to /tables/{id}/release', () => {
    service.releaseTable(3).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/tables/3/release`);
    expect(req.request.method).toBe('POST');
    req.flush({});
  });
});
