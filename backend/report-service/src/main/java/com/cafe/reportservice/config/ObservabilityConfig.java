package com.cafe.reportservice.config;

import com.cafe.common.observability.HealthCheckMarkingFilter;
import com.cafe.common.observability.HealthCheckObservationPredicates;
import io.micrometer.observation.ObservationPredicate;
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
  public FilterRegistrationBean<HealthCheckMarkingFilter> healthCheckMarkingFilter() {
    FilterRegistrationBean<HealthCheckMarkingFilter> registration =
        new FilterRegistrationBean<>(new HealthCheckMarkingFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
