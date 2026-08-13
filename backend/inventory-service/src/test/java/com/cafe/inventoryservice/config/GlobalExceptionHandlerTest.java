package com.cafe.inventoryservice.config;

import com.cafe.common.error.ApiError;
import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests, no Spring context - each handler is exercised by constructing/mocking just
 * enough of its exception argument to drive the method's own logic. handleMethodValidation is
 * the one worth the most scrutiny here: mixing a directly-constrained parameter (e.g. @Positive
 * Long id) with a @Valid @RequestBody one on the same controller method routes both kinds of
 * violation through HandlerMethodValidationException instead of splitting across
 * MethodArgumentNotValidException too - a body violation arrives as a ParameterErrors result
 * (nested field name available), a plain scalar violation doesn't (just the parameter's own
 * name) - this was verified against production Spring behavior via live curl testing after an
 * earlier version of this handler silently discarded nested field names for the body case.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        when(request.getRequestURI()).thenReturn("/api/ingredients/1");
    }

    /** The pass-through handlers - build response, no branching logic beyond the exception's own message. */
    private static Stream<Arguments> simpleHandlers() {
        return Stream.of(
                Arguments.of(
                        "handleNotFound",
                        (BiFunction<GlobalExceptionHandler, HttpServletRequest, ResponseEntity<ApiError>>)
                                (h, req) -> h.handleNotFound(ResourceNotFoundException.of("Ingredient", 1L), req),
                        HttpStatus.NOT_FOUND, "Not Found", "Ingredient not found: 1"
                ),
                Arguments.of(
                        "handleBusinessRule",
                        (BiFunction<GlobalExceptionHandler, HttpServletRequest, ResponseEntity<ApiError>>)
                                (h, req) -> h.handleBusinessRule(new BusinessRuleException("Ingredient is used by an active recipe"), req),
                        HttpStatus.CONFLICT, "Conflict", "Ingredient is used by an active recipe"
                ),
                Arguments.of(
                        "handleNoResource",
                        (BiFunction<GlobalExceptionHandler, HttpServletRequest, ResponseEntity<ApiError>>)
                                (h, req) -> h.handleNoResource(mock(NoResourceFoundException.class), req),
                        HttpStatus.NOT_FOUND, "Not Found", "No resource found for this path"
                ),
                Arguments.of(
                        // Regression test: a non-numeric path segment (e.g. "abc" for a @Positive Long id)
                        // fails type conversion before @Positive ever runs - without this handler it falls
                        // through to the catch-all handleUnexpected and misreports a client error as a 500.
                        "handleTypeMismatch",
                        (BiFunction<GlobalExceptionHandler, HttpServletRequest, ResponseEntity<ApiError>>)
                                (h, req) -> h.handleTypeMismatch(typeMismatchException("id", "abc"), req),
                        HttpStatus.BAD_REQUEST, "Bad Request", "Invalid value for 'id': abc"
                ),
                Arguments.of(
                        "handleUnexpected",
                        (BiFunction<GlobalExceptionHandler, HttpServletRequest, ResponseEntity<ApiError>>)
                                (h, req) -> h.handleUnexpected(new RuntimeException("boom"), req),
                        HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Unexpected error"
                )
        );
    }

    private static MethodArgumentTypeMismatchException typeMismatchException(String name, Object value) {
        MethodArgumentTypeMismatchException e = mock(MethodArgumentTypeMismatchException.class);
        when(e.getName()).thenReturn(name);
        when(e.getValue()).thenReturn(value);
        return e;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("simpleHandlers")
    void simpleHandler_mapsExceptionToExpectedApiError(
            String handlerName,
            BiFunction<GlobalExceptionHandler, HttpServletRequest, ResponseEntity<ApiError>> invoker,
            HttpStatus expectedStatus, String expectedError, String expectedMessage) {
        ResponseEntity<ApiError> response = invoker.apply(handler, request);
        ApiError body = response.getBody();

        assertAll(handlerName,
                () -> assertThat(response.getStatusCode()).isEqualTo(expectedStatus),
                () -> assertThat(body.status()).isEqualTo(expectedStatus.value()),
                () -> assertThat(body.error()).isEqualTo(expectedError),
                () -> assertThat(body.message()).isEqualTo(expectedMessage),
                () -> assertThat(body.path()).isEqualTo("/api/ingredients/1"),
                () -> assertThat(body.validationErrors()).isEmpty(),
                () -> assertThat(body.timestamp()).isNotNull()
        );
    }

    @Test
    void handleValidation_extractsFieldErrorsFromBindingResult() {
        FieldError fieldError = new FieldError("ingredientRequest", "name", "must not be blank");
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiError> response = handler.handleValidation(exception, request);
        ApiError body = response.getBody();

        assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(body.status()).isEqualTo(400),
                () -> assertThat(body.error()).isEqualTo("Validation Failed"),
                () -> assertThat(body.message()).isEqualTo("Request body did not pass validation"),
                () -> assertThat(body.path()).isEqualTo("/api/ingredients/1"),
                () -> assertThat(body.validationErrors())
                        .containsExactly(new ApiError.FieldViolation("name", "must not be blank"))
        );
    }

    /**
     * Both cases end up as a List<ParameterValidationResult> on the mocked exception -
     * ParameterErrors (body case) implements ParameterValidationResult too, so one supplier
     * type covers both the scalar-parameter and nested-body-field shapes.
     */
    private static Stream<Arguments> methodValidationResults() {
        return Stream.of(
                Arguments.of(
                        "scalarParameterViolation_usesParameterName",
                        (Supplier<ParameterValidationResult>) () -> {
                            MethodParameter methodParameter = mock(MethodParameter.class);
                            when(methodParameter.getParameterName()).thenReturn("id");
                            MessageSourceResolvable error = mock(MessageSourceResolvable.class);
                            when(error.getDefaultMessage()).thenReturn("must be greater than 0");
                            ParameterValidationResult result = mock(ParameterValidationResult.class);
                            when(result.getMethodParameter()).thenReturn(methodParameter);
                            when(result.getResolvableErrors()).thenReturn(List.of(error));
                            return result;
                        },
                        new ApiError.FieldViolation("id", "must be greater than 0")
                ),
                Arguments.of(
                        // Regression test: a @Valid @RequestBody parameter on a method that also has a
                        // directly-constrained parameter (e.g. @Positive Long id) arrives as ParameterErrors,
                        // not a plain ParameterValidationResult - the field name must come from the nested
                        // FieldError (getFieldErrors()), not MethodParameter.getParameterName() (which would
                        // just say "request", the parameter's own name, discarding which DTO field failed).
                        "bodyViolation_usesNestedFieldNameNotParameterName",
                        (Supplier<ParameterValidationResult>) () -> {
                            FieldError fieldError = new FieldError("ingredientRequest", "unit", "size must be between 0 and 20");
                            ParameterErrors bodyErrors = mock(ParameterErrors.class);
                            when(bodyErrors.getFieldErrors()).thenReturn(List.of(fieldError));
                            return bodyErrors;
                        },
                        new ApiError.FieldViolation("unit", "size must be between 0 and 20")
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("methodValidationResults")
    void handleMethodValidation_extractsExpectedFieldViolation(
            String caseName, Supplier<ParameterValidationResult> resultSupplier, ApiError.FieldViolation expectedViolation) {
        ParameterValidationResult result = resultSupplier.get();
        HandlerMethodValidationException exception = mock(HandlerMethodValidationException.class);
        when(exception.getParameterValidationResults()).thenReturn(List.of(result));

        ResponseEntity<ApiError> response = handler.handleMethodValidation(exception, request);
        ApiError body = response.getBody();

        assertAll(caseName,
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(body.status()).isEqualTo(400),
                () -> assertThat(body.error()).isEqualTo("Validation Failed"),
                () -> assertThat(body.message()).isEqualTo("Request did not pass validation"),
                () -> assertThat(body.path()).isEqualTo("/api/ingredients/1"),
                () -> assertThat(body.validationErrors()).containsExactly(expectedViolation)
        );
    }
}
