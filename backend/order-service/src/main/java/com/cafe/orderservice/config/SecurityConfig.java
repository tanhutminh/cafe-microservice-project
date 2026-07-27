package com.cafe.orderservice.config;

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
 * order-service is the POS surface (plan section 5/8): CASHIER's whole job lives here
 * (tables + orders), so unlike menu-service there's no read/write split by role — both
 * ADMIN and CASHIER get full access to /api/tables/** and /api/orders/**.
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
                        .requestMatchers("/api/tables/**", "/api/orders/**").hasAnyRole(Roles.ADMIN, Roles.CASHIER)
                        .anyRequest().hasRole(Roles.ADMIN))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new AccessDeniedHandlerImpl()))
                .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
