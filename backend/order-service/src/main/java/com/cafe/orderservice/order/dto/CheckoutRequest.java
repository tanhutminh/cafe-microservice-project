package com.cafe.orderservice.order.dto;

import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(@NotBlank String paymentMethod) {
}
