package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CheckoutRequest(
    @NotEmpty
        @Valid
        @Schema(
            description =
                "The full item list to verify with - replaces whatever the order "
                    + "currently holds")
        List<AddOrderItemRequest> items) {}
