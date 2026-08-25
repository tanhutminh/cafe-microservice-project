package com.cafe.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

class HealthCheckObservationPredicatesTest {

  @AfterEach
  void clearAnyLeftoverMark() {
    HealthCheckRequestContext.clear();
  }

  /**
   * Deliberately checked against several unrelated {@code Observation.Context} subtypes - the whole
   * point of this predicate is that it doesn't inspect the context type at all.
   */
  @Test
  void excludingMarkedRequests_excludesEveryContextTypeWhileMarked() {
    ObservationPredicate predicate = HealthCheckObservationPredicates.excludingMarkedRequests();
    ScheduledTaskObservationContext unrelatedContext = mock(ScheduledTaskObservationContext.class);

    HealthCheckRequestContext.mark();

    assertAll(
        () ->
            assertThat(predicate.test("http.server.requests", new Observation.Context())).isFalse(),
        () -> assertThat(predicate.test("http.server.requests", unrelatedContext)).isFalse());
  }

  @Test
  void excludingMarkedRequests_admitsEveryContextTypeWhenNotMarked() {
    ObservationPredicate predicate = HealthCheckObservationPredicates.excludingMarkedRequests();
    ScheduledTaskObservationContext unrelatedContext = mock(ScheduledTaskObservationContext.class);

    assertAll(
        () ->
            assertThat(predicate.test("http.server.requests", new Observation.Context())).isTrue(),
        () -> assertThat(predicate.test("http.server.requests", unrelatedContext)).isTrue());
  }
}
