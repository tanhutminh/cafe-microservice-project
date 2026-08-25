package com.cafe.inventoryservice.config;

import com.cafe.common.observability.HealthCheckMarkingFilter;
import com.cafe.common.observability.HealthCheckObservationPredicates;
import com.cafe.common.observability.ScheduledPollerObservationPredicates;
import com.cafe.inventoryservice.inbox.InboxPoller;
import com.cafe.inventoryservice.outbox.OutboxPoller;
import io.micrometer.observation.ObservationPredicate;
import java.util.Set;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ObservabilityConfig {

  @Bean
  public ObservationPredicate healthCheckObservationPredicate() {
    return HealthCheckObservationPredicates.excludingMarkedRequests();
  }

  @Bean
  public ObservationPredicate scheduledPollerObservationPredicate() {
    return ScheduledPollerObservationPredicates.excludingTargets(
        Set.of(OutboxPoller.class, InboxPoller.class));
  }

  @Bean
  public FilterRegistrationBean<HealthCheckMarkingFilter> healthCheckMarkingFilter() {
    FilterRegistrationBean<HealthCheckMarkingFilter> registration =
        new FilterRegistrationBean<>(new HealthCheckMarkingFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
