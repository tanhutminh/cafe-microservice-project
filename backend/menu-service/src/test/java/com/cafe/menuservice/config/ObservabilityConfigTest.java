package com.cafe.menuservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.cafe.common.observability.HealthCheckMarkingFilter;
import com.cafe.common.observability.HealthCheckRequestContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/**
 * Confirms the beans delegate to the shared factory/filter - exhaustive edge-case coverage of the
 * predicate's own matching logic lives in HealthCheckObservationPredicatesTest in common-lib.
 */
class ObservabilityConfigTest {

  private final ObservabilityConfig config = new ObservabilityConfig();

  @AfterEach
  void clearAnyLeftoverMark() {
    HealthCheckRequestContext.clear();
  }

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
}
