package com.cafe.orderservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.cafe.common.observability.HealthCheckMarkingFilter;
import com.cafe.common.observability.HealthCheckRequestContext;
import com.cafe.orderservice.outbox.OutboxPoller;
import com.cafe.orderservice.saga.OrderSagaReconciliationJob;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

class ObservabilityConfigTest {

  private final ObservabilityConfig config = new ObservabilityConfig();

  @AfterEach
  void clearAnyLeftoverMark() {
    HealthCheckRequestContext.clear();
  }

  /**
   * Confirms the bean delegates to the shared factory/filter - exhaustive edge-case coverage of the
   * predicate's own matching logic lives in HealthCheckObservationPredicatesTest in common-lib.
   */
  @Test
  void healthCheckObservationPredicate_delegatesToSharedPredicate() {
    ObservationPredicate predicate = config.healthCheckObservationPredicate();

    HealthCheckRequestContext.mark();
    boolean excludedWhileMarked = predicate.test("http.server.requests", new Observation.Context());
    HealthCheckRequestContext.clear();
    boolean admittedWhenNotMarked =
        predicate.test("http.server.requests", new Observation.Context());

    assertAll(
        () -> assertThat(excludedWhileMarked).isFalse(),
        () -> assertThat(admittedWhenNotMarked).isTrue());
  }

  @Test
  void healthCheckMarkingFilter_registeredWithHighestPrecedence() {
    FilterRegistrationBean<HealthCheckMarkingFilter> registration =
        config.healthCheckMarkingFilter();

    assertAll(
        () -> assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE),
        () -> assertThat(registration.getFilter()).isInstanceOf(HealthCheckMarkingFilter.class));
  }

  /**
   * Confirms the bean delegates to the shared factory with the right target classes - exhaustive
   * edge-case coverage of the predicate's own matching logic lives in
   * ScheduledPollerObservationPredicatesTest in common-lib.
   */
  @ParameterizedTest
  @MethodSource("scheduledPollerPredicateCases")
  void scheduledPollerObservationPredicate_delegatesWithTheKnownPollers(
      Observation.Context context, boolean expected) {
    ObservationPredicate predicate = config.scheduledPollerObservationPredicate();

    assertAll(
        () -> assertThat(predicate.test("tasks.scheduled.execution", context)).isEqualTo(expected),
        () -> assertThat(predicate.test("some.other.name", context)).isEqualTo(expected));
  }

  private static Stream<Arguments> scheduledPollerPredicateCases() {
    return Stream.of(
        Arguments.of(taskContextFor(OutboxPoller.class), false),
        Arguments.of(taskContextFor(OrderSagaReconciliationJob.class), false),
        Arguments.of(taskContextFor(ObservabilityConfigTest.class), true));
  }

  private static ScheduledTaskObservationContext taskContextFor(Class<?> targetClass) {
    ScheduledTaskObservationContext context = mock(ScheduledTaskObservationContext.class);
    doReturn(targetClass).when(context).getTargetClass();
    return context;
  }
}
