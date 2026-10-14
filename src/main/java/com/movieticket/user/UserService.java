package com.movieticket.user;

import com.movieticket.common.exception.ApiException;
import com.movieticket.security.JwtService;
import com.movieticket.user.UserDtos.AuthResponse;
import com.movieticket.user.UserDtos.LoginRequest;
import com.movieticket.user.UserDtos.RegisterRequest;
import com.movieticket.user.UserDtos.UserResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final Clock clock;
    /** Used so that "unknown email" and "wrong password" take the same time and give the same answer. */
    private final String dummyHash;

    public UserService(UserRepository users, PasswordEncoder encoder, JwtService jwt, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.clock = clock;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    @Transactional
    public UserResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw emailTaken(email);
        }
        try {
            User saved = users.saveAndFlush(new User(req.name().trim(), email, encoder.encode(req.password()),
                    Role.USER, LocalDateTime.now(clock)));
            log.info("Registered user id={}", saved.getId());
            return UserResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            // two simultaneous registrations passed the existsByEmail check; the unique index decides
            throw emailTaken(email);
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        User user = users.findByEmail(email).orElse(null);
        boolean ok = encoder.matches(req.password(), user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !ok) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }
        return new AuthResponse(jwt.generateToken(user), "Bearer", jwt.getExpirationSeconds(), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        return users.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User " + id + " does not exist"));
    }

    private ApiException emailTaken(String email) {
        return ApiException.conflict("EMAIL_ALREADY_REGISTERED", "Email " + email + " is already registered");
    }
}
