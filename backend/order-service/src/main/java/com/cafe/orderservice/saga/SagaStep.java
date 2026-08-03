package com.cafe.orderservice.saga;

public enum SagaStep {
    STARTED,
    STOCK_RESERVATION_REQUESTED,
    CONFIRMED,
    PAYMENT_REQUESTED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED
}
