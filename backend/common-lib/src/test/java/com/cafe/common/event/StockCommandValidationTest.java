package com.cafe.common.event;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InventoryReserveStockCommand, InventoryCommitStockCommand and InventoryReleaseStockCommand
 * share the exact same (orderId, items) validation shape, so one parameterized suite - driven
 * by each record's constructor reference - covers all three instead of tripling the test code.
 */
class StockCommandValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Stream<Arguments> commandConstructors() {
        return Stream.of(
                Arguments.of("InventoryReserveStockCommand", (BiFunction<Long, List<OrderLineItem>, Object>) InventoryReserveStockCommand::new),
                Arguments.of("InventoryCommitStockCommand", (BiFunction<Long, List<OrderLineItem>, Object>) InventoryCommitStockCommand::new),
                Arguments.of("InventoryReleaseStockCommand", (BiFunction<Long, List<OrderLineItem>, Object>) InventoryReleaseStockCommand::new)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commandConstructors")
    void validCommand_hasNoViolations(String name, BiFunction<Long, List<OrderLineItem>, Object> ctor) {
        Object command = ctor.apply(1L, List.of(new OrderLineItem(10L, 2)));

        assertThat(VALIDATOR.validate(command)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commandConstructors")
    void nullOrderId_violatesNotNull(String name, BiFunction<Long, List<OrderLineItem>, Object> ctor) {
        Object command = ctor.apply(null, List.of(new OrderLineItem(10L, 2)));

        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(command);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("orderId");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commandConstructors")
    void nonPositiveOrderId_violatesPositive(String name, BiFunction<Long, List<OrderLineItem>, Object> ctor) {
        Object command = ctor.apply(0L, List.of(new OrderLineItem(10L, 2)));

        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(command);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("orderId");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commandConstructors")
    void emptyItems_violatesNotEmpty(String name, BiFunction<Long, List<OrderLineItem>, Object> ctor) {
        Object command = ctor.apply(1L, List.of());

        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(command);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("items");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commandConstructors")
    void invalidNestedItem_cascadesViaValidAnnotation(String name, BiFunction<Long, List<OrderLineItem>, Object> ctor) {
        // Regression coverage for @Valid on the items list itself - without it, a structurally
        // invalid line (non-positive menuItemId, non-positive quantity) would sail through undetected.
        Object command = ctor.apply(1L, List.of(new OrderLineItem(-1L, 0)));

        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(command);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("items[0].menuItemId", "items[0].quantity");
    }
}
