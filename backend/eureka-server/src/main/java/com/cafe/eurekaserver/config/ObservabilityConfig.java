package com.cafe.eurekaserver.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
public class ObservabilityConfig {

  /**
   * Excludes the health-check endpoint from tracing - matched against the raw request's URI via
   * {@link ServerRequestObservationContext#getCarrier()}, not {@code getPathPattern()}: the
   * predicate runs when the observation starts, which happens before the request is dispatched to a
   * handler, so the resolved path pattern is always {@code null} at that point - only the raw
   * request is available that early. Not the observation name either, since every HTTP server
   * request shares the single name "http.server.requests" and filtering by name would suppress
   * tracing for every endpoint. Assumes the default actuator base path ("/actuator") and no {@code
   * server.servlet.context-path}; a raw URI comparison is also stricter than pattern matching (no
   * trailing-slash normalization), which is fine for a fixed literal path like this.
   */
  @Bean
  public ObservationPredicate healthCheckObservationPredicate() {
    return (name, context) ->
        !(context instanceof ServerRequestObservationContext serverContext
            && "/actuator/health".equals(serverContext.getCarrier().getRequestURI()));
  }
}
