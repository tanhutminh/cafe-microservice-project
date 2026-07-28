package com.cafe.gateway.security;

import com.cafe.common.security.PemKeyUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.security.PublicKey;
import java.util.List;

/**
 * Verifies the JWT's RS256 signature at the edge and, on success, replaces any
 * client-supplied X-User-* headers with trusted values derived from the token's
 * claims before forwarding downstream. Domain services never see the JWT itself
 * (plan section 5) — only these headers, via {@code com.cafe.common.security.HeaderAuthenticationFilter}.
 */
@Component
public class JwtAuthGlobalFilter implements WebFilter, Ordered {

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/actuator"
    );

    private final JwtProperties jwtProperties;
    private final GlobalCorsProperties globalCorsProperties;
    private PublicKey publicKey;

    public JwtAuthGlobalFilter(JwtProperties jwtProperties, GlobalCorsProperties globalCorsProperties) {
        this.jwtProperties = jwtProperties;
        this.globalCorsProperties = globalCorsProperties;
    }

    @PostConstruct
    void init() {
        this.publicKey = PemKeyUtils.parsePublicKey(jwtProperties.publicKey());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // CORS preflight requests never carry Authorization — let them through
        // untouched so the CORS filter can answer them; this filter only guards
        // the actual request that follows.
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getURI().getPath();

        if (isPublic(path)) {
            return chain.filter(stripUserHeaders(exchange));
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        String token = authHeader.substring("Bearer ".length());
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = String.valueOf(claims.get("userId"));
            String username = claims.getSubject();
            String role = String.valueOf(claims.get("role"));

            ServerHttpRequest mutatedRequest = stripUserHeaders(exchange).getRequest().mutate()
                    .header("X-User-Id", userId)
                    .header("X-Username", username)
                    .header("X-User-Role", role)
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized(exchange);
        }
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * Strips any X-User-* headers the caller may have sent directly, so a client can
     * never forge identity by just setting these headers itself — they are only ever
     * trustworthy once this filter (re)sets them from a verified token.
     */
    private ServerWebExchange stripUserHeaders(ServerWebExchange exchange) {
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove("X-User-Id");
                    headers.remove("X-Username");
                    headers.remove("X-User-Role");
                })
                .build();
        return exchange.mutate().request(stripped).build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        applyCorsHeaders(exchange);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /**
     * A 401 short-circuits the exchange before it reaches Gateway's route handler mapping,
     * which is where {@code spring.cloud.gateway.server.webflux.globalcors} normally adds
     * CORS response headers. Without this, a browser sees a response with no
     * Access-Control-Allow-Origin header and reports a CORS failure instead of a 401,
     * so the SPA's redirect-to-login on 401 never fires.
     */
    private void applyCorsHeaders(ServerWebExchange exchange) {
        String requestOrigin = exchange.getRequest().getHeaders().getOrigin();
        if (requestOrigin == null) {
            return;
        }
        CorsConfiguration corsConfig = globalCorsProperties.getCorsConfigurations().get("/**");
        if (corsConfig == null) {
            return;
        }
        String allowedOrigin = corsConfig.checkOrigin(requestOrigin);
        if (allowedOrigin == null) {
            return;
        }
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
        if (Boolean.TRUE.equals(corsConfig.getAllowCredentials())) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }
}
