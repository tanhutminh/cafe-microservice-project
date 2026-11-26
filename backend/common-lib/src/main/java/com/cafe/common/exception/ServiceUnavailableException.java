package com.cafe.common.exception;

/**
 * Signals that a call to a downstream service could not be completed because that service is
 * unavailable. The failure is expected to be temporary, so the same call may succeed later. This
 * exception corresponds to HTTP 503 Service Unavailable.
 */
public class ServiceUnavailableException extends RuntimeException {

  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
