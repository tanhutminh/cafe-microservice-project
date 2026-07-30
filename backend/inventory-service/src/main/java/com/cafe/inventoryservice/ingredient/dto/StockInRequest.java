package com.cafe.inventoryservice.ingredient.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record StockInRequest(@NotNull @DecimalMin("0.001") BigDecimal quantity) {
}
