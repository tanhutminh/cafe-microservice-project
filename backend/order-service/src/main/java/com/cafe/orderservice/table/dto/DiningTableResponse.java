package com.cafe.orderservice.table.dto;

import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.TableStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record DiningTableResponse(
        @Schema(example = "3") Long id,
        @Schema(example = "Table 3") String tableNumber,
        @Schema(example = "4") int capacity,
        @Schema(example = "OCCUPIED") TableStatus status,
        @Schema(example = "true") boolean active
) {
    public static DiningTableResponse from(DiningTable table) {
        return new DiningTableResponse(
                table.getId(),
                table.getTableNumber(),
                table.getCapacity(),
                table.getStatus(),
                table.isActive()
        );
    }
}
