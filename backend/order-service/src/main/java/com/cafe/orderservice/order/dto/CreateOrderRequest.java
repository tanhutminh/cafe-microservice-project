package com.cafe.orderservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record CreateOrderRequest(
    @NotNull @Positive @Schema(example = "3") Long tableId,
    @NotEmpty
        @Valid
        @Schema(
            description =
                "The full item list to open the order with - the table must "
                    + "already be OCCUPIED (see POST /api/tables/{id}/occupy) before this call")
        List<AddOrderItemRequest> items) {}
