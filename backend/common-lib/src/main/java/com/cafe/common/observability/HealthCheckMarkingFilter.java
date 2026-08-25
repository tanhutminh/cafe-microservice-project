package com.cafe.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Marks the current thread via {@link HealthCheckRequestContext} when the request targets the
 * actuator health-check endpoint, so downstream {@code ObservationPredicate} beans can exclude
 * every observation the request triggers. Must run before any observation-producing filter -
 * register with the highest possible precedence ({@code Ordered.HIGHEST_PRECEDENCE}) so this
 * filter's own {@code chain.doFilter()} call is what actually invokes them, both the HTTP request
 * observation filter and Spring Security's filter chain. Assumes a synchronous, non-async request
 * lifecycle (this codebase has no async controllers as of writing): an async re-dispatch would skip
 * re-marking, since {@link OncePerRequestFilter} doesn't re-run itself on an {@code ASYNC} dispatch
 * by default, while the observation filters it's meant to precede do.
 */
public class HealthCheckMarkingFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("/actuator/health".equals(request.getRequestURI())) {
      HealthCheckRequestContext.mark();
    }
    try {
      chain.doFilter(request, response);
    } finally {
      HealthCheckRequestContext.clear();
    }
  }
}
