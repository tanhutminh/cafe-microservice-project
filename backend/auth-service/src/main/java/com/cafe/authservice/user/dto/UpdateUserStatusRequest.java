package com.cafe.authservice.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
        @NotNull @Schema(example = "false", description = "false blocks login without deleting the account") Boolean active
) {
}
