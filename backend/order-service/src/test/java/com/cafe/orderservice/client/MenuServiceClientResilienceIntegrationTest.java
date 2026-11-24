package com.cafe.orderservice.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.common.exception.ServiceUnavailableException;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import com.cafe.orderservice.testsupport.ContainerImages;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerAspect;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfigurationProperties;
import io.github.resilience4j.spring6.retry.configure.RetryAspect;
import io.github.resilience4j.spring6.retry.configure.RetryConfigurationProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("testcontainers")
@SpringBootTest
@TestPropertySource(properties = "app.outbox.poll-enabled=false")
@Testcontainers
@DirtiesContext
class MenuServiceClientResilienceIntegrationTest {

  private static final String LATTE_JSON =
      "{\"id\":1,\"name\":\"Latte\",\"price\":50000,\"available\":true}";

  private static final String SERVICE_UNAVAILABLE_MESSAGE =
      "menu-service is unavailable, please try again shortly";

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer(ContainerImages.POSTGRES);

  @Container @ServiceConnection
  static final KafkaContainer kafka = new KafkaContainer(ContainerImages.KAFKA);

  static final MockWebServer menuService = new MockWebServer();

  @DynamicPropertySource
  static void menuServiceBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.clients.menu-service.base-url", () -> menuService.url("/").toString());
  }

  @AfterAll
  static void shutDownMenuService() throws IOException {
    menuService.shutdown();
  }

  @Autowired private MenuServiceClient menuServiceClient;
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
  @Autowired private RetryRegistry retryRegistry;
  @Autowired private CircuitBreakerConfigurationProperties circuitBreakerProperties;
  @Autowired private RetryConfigurationProperties retryProperties;

  private CircuitBreaker breaker;
  private int requestsBefore;

  @BeforeEach
  void setUp() {
    breaker = circuitBreakerRegistry.find("menu-service").orElseThrow();
    breaker.reset();
    QueueDispatcher dispatcher = new QueueDispatcher();
    dispatcher.setFailFast(new MockResponse().setResponseCode(418));
    menuService.setDispatcher(dispatcher);
    requestsBefore = menuService.getRequestCount();
  }

  @Test
  void menuServiceClient_isProxied_withTheConfiguredCircuitBreakerAndRetry() {
    CircuitBreakerConfig breakerConfig = breaker.getCircuitBreakerConfig();
    RetryConfig retryConfig = retryRegistry.find("menu-service").orElseThrow().getRetryConfig();
    CallNotPermittedException callNotPermitted =
        CallNotPermittedException.createCallNotPermittedException(breaker);
    MenuServiceContractException contractException =
        new MenuServiceContractException(
            "menu-service rejected the request: 400 BAD_REQUEST", new RuntimeException());

    List<Class<?>> aspectClasses = new ArrayList<>();
    if (menuServiceClient instanceof Advised advised) {
      for (Advisor advisor : advised.getAdvisors()) {
        if (advisor.getAdvice() instanceof AbstractAspectJAdvice advice) {
          aspectClasses.add(advice.getAspectJAdviceMethod().getDeclaringClass());
        }
      }
    }

    assertAll(
        () -> assertTrue(AopUtils.isAopProxy(menuServiceClient)),
        () -> assertEquals(List.of(RetryAspect.class, CircuitBreakerAspect.class), aspectClasses),
        () -> assertTrue(circuitBreakerProperties.getInstances().containsKey("menu-service")),
        () -> assertTrue(retryProperties.getInstances().containsKey("menu-service")),
        () -> assertEquals(10, breakerConfig.getSlidingWindowSize()),
        () -> assertEquals(5, breakerConfig.getMinimumNumberOfCalls()),
        () -> assertEquals(50f, breakerConfig.getFailureRateThreshold()),
        () ->
            assertEquals(
                50f,
                circuitBreakerProperties
                    .getInstances()
                    .get("menu-service")
                    .getFailureRateThreshold()),
        () -> assertEquals(3, breakerConfig.getPermittedNumberOfCallsInHalfOpenState()),
        () -> assertEquals(10_000L, breakerConfig.getWaitIntervalFunctionInOpenState().apply(1)),
        () ->
            assertTrue(
                breakerConfig
                    .getIgnoreExceptionPredicate()
                    .test(ResourceNotFoundException.of("MenuItem", 1L))),
        () -> assertTrue(breakerConfig.getIgnoreExceptionPredicate().test(contractException)),
        () -> assertFalse(breakerConfig.getIgnoreExceptionPredicate().test(new RuntimeException())),
        () -> assertEquals(3, retryConfig.getMaxAttempts()),
        () -> assertEquals(3, retryProperties.getInstances().get("menu-service").getMaxAttempts()),
        () -> assertEquals(200L, retryConfig.getIntervalBiFunction().apply(1, null)),
        () -> assertEquals(400L, retryConfig.getIntervalBiFunction().apply(2, null)),
        () ->
            assertFalse(
                retryConfig
                    .getExceptionPredicate()
                    .test(ResourceNotFoundException.of("MenuItem", 1L))),
        () -> assertFalse(retryConfig.getExceptionPredicate().test(contractException)),
        () -> assertFalse(retryConfig.getExceptionPredicate().test(callNotPermitted)),
        () -> assertTrue(retryConfig.getExceptionPredicate().test(new RuntimeException())));
  }

  @Test
  void findMenuItemsAsMap_knownIds_returnsDetailsAndRecordsSuccess() {
    menuService.enqueue(jsonResponse("[" + LATTE_JSON + "]"));

    Map<Long, MenuItemDetails> result = menuServiceClient.findMenuItemsAsMap(List.of(1L));

    assertAll(
        () -> assertEquals(1, result.size()),
        () ->
            assertEquals(
                new MenuItemDetails(1L, "Latte", BigDecimal.valueOf(50000), true), result.get(1L)),
        () -> assertEquals(1, requestsSinceSetUp()),
        () -> assertEquals(1, breaker.getMetrics().getNumberOfSuccessfulCalls()),
        () -> assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls()),
        () -> assertEquals(CircuitBreaker.State.CLOSED, breaker.getState()));
  }

  @Test
  void findMenuItemsAsMap_idMissingFromResponse_throwsResourceNotFoundExceptionAfterOneRequest() {
    menuService.enqueue(jsonResponse("[" + LATTE_JSON + "]"));

    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class,
            () -> menuServiceClient.findMenuItemsAsMap(List.of(1L, 999L)));

    assertAll(
        () -> assertEquals("MenuItem not found: 999", thrown.getMessage()),
        () -> assertEquals(1, requestsSinceSetUp()),
        () -> assertEquals(0, breaker.getMetrics().getNumberOfBufferedCalls()),
        () -> assertEquals(CircuitBreaker.State.CLOSED, breaker.getState()));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504})
  void findMenuItemsAsMap_serverErrorStatus_throwsServiceUnavailableExceptionAfterThreeRequests(
      int status) {
    for (int attempt = 0; attempt < 3; attempt++) {
      menuService.enqueue(new MockResponse().setResponseCode(status));
    }

    ServiceUnavailableException thrown =
        assertThrows(
            ServiceUnavailableException.class,
            () -> menuServiceClient.findMenuItemsAsMap(List.of(1L)));

    WebClientResponseException cause =
        assertInstanceOf(WebClientResponseException.class, thrown.getCause());
    assertAll(
        () -> assertEquals(SERVICE_UNAVAILABLE_MESSAGE, thrown.getMessage()),
        () -> assertEquals(status, cause.getStatusCode().value()),
        () -> assertEquals(3, requestsSinceSetUp()),
        () -> assertEquals(3, breaker.getMetrics().getNumberOfFailedCalls()),
        () -> assertEquals(CircuitBreaker.State.CLOSED, breaker.getState()));
  }

  @Test
  void findMenuItemsAsMap_serverErrorThenSuccess_returnsDetailsAfterTwoRequests() {
    menuService.enqueue(new MockResponse().setResponseCode(503));
    menuService.enqueue(jsonResponse("[" + LATTE_JSON + "]"));

    Map<Long, MenuItemDetails> result = menuServiceClient.findMenuItemsAsMap(List.of(1L));

    assertAll(
        () -> assertEquals(1, result.size()),
        () ->
            assertEquals(
                new MenuItemDetails(1L, "Latte", BigDecimal.valueOf(50000), true), result.get(1L)),
        () -> assertEquals(2, requestsSinceSetUp()),
        () -> assertEquals(1, breaker.getMetrics().getNumberOfFailedCalls()),
        () -> assertEquals(1, breaker.getMetrics().getNumberOfSuccessfulCalls()),
        () -> assertEquals(CircuitBreaker.State.CLOSED, breaker.getState()));
  }

  @Test
  void findMenuItemsAsMap_connectionDropped_throwsServiceUnavailableExceptionAfterThreeRequests() {
    for (int attempt = 0; attempt < 3; attempt++) {
      menuService.enqueue(
          new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    }

    ServiceUnavailableException thrown =
        assertThrows(
            ServiceUnavailableException.class,
            () -> menuServiceClient.findMenuItemsAsMap(List.of(1L)));

    assertAll(
        () -> assertEquals(SERVICE_UNAVAILABLE_MESSAGE, thrown.getMessage()),
        () -> assertInstanceOf(WebClientRequestException.class, thrown.getCause()),
        () -> assertEquals(3, requestsSinceSetUp()),
        () -> assertEquals(3, breaker.getMetrics().getNumberOfFailedCalls()),
        () -> assertEquals(CircuitBreaker.State.CLOSED, breaker.getState()));
  }

  @Test
  void findMenuItemsAsMap_breakerOpen_throwsServiceUnavailableExceptionWithoutRequest() {
    breaker.transitionToOpenState();

    ServiceUnavailableException thrown =
        assertThrows(
            ServiceUnavailableException.class,
            () -> menuServiceClient.findMenuItemsAsMap(List.of(1L)));

    assertAll(
        () -> assertEquals(SERVICE_UNAVAILABLE_MESSAGE, thrown.getMessage()),
        () -> assertInstanceOf(CallNotPermittedException.class, thrown.getCause()),
        () -> assertEquals(0, requestsSinceSetUp()),
        () -> assertEquals(0, breaker.getMetrics().getNumberOfBufferedCalls()),
        () -> assertEquals(1, breaker.getMetrics().getNumberOfNotPermittedCalls()),
        () -> assertEquals(CircuitBreaker.State.OPEN, breaker.getState()));
  }

  @Test
  void findMenuItemsAsMap_breakerOpensMidRetry_stopsRetrying() {
    for (int failure = 0; failure < 4; failure++) {
      breaker.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
    }
    menuService.enqueue(new MockResponse().setResponseCode(503));

    ServiceUnavailableException thrown =
        assertThrows(
            ServiceUnavailableException.class,
            () -> menuServiceClient.findMenuItemsAsMap(List.of(1L)));

    assertAll(
        () -> assertEquals(SERVICE_UNAVAILABLE_MESSAGE, thrown.getMessage()),
        () -> assertInstanceOf(CallNotPermittedException.class, thrown.getCause()),
        () -> assertEquals(1, requestsSinceSetUp()),
        () -> assertEquals(5, breaker.getMetrics().getNumberOfFailedCalls()),
        () -> assertEquals(1, breaker.getMetrics().getNumberOfNotPermittedCalls()),
        () -> assertEquals(CircuitBreaker.State.OPEN, breaker.getState()));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404})
  void findMenuItemsAsMap_clientErrorStatus_throwsMenuServiceContractExceptionAfterOneRequest(
      int status) {
    menuService.enqueue(new MockResponse().setResponseCode(status));

    MenuServiceContractException thrown =
        assertThrows(
            MenuServiceContractException.class,
            () -> menuServiceClient.findMenuItemsAsMap(List.of(1L)));

    WebClientResponseException cause =
        assertInstanceOf(WebClientResponseException.class, thrown.getCause());
    assertAll(
        () ->
            assertEquals(
                "menu-service rejected the request: " + HttpStatusCode.valueOf(status),
                thrown.getMessage()),
        () -> assertEquals(status, cause.getStatusCode().value()),
        () -> assertEquals(1, requestsSinceSetUp()),
        () -> assertEquals(0, breaker.getMetrics().getNumberOfBufferedCalls()),
        () -> assertEquals(CircuitBreaker.State.CLOSED, breaker.getState()));
  }

  private int requestsSinceSetUp() {
    return menuService.getRequestCount() - requestsBefore;
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }
}
