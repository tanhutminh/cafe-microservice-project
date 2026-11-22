package com.cafe.orderservice.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.testsupport.ContainerImages;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerAspect;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfigurationProperties;
import io.github.resilience4j.spring6.retry.configure.RetryAspect;
import io.github.resilience4j.spring6.retry.configure.RetryConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
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

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer(ContainerImages.POSTGRES);

  @Container @ServiceConnection
  static final KafkaContainer kafka = new KafkaContainer(ContainerImages.KAFKA);

  @Autowired private MenuServiceClient menuServiceClient;
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
  @Autowired private RetryRegistry retryRegistry;
  @Autowired private CircuitBreakerConfigurationProperties circuitBreakerProperties;
  @Autowired private RetryConfigurationProperties retryProperties;

  @Test
  void menuServiceClient_isProxied_withTheConfiguredCircuitBreakerAndRetry() {
    boolean breakerConfigured = circuitBreakerRegistry.find("menu-service").isPresent();
    boolean retryConfigured = retryRegistry.find("menu-service").isPresent();
    CircuitBreakerConfig breaker =
        circuitBreakerRegistry.circuitBreaker("menu-service").getCircuitBreakerConfig();
    RetryConfig retry = retryRegistry.retry("menu-service").getRetryConfig();
    CallNotPermittedException callNotPermitted =
        CallNotPermittedException.createCallNotPermittedException(
            circuitBreakerRegistry.circuitBreaker("menu-service"));
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
        () -> assertTrue(breakerConfigured),
        () -> assertTrue(retryConfigured),
        () -> assertEquals(10, breaker.getSlidingWindowSize()),
        () -> assertEquals(5, breaker.getMinimumNumberOfCalls()),
        () -> assertEquals(50f, breaker.getFailureRateThreshold()),
        () ->
            assertEquals(
                50f,
                circuitBreakerProperties
                    .getInstances()
                    .get("menu-service")
                    .getFailureRateThreshold()),
        () -> assertEquals(3, breaker.getPermittedNumberOfCallsInHalfOpenState()),
        () -> assertEquals(10_000L, breaker.getWaitIntervalFunctionInOpenState().apply(1)),
        () ->
            assertTrue(
                breaker
                    .getIgnoreExceptionPredicate()
                    .test(ResourceNotFoundException.of("MenuItem", 1L))),
        () -> assertFalse(breaker.getIgnoreExceptionPredicate().test(new RuntimeException())),
        () -> assertEquals(3, retry.getMaxAttempts()),
        () -> assertEquals(3, retryProperties.getInstances().get("menu-service").getMaxAttempts()),
        () -> assertEquals(200L, retry.getIntervalBiFunction().apply(1, null)),
        () -> assertEquals(400L, retry.getIntervalBiFunction().apply(2, null)),
        () ->
            assertFalse(
                retry.getExceptionPredicate().test(ResourceNotFoundException.of("MenuItem", 1L))),
        () -> assertFalse(retry.getExceptionPredicate().test(callNotPermitted)),
        () -> assertTrue(retry.getExceptionPredicate().test(new RuntimeException())));
  }
}
