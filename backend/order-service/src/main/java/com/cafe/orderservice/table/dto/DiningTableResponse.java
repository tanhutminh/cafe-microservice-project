package com.cafe.orderservice.table.dto;

import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.TableStatus;

public record DiningTableResponse(
        Long id,
        String tableNumber,
        int capacity,
        TableStatus status,
        boolean active
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
