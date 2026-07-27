package com.cafe.orderservice.order.dto;

import jakarta.validation.constraints.NotNull;

public record MoveTableRequest(@NotNull Long tableId) {
}
