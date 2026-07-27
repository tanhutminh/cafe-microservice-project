package com.cafe.orderservice.saga;

public enum SagaStep {
    STARTED,
    STOCK_RESERVATION_REQUESTED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED
}
