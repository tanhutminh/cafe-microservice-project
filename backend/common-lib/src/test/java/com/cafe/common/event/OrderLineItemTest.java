package com.cafe.common.event;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLineItemTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validOrderLineItem_hasNoViolations() {
        assertThat(VALIDATOR.validate(new OrderLineItem(10L, 2))).isEmpty();
    }

    private static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of("nullMenuItemId", null, 1, List.of("menuItemId")),
                Arguments.of("zeroMenuItemId", 0L, 1, List.of("menuItemId")),
                Arguments.of("negativeMenuItemId", -1L, 1, List.of("menuItemId")),
                Arguments.of("zeroQuantity", 10L, 0, List.of("quantity")),
                Arguments.of("negativeQuantity", 10L, -1, List.of("quantity")),
                Arguments.of("bothInvalid", -1L, 0, List.of("menuItemId", "quantity"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInputs")
    void invalidOrderLineItem_violatesExpectedConstraints(String caseName, Long menuItemId, int quantity, List<String> expectedFields) {
        Set<ConstraintViolation<OrderLineItem>> violations = VALIDATOR.validate(new OrderLineItem(menuItemId, quantity));

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrderElementsOf(expectedFields);
    }
}
