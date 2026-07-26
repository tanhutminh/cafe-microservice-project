package com.cafe.menuservice.menuitem.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAvailabilityRequest(@NotNull Boolean available) {
}
