package com.cafe.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

class ObservabilityConfigTest {

  private final ObservabilityConfig config = new ObservabilityConfig();

  /**
   * Every case also checked against a second, unrelated observation name - the predicate must match
   * purely on the carrier request's raw path, not the shared "http.server.requests" name every HTTP
   * request uses.
   */
  @ParameterizedTest
  @MethodSource("healthCheckPredicateCases")
  void healthCheckObservationPredicate_matchesOnlyTheExactHealthPath(
      Observation.Context context, boolean expected) {
    ObservationPredicate predicate = config.healthCheckObservationPredicate();

    assertAll(
        () -> assertThat(predicate.test("http.server.requests", context)).isEqualTo(expected),
        () -> assertThat(predicate.test("some.other.name", context)).isEqualTo(expected));
  }

  private static Stream<Arguments> healthCheckPredicateCases() {
    ServerHttpRequest healthCheckRequest = requestWithPath("/actuator/health");
    ServerRequestObservationContext healthCheck = mock(ServerRequestObservationContext.class);
    when(healthCheck.getCarrier()).thenReturn(healthCheckRequest);

    ServerHttpRequest otherRequest = requestWithPath("/api/orders/1");
    ServerRequestObservationContext otherEndpoint = mock(ServerRequestObservationContext.class);
    when(otherEndpoint.getCarrier()).thenReturn(otherRequest);

    return Stream.of(
        Arguments.of(healthCheck, false),
        Arguments.of(otherEndpoint, true),
        Arguments.of(new Observation.Context(), true));
  }

  private static ServerHttpRequest requestWithPath(String path) {
    RequestPath requestPath = mock(RequestPath.class);
    when(requestPath.value()).thenReturn(path);

    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(requestPath);
    return request;
  }
}
