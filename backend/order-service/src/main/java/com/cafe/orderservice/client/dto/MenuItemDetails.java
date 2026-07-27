package com.cafe.orderservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** Subset of menu-service's MenuItemResponse — only what order-service needs to snapshot a line item. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MenuItemDetails(Long id, String name, BigDecimal price, boolean available) {
}
