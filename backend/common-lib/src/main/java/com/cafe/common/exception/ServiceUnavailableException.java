package com.cafe.common.exception;

/**
 * Signals that a downstream service call could not be completed - the circuit breaker
 * is open, or retries were exhausted. Mapped to 503 Service Unavailable by
 * GlobalExceptionHandler in each service, so callers see a clear transient-failure
 * response instead of a generic 500.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
