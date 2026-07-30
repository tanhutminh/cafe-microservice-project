package com.cafe.menuservice.config;

import com.cafe.common.security.HeaderAuthenticationFilter;
import com.cafe.common.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * menu-service never sees a JWT — it trusts the X-User-* headers the gateway
 * sets after verifying the token (plan section 5). Reads are open to any
 * authenticated staff (ADMIN or CASHIER, e.g. for a future POS screen);
 * writes are ADMIN-only except the availability toggle, which CASHIER also
 * needs to 86 an item from the counter.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // /error must stay open: a denied request forwards internally to /error to
                        // render the response body, and that forward re-enters this same filter chain
                        // with a fresh (anonymous) context — without this, the real 403 gets clobbered
                        // into a misleading 401 from anyRequest().hasRole(ADMIN) below.
                        .requestMatchers("/actuator/**", "/error", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories/**", "/api/menu-items/**").hasAnyRole(Roles.ADMIN, Roles.CASHIER)
                        .requestMatchers(HttpMethod.PATCH, "/api/menu-items/*/availability").hasAnyRole(Roles.ADMIN, Roles.CASHIER)
                        .anyRequest().hasRole(Roles.ADMIN))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new AccessDeniedHandlerImpl()))
                .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
