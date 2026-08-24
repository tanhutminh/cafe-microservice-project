package com.cafe.configserver.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

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
}
