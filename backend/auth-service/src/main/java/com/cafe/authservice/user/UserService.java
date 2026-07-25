package com.cafe.authservice.user;

import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> ResourceNotFoundException.of("User", username));
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }

    @Transactional
    public User create(String username, String rawPassword, String fullName, Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessRuleException("Username already taken: " + username);
        }
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .fullName(fullName)
                .role(role)
                .active(true)
                .build();
        return userRepository.save(user);
    }

    @Transactional
    public User setActive(Long id, boolean active) {
        User user = findById(id);
        user.setActive(active);
        return userRepository.save(user);
    }
}
