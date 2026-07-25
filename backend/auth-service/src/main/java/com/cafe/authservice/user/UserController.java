package com.cafe.authservice.user;

import com.cafe.authservice.user.dto.CreateUserRequest;
import com.cafe.authservice.user.dto.UpdateUserStatusRequest;
import com.cafe.authservice.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponse> findAll() {
        return userService.findAll().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.create(request.username(), request.password(), request.fullName(), request.role());
        return UserResponse.from(user);
    }

    @PatchMapping("/{id}/status")
    public UserResponse updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateUserStatusRequest request) {
        User user = userService.setActive(id, request.active());
        return UserResponse.from(user);
    }
}
