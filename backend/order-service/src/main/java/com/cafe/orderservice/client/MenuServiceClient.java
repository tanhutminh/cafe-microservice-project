package com.cafe.orderservice.client;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.common.exception.ServiceUnavailableException;
import com.cafe.common.security.HeaderAuthenticationFilter;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Synchronous call to menu-service (via Eureka — service-to-service traffic bypasses the gateway).
 * Forwards the same trusted identity headers the gateway set on the inbound request, since
 * menu-service's security never sees a raw JWT either.
 *
 * <p>Wrapped in the Circuit Breaker pattern (Resilience4j, config under resilience4j.* in
 * application.yml, instance name "menu-service"): a response timeout bounds how long a single call
 * can hang, Retry re-attempts transient failures, and once failures cross the configured threshold
 * the breaker opens and fails fast instead of letting every order-service thread pile up waiting on
 * a struggling menu-service.
 */
@Component
public class MenuServiceClient {

  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

  private final WebClient webClient;

  @Autowired
  public MenuServiceClient(WebClient.Builder loadBalancedWebClientBuilder) {
    this(loadBalancedWebClientBuilder, "http://menu-service");
  }

  /** Package-private seam for tests to point this client at a local MockWebServer instead. */
  MenuServiceClient(WebClient.Builder loadBalancedWebClientBuilder, String baseUrl) {
    HttpClient httpClient = HttpClient.create().responseTimeout(RESPONSE_TIMEOUT);
    this.webClient =
        loadBalancedWebClientBuilder
            .baseUrl(baseUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
  }

  /**
   * Resolves every given id in a single round trip instead of one request per id. menu-service's
   * batch endpoint silently omits ids it doesn't find rather than failing the whole request, so
   * this is the one place with both the ids that were asked for and the ones that came back -
   * throws {@link ResourceNotFoundException} for the first requested id missing from the response.
   */
  @CircuitBreaker(name = "menu-service", fallbackMethod = "findMenuItemsFallback")
  @Retry(name = "menu-service")
  public Map<Long, MenuItemDetails> findMenuItemsAsMap(List<Long> menuItemIds) {
    List<MenuItemDetails> found =
        webClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder.path("/api/menu-items/batch").queryParam("ids", menuItemIds).build())
            .headers(this::forwardIdentityHeaders)
            .retrieve()
            .bodyToFlux(MenuItemDetails.class)
            .collectList()
            .block();
    Map<Long, MenuItemDetails> detailsByMenuItemId =
        found.stream().collect(Collectors.toMap(MenuItemDetails::id, Function.identity()));
    for (Long menuItemId : menuItemIds) {
      if (!detailsByMenuItemId.containsKey(menuItemId)) {
        throw ResourceNotFoundException.of("MenuItem", menuItemId);
      }
    }
    return detailsByMenuItemId;
  }

  @SuppressWarnings("unused")
  private Map<Long, MenuItemDetails> findMenuItemsFallback(List<Long> menuItemIds, Throwable t) {
    throw new ServiceUnavailableException(
        "menu-service is unavailable, please try again shortly", t);
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
