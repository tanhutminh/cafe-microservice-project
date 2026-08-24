package com.cafe.orderservice.config;

import com.cafe.orderservice.outbox.OutboxPoller;
import com.cafe.orderservice.saga.OrderSagaReconciliationJob;
import io.micrometer.observation.ObservationPredicate;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

@Configuration
public class ObservabilityConfig {

  /**
   * Excludes the health-check endpoint from tracing - matched by its own exact path pattern, not
   * the observation name, since every HTTP server request shares the single name
   * "http.server.requests" and filtering by name would suppress tracing for every endpoint. Assumes
   * the default actuator base path ("/actuator"); changing management.endpoints.web.base-path would
   * silently stop this from matching.
   */
  @Bean
  public ObservationPredicate healthCheckObservationPredicate() {
    return (name, context) ->
        !(context instanceof ServerRequestObservationContext serverContext
            && "/actuator/health".equals(serverContext.getPathPattern()));
  }

  /**
   * Excludes this service's own recurring pollers from tracing - matched by the observation's
   * target class, not the shared "tasks.scheduled.execution" name every {@code @Scheduled} method
   * uses, so adding a new scheduled method elsewhere doesn't silently lose tracing too.
   */
  @Bean
  public ObservationPredicate scheduledPollerObservationPredicate() {
    Set<Class<?>> excludedTargets = Set.of(OutboxPoller.class, OrderSagaReconciliationJob.class);
    return (name, context) ->
        !(context instanceof ScheduledTaskObservationContext taskContext
            && excludedTargets.contains(taskContext.getTargetClass()));
  }
}
