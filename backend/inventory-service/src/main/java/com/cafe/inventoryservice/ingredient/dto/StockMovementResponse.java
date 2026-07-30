package com.cafe.inventoryservice.ingredient.dto;

import com.cafe.inventoryservice.reservation.StockMovement;

import java.math.BigDecimal;
import java.time.Instant;

public record StockMovementResponse(
        Long id,
        BigDecimal changeAmount,
        String reason,
        String referenceId,
        Instant createdAt
) {
    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(
                movement.getId(),
                movement.getChangeAmount(),
                movement.getReason(),
                movement.getReferenceId(),
                movement.getCreatedAt()
        );
    }
}
