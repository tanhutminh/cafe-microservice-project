package com.cafe.common.event;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Minimal per-line payload carried on saga/event messages — just enough for
 * inventory-service to look up its own recipe rows (quantityRequired per unit)
 * and for report-service to build its revenue read-model, without either
 * needing to call back into order-service or menu-service.
 */
public record OrderLineItem(@NotNull @Positive Long menuItemId, @Positive int quantity) {
}
