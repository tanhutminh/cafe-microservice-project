package com.cafe.common.observability;

import io.micrometer.observation.ObservationPredicate;
import java.util.Set;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Shared {@link ObservationPredicate} logic for excluding a service's own recurring
 * {@code @Scheduled} pollers from tracing. Kept as a plain static factory rather than an
 * auto-configured bean so each service still declares its own {@code @Bean} explicitly - this is an
 * internal monorepo, not a published starter for external consumers.
 */
public final class ScheduledPollerObservationPredicates {

  private ScheduledPollerObservationPredicates() {}

  /**
   * Excludes the given classes' scheduled-task spans from tracing - matched by the observation's
   * target class, not the shared "tasks.scheduled.execution" name every {@code @Scheduled} method
   * uses, so excluding these classes doesn't silently exclude every other scheduled method too.
   */
  public static ObservationPredicate excludingTargets(Set<Class<?>> excludedTargets) {
    return (name, context) ->
        !(context instanceof ScheduledTaskObservationContext taskContext
            && excludedTargets.contains(taskContext.getTargetClass()));
  }
}
