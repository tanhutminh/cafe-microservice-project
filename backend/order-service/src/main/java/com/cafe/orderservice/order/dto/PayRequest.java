package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PayRequest(@NotBlank @Pattern(regexp = "CASH|CARD") @Schema(example = "CASH") String paymentMethod) {
}
