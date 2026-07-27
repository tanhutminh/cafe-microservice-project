package com.cafe.orderservice.client;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.common.security.HeaderAuthenticationFilter;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Synchronous call to menu-service (via Eureka, plan section 3 — service-to-service
 * traffic bypasses the gateway). Forwards the same trusted identity headers the gateway
 * set on the inbound request, since menu-service's security never sees a raw JWT either.
 */
@Component
public class MenuServiceClient {

    private final WebClient webClient;

    public MenuServiceClient(WebClient.Builder loadBalancedWebClientBuilder) {
        this.webClient = loadBalancedWebClientBuilder.baseUrl("http://menu-service").build();
    }

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
