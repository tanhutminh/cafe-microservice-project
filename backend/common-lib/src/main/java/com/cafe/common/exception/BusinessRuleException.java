package com.cafe.common.exception;

/**
 * Signals a violated business invariant (e.g. table already occupied, insufficient stock).
 * Mapped to 409 Conflict by GlobalExceptionHandler in each service.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
