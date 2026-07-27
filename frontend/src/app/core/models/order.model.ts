export type OrderStatus = 'OPEN' | 'PENDING_CONFIRMATION' | 'PAID' | 'CANCELLED';

export interface OrderItem {
  id: number;
  menuItemId: number;
  name: string;
  price: number;
  quantity: number;
}

export interface Order {
  id: number;
  tableId: number;
  tableNumber: string;
  status: OrderStatus;
  paymentMethod: string | null;
  failureReason: string | null;
  items: OrderItem[];
  grandTotal: number;
  createdAt: string;
  closedAt: string | null;
}

export interface CreateOrderRequest {
  tableId: number;
}

export interface AddOrderItemRequest {
  menuItemId: number;
  quantity: number;
}

export interface CheckoutRequest {
  paymentMethod: string;
}

export interface MoveTableRequest {
  tableId: number;
}
