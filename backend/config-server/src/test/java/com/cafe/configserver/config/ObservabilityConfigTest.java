package com.cafe.configserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.server.observation.ServerRequestObservationContext;

class ObservabilityConfigTest {

  private final ObservabilityConfig config = new ObservabilityConfig();

  /**
   * Every case also checked against a second, unrelated observation name - the predicate must match
   * purely on the carrier request's raw URI, not the shared "http.server.requests" name every HTTP
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
    HttpServletRequest healthCheckRequest = requestWithUri("/actuator/health");
    ServerRequestObservationContext healthCheck = mock(ServerRequestObservationContext.class);
    when(healthCheck.getCarrier()).thenReturn(healthCheckRequest);

    HttpServletRequest otherRequest = requestWithUri("/config-server/order-service/default");
    ServerRequestObservationContext otherEndpoint = mock(ServerRequestObservationContext.class);
    when(otherEndpoint.getCarrier()).thenReturn(otherRequest);

    return Stream.of(
        Arguments.of(healthCheck, false),
        Arguments.of(otherEndpoint, true),
        Arguments.of(new Observation.Context(), true));
  }

  private static HttpServletRequest requestWithUri(String uri) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(uri);
    return request;
  }
}
