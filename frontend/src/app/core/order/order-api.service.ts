import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DiningTable } from '../models/dining-table.model';
import { AddOrderItemRequest, CreateOrderRequest, MoveTableRequest, Order, PayRequest, UpdateOrderItemQuantityRequest } from '../models/order.model';

@Injectable({ providedIn: 'root' })
export class OrderApiService {
  private readonly http = inject(HttpClient);

  listTables(): Observable<DiningTable[]> {
    return this.http.get<DiningTable[]>(`${environment.apiBaseUrl}/tables`);
  }

  getOrder(orderId: number): Observable<Order> {
    return this.http.get<Order>(`${environment.apiBaseUrl}/orders/${orderId}`);
  }

  getCurrentOrderForTable(tableId: number): Observable<Order> {
    const params = new HttpParams().set('tableId', tableId);
    return this.http.get<Order>(`${environment.apiBaseUrl}/orders`, { params });
  }

  createOrder(request: CreateOrderRequest): Observable<Order> {
    return this.http.post<Order>(`${environment.apiBaseUrl}/orders`, request);
  }

  addItem(orderId: number, request: AddOrderItemRequest): Observable<Order> {
    return this.http.post<Order>(`${environment.apiBaseUrl}/orders/${orderId}/items`, request);
  }

  removeItem(orderId: number, orderItemId: number): Observable<Order> {
    return this.http.delete<Order>(`${environment.apiBaseUrl}/orders/${orderId}/items/${orderItemId}`);
  }

  updateItemQuantity(orderId: number, orderItemId: number, request: UpdateOrderItemQuantityRequest): Observable<Order> {
    return this.http.patch<Order>(`${environment.apiBaseUrl}/orders/${orderId}/items/${orderItemId}`, request);
  }

  cancelOrder(orderId: number): Observable<Order> {
    return this.http.post<Order>(`${environment.apiBaseUrl}/orders/${orderId}/cancel`, {});
  }

  checkout(orderId: number): Observable<Order> {
    return this.http.post<Order>(`${environment.apiBaseUrl}/orders/${orderId}/checkout`, {});
  }

  pay(orderId: number, request: PayRequest): Observable<Order> {
    return this.http.post<Order>(`${environment.apiBaseUrl}/orders/${orderId}/pay`, request);
  }

  moveTable(orderId: number, request: MoveTableRequest): Observable<Order> {
    return this.http.post<Order>(`${environment.apiBaseUrl}/orders/${orderId}/move`, request);
  }

  releaseTable(tableId: number): Observable<DiningTable> {
    return this.http.post<DiningTable>(`${environment.apiBaseUrl}/tables/${tableId}/release`, {});
  }
}
