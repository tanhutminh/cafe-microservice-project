package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record PayRequest(@NotBlank @Schema(example = "CASH") String paymentMethod) {
}
