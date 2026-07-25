package com.cafe.authservice.user.dto;

import com.cafe.authservice.user.Role;
import com.cafe.authservice.user.User;

public record UserResponse(Long id, String username, String fullName, Role role, boolean active) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(), user.getRole(), user.isActive());
    }
}
