package com.movieticket.config;

import com.movieticket.user.Role;
import com.movieticket.user.User;
import com.movieticket.user.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN account at start-up, but only when ADMIN_PASSWORD is supplied through the
 * environment. The password is never stored in the repository.
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public AdminInitializer(AppProperties props, UserRepository users, PasswordEncoder encoder, Clock clock) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = props.admin().password();
        if (password == null || password.isBlank()) {
            log.info("ADMIN_PASSWORD not set - no admin account created. Catalogue write endpoints will be unavailable.");
            return;
        }
        String email = props.admin().email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            log.info("Admin account {} already exists.", email);
            return;
        }
        users.save(new User(props.admin().name(), email, encoder.encode(password), Role.ADMIN, LocalDateTime.now(clock)));
        log.info("Admin account {} created.", email);
    }
}
