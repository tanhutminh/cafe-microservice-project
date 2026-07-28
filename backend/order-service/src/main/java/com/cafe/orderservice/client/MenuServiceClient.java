package com.cafe.orderservice.client;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.common.exception.ServiceUnavailableException;
import com.cafe.common.security.HeaderAuthenticationFilter;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Synchronous call to menu-service (via Eureka, plan section 3 — service-to-service
 * traffic bypasses the gateway). Forwards the same trusted identity headers the gateway
 * set on the inbound request, since menu-service's security never sees a raw JWT either.
 *
 * Wrapped in the Circuit Breaker pattern (Resilience4j, config under
 * resilience4j.* in application.yml, instance name "menu-service"): a response timeout
 * bounds how long a single call can hang, Retry re-attempts transient failures, and once
 * failures cross the configured threshold the breaker opens and fails fast instead of
 * letting every order-service thread pile up waiting on a struggling menu-service.
 */
@Component
public class MenuServiceClient {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;

    public MenuServiceClient(WebClient.Builder loadBalancedWebClientBuilder) {
        HttpClient httpClient = HttpClient.create().responseTimeout(RESPONSE_TIMEOUT);
        this.webClient = loadBalancedWebClientBuilder
                .baseUrl("http://menu-service")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @CircuitBreaker(name = "menu-service", fallbackMethod = "findMenuItemFallback")
    @Retry(name = "menu-service")
    public MenuItemDetails findMenuItem(Long menuItemId) {
        try {
            return webClient.get()
                    .uri("/api/menu-items/{id}", menuItemId)
                    .headers(this::forwardIdentityHeaders)
                    .retrieve()
                    .bodyToMono(MenuItemDetails.class)
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            throw ResourceNotFoundException.of("MenuItem", menuItemId);
        }
    }

    @SuppressWarnings("unused")
    private MenuItemDetails findMenuItemFallback(Long menuItemId, Throwable t) {
        throw new ServiceUnavailableException("menu-service is unavailable, please try again shortly", t);
    }

    private void forwardIdentityHeaders(HttpHeaders headers) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest request = attrs.getRequest();
        copyHeader(request, headers, HeaderAuthenticationFilter.HEADER_USER_ID);
        copyHeader(request, headers, HeaderAuthenticationFilter.HEADER_USERNAME);
        copyHeader(request, headers, HeaderAuthenticationFilter.HEADER_USER_ROLE);
    }

    private void copyHeader(HttpServletRequest request, HttpHeaders headers, String name) {
        String value = request.getHeader(name);
        if (value != null) {
            headers.set(name, value);
        }
    }
}
