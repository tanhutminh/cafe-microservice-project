package com.cafe.authservice.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dev-only bootstrap: creates a default ADMIN account if the users table is empty,
 * since there is no self-registration flow (staff accounts are admin-provisioned).
 * A real deployment should seed its first admin out-of-band instead.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";

    private final UserRepository userRepository;
    private final UserService userService;

    public AdminSeeder(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            userService.create(DEFAULT_USERNAME, DEFAULT_PASSWORD, "Default Admin", Role.ADMIN);
            log.warn("Seeded default admin user (dev only) — username='{}' password='{}'. " +
                    "Change or remove this before any non-local deployment.", DEFAULT_USERNAME, DEFAULT_PASSWORD);
        }
    }
}
