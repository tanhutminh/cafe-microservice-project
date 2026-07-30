package com.cafe.authservice.user;

import com.cafe.authservice.user.dto.CreateUserRequest;
import com.cafe.authservice.user.dto.UpdateUserStatusRequest;
import com.cafe.authservice.user.dto.UserResponse;
import com.cafe.common.security.HeaderAuthenticationFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Staff accounts (ADMIN only for every endpoint here)")
@SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ID)
@SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USERNAME)
@SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ROLE)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List every staff account")
    public List<UserResponse> findAll() {
        return userService.findAll().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a staff account")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.create(request.username(), request.password(), request.fullName(), request.role());
        return UserResponse.from(user);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a staff account (a deactivated user can't log in)")
    public UserResponse updateStatus(@Parameter(description = "The user's id", example = "3") @PathVariable Long id, @Valid @RequestBody UpdateUserStatusRequest request) {
        User user = userService.setActive(id, request.active());
        return UserResponse.from(user);
    }
}
