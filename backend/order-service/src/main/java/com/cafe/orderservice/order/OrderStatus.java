package com.cafe.orderservice.order;

public enum OrderStatus {
    OPEN,
    PENDING_CONFIRMATION,
    CONFIRMED,
    PAYMENT_PENDING,
    PAID,
    CANCELLED
}
