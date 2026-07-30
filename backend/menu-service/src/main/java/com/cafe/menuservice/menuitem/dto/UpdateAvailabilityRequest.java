package com.cafe.menuservice.menuitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateAvailabilityRequest(
        @NotNull @Schema(example = "false", description = "false = 86 it; true = bring it back") Boolean available
) {
}
