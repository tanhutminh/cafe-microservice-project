package com.cafe.inventoryservice.config;

import com.cafe.common.error.ApiError;
import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessRule(BusinessRuleException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "No resource found for this path", request);
    }

    /**
     * A path variable/query param that fails type conversion (e.g. a non-numeric id segment)
     * never reaches @Positive validation - it fails during Spring's own argument resolution
     * with this exception instead, which would otherwise fall through to the catch-all
     * handleUnexpected below and misreport a client input error as a 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        String message = "Invalid value for '" + e.getName() + "': " + e.getValue();
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation Failed",
                "Request body did not pass validation", request.getRequestURI(), violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * @Positive/@Size etc. on a bare @PathVariable/@RequestParam fails with this, not
     * MethodArgumentNotValidException - same ApiError shape as that handler either way. A
     * handler method that mixes a directly-constrained parameter (e.g. @Positive Long id) with
     * a @Valid @RequestBody one has *all* of its validation - including the body's - reported
     * through this single exception instead of splitting across both handlers; a body violation
     * shows up as a {@link ParameterErrors} result (implements Errors, so real nested field
     * names are still available via getFieldErrors()), while a plain scalar violation is just
     * the parameter's own name.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException e, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = e.getParameterValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors bodyErrors
                        ? bodyErrors.getFieldErrors().stream()
                                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                        : result.getResolvableErrors().stream()
                                .map(err -> new ApiError.FieldViolation(result.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation Failed",
                "Request did not pass validation", request.getRequestURI(), violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
