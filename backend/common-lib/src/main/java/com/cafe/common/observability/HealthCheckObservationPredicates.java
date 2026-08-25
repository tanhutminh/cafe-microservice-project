package com.cafe.common.observability;

import io.micrometer.observation.ObservationPredicate;

/**
 * Shared {@link ObservationPredicate} logic for excluding the actuator health-check endpoint from
 * tracing. Kept as a plain static factory rather than an auto-configured bean so each service still
 * declares its own {@code @Bean} explicitly - this is an internal monorepo, not a published starter
 * for external consumers.
 */
public final class HealthCheckObservationPredicates {

  private HealthCheckObservationPredicates() {}

  /**
   * Excludes every observation created while handling a request that {@link
   * HealthCheckMarkingFilter} marked as a health check - HTTP-level and Spring Security's own
   * filter-chain/authorization observations alike, none of which carry a path/URI field to match on
   * individually. Requires {@code HealthCheckMarkingFilter} to be registered ahead of every other
   * observation-producing filter; used by the services that run a Spring Security filter chain
   * (auth, menu, order, inventory, report). config-server, eureka-server, and gateway have no
   * Spring Security filter chain to generate that extra noise, so each keeps its own local,
   * path-based predicate instead - independent of this class, since none of them share the
   * exact-same servlet {@code Observation.Context} shape and dependency footprint this factory
   * assumes.
   */
  public static ObservationPredicate excludingMarkedRequests() {
    return (name, context) -> !HealthCheckRequestContext.isHealthCheck();
  }
}
