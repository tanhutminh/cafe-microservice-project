export type OrderStatus =
  'OPEN' | 'PENDING_CONFIRMATION' | 'CONFIRMED' | 'PAYMENT_PENDING' | 'PAID' | 'CANCELLED';

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

/**
 * A locally-picked line item, before it's ever been sent to the server - same shape as OrderItem
 * minus `id`, since a draft item has no order-item id until Confirm actually persists it.
 */
export interface DraftItem {
  menuItemId: number;
  name: string;
  price: number;
  quantity: number;
}

export interface AddOrderItemRequest {
  menuItemId: number;
  quantity: number;
}

export interface CreateOrderRequest {
  tableId: number;
  items: AddOrderItemRequest[];
}

export interface CheckoutRequest {
  items: AddOrderItemRequest[];
}

export interface PayRequest {
  paymentMethod: string;
}

export interface MoveTableRequest {
  tableId: number;
}
