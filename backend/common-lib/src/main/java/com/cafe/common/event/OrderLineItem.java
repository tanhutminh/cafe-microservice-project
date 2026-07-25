package com.cafe.common.event;

/**
 * Minimal per-line payload carried on saga/event messages — just enough for
 * inventory-service to look up its own recipe rows (quantityRequired per unit)
 * and for report-service to build its revenue read-model, without either
 * needing to call back into order-service or menu-service.
 */
public record OrderLineItem(Long menuItemId, int quantity) {
}
