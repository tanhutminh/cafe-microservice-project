package com.cafe.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HealthCheckMarkingFilterTest {

  private final HealthCheckMarkingFilter filter = new HealthCheckMarkingFilter();

  @AfterEach
  void clearAnyLeftoverMark() {
    HealthCheckRequestContext.clear();
  }

  @Test
  void doFilterInternal_marksHealthCheckPathDuringChainAndClearsAfter() throws Exception {
    HttpServletRequest request = requestWithUri("/actuator/health");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    doAnswer(
            invocation -> {
              assertThat(HealthCheckRequestContext.isHealthCheck()).isTrue();
              return null;
            })
        .when(chain)
        .doFilter(request, response);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThat(HealthCheckRequestContext.isHealthCheck()).isFalse();
  }

  @Test
  void doFilterInternal_leavesOtherPathsUnmarked() throws Exception {
    HttpServletRequest request = requestWithUri("/api/orders");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    doAnswer(
            invocation -> {
              assertThat(HealthCheckRequestContext.isHealthCheck()).isFalse();
              return null;
            })
        .when(chain)
        .doFilter(request, response);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  void doFilterInternal_clearsMarkEvenIfChainThrows() throws Exception {
    HttpServletRequest request = requestWithUri("/actuator/health");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    doAnswer(
            invocation -> {
              throw new IOException("boom");
            })
        .when(chain)
        .doFilter(request, response);

    assertThrows(IOException.class, () -> filter.doFilterInternal(request, response, chain));

    assertThat(HealthCheckRequestContext.isHealthCheck()).isFalse();
  }

  private static HttpServletRequest requestWithUri(String uri) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(uri);
    return request;
  }
}
