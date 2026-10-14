package com.movieticket.security;

import com.movieticket.user.Role;

/** The identity extracted from a verified JWT; injected into controllers with @AuthenticationPrincipal. */
public record AuthenticatedUser(Long id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
