package com.cafe.common.observability;

/**
 * Marks the current thread as handling a health-check request, so {@link
 * HealthCheckObservationPredicates#excludingMarkedRequests()} can exclude every observation created
 * while processing it - HTTP-level and Spring Security's own filter-chain/authorization
 * observations alike - without needing to inspect each {@code Observation.Context} type
 * individually (several of which, like Spring Security's own filter-chain context, carry no
 * path/URI field at all). Set by {@link HealthCheckMarkingFilter} before the rest of the filter
 * chain runs, cleared once it returns. {@code mark()}/{@code clear()} are public so tests can
 * simulate the marked state directly, without going through a real servlet filter chain.
 */
public final class HealthCheckRequestContext {

  private static final ThreadLocal<Boolean> MARKED = new ThreadLocal<>();

  private HealthCheckRequestContext() {}

  public static void mark() {
    MARKED.set(Boolean.TRUE);
  }

  public static void clear() {
    MARKED.remove();
  }

  public static boolean isHealthCheck() {
    return Boolean.TRUE.equals(MARKED.get());
  }
}
