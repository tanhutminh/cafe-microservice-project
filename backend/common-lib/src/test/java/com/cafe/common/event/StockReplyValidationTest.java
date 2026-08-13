package com.cafe.common.event;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InventoryStockReservationReply and InventoryStockCommitReply share the same (orderId,
 * success, reason) validation shape - only orderId is constrained - so one parameterized suite,
 * driven by each record's own success()/failure() factory methods, covers both.
 */
class StockReplyValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Stream<Arguments> successFactories() {
        return Stream.of(
                Arguments.of("InventoryStockReservationReply", (Function<Long, Object>) InventoryStockReservationReply::success),
                Arguments.of("InventoryStockCommitReply", (Function<Long, Object>) InventoryStockCommitReply::success)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successFactories")
    void validReply_hasNoViolations(String name, Function<Long, Object> successFactory) {
        assertThat(VALIDATOR.validate(successFactory.apply(1L))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successFactories")
    void nullOrderId_violatesNotNull(String name, Function<Long, Object> successFactory) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(successFactory.apply(null));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("orderId");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successFactories")
    void nonPositiveOrderId_violatesPositive(String name, Function<Long, Object> successFactory) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(successFactory.apply(-1L));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("orderId");
    }

    private static Stream<Arguments> failureFactories() {
        return Stream.of(
                Arguments.of("InventoryStockReservationReply", (BiFunction<Long, String, Object>) InventoryStockReservationReply::failure),
                Arguments.of("InventoryStockCommitReply", (BiFunction<Long, String, Object>) InventoryStockCommitReply::failure)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureFactories")
    void validFailureReply_hasNoViolations(String name, BiFunction<Long, String, Object> failureFactory) {
        assertThat(VALIDATOR.validate(failureFactory.apply(1L, "out of stock"))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureFactories")
    void failureReply_nullOrderId_violatesNotNull(String name, BiFunction<Long, String, Object> failureFactory) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(failureFactory.apply(null, "out of stock"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("orderId");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureFactories")
    void failureReply_nonPositiveOrderId_violatesPositive(String name, BiFunction<Long, String, Object> failureFactory) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(failureFactory.apply(-1L, "out of stock"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("orderId");
    }
}
