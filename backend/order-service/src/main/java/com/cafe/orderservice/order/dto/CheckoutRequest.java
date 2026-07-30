package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(@NotBlank @Schema(example = "CASH") String paymentMethod) {
}
