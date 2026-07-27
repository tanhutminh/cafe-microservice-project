package com.cafe.inventoryservice.config;

import com.cafe.common.security.HeaderAuthenticationFilter;
import com.cafe.common.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Inventory management is back-office, ADMIN-only (plan section 5/8) — CASHIER's entire
 * surface is order-service (POS + tables). inventory-service never sees a raw JWT either;
 * it trusts the same gateway-issued identity headers as every other domain service.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // See menu-service's SecurityConfig for why /error must stay open: a denied
                        // request's internal forward to /error re-enters this same filter chain with
                        // a fresh anonymous context, which would otherwise clobber the real 403 into 401.
                        .requestMatchers("/actuator/**", "/error").permitAll()
                        .anyRequest().hasRole(Roles.ADMIN))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new AccessDeniedHandlerImpl()))
                .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
