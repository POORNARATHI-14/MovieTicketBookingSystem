package com.movieticket.user;

import com.movieticket.common.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public final class UserDtos {

    private UserDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must be at most 100 characters") String name,
            @NotBlank(message = "email is required") @Email(message = "email must be a valid address")
            @Size(max = 150, message = "email must be at most 150 characters") String email,
            @NotBlank(message = "password is required") @StrongPassword String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "email is required") String email,
            @NotBlank(message = "password is required") String password) {
    }

    public record UserResponse(Long id, String name, String email, Role role, LocalDateTime createdAt) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getName(), u.getEmail(), u.getRole(), u.getCreatedAt());
        }
    }

    public record AuthResponse(String token, String tokenType, long expiresInSeconds, UserResponse user) {
    }
}
