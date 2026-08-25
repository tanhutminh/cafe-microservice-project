package com.cafe.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

class ScheduledPollerObservationPredicatesTest {

  private static final Set<Class<?>> EXCLUDED_TARGETS = Set.of(Runnable.class, AutoCloseable.class);

  /**
   * Every case also checked against a second, unrelated observation name - the predicate must match
   * purely on the context's target class, not the shared "tasks.scheduled.execution" name every
   * {@code @Scheduled} method uses.
   */
  @ParameterizedTest
  @MethodSource("scheduledPollerPredicateCases")
  void excludingTargets_excludesOnlyTheGivenClasses(Observation.Context context, boolean expected) {
    ObservationPredicate predicate =
        ScheduledPollerObservationPredicates.excludingTargets(EXCLUDED_TARGETS);

    assertAll(
        () -> assertThat(predicate.test("tasks.scheduled.execution", context)).isEqualTo(expected),
        () -> assertThat(predicate.test("some.other.name", context)).isEqualTo(expected));
  }

  private static Stream<Arguments> scheduledPollerPredicateCases() {
    return Stream.of(
        Arguments.of(taskContextFor(Runnable.class), false),
        Arguments.of(taskContextFor(AutoCloseable.class), false),
        Arguments.of(taskContextFor(ScheduledPollerObservationPredicatesTest.class), true),
        Arguments.of(new Observation.Context(), true));
  }

  private static ScheduledTaskObservationContext taskContextFor(Class<?> targetClass) {
    ScheduledTaskObservationContext context = mock(ScheduledTaskObservationContext.class);
    doReturn(targetClass).when(context).getTargetClass();
    return context;
  }
}
