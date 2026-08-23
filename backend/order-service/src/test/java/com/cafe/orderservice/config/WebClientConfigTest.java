package com.cafe.orderservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import brave.Tracing;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class WebClientConfigTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void loadBalancedWebClientBuilder_instrumentsRequestsWithTheGivenRegistry() {
    TestObservationRegistry registry = TestObservationRegistry.create();
    server.enqueue(new MockResponse().setBody("ok"));

    WebClient client =
        new WebClientConfig()
            .loadBalancedWebClientBuilder(registry)
            .baseUrl(server.url("/").toString())
            .build();

    String response =
        client.get().uri("/ping").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));

    assertAll(
        () -> assertThat(response).isEqualTo("ok"),
        () ->
            TestObservationRegistryAssert.assertThat(registry)
                .hasNumberOfObservationsEqualTo(1)
                .hasObservationWithNameEqualTo("http.client.requests"));
  }

  @Test
  void loadBalancedWebClientBuilder_propagatesTraceContextToTheCallee()
      throws InterruptedException {
    Tracing tracing = Tracing.newBuilder().build();
    try {
      BraveTracer braveTracer =
          new BraveTracer(
              tracing.tracer(),
              new BraveCurrentTraceContext(tracing.currentTraceContext()),
              new BraveBaggageManager());
      ObservationRegistry registry = ObservationRegistry.create();
      registry
          .observationConfig()
          .observationHandler(
              new PropagatingSenderTracingObservationHandler<>(
                  braveTracer, new BravePropagator(tracing)));
      server.enqueue(new MockResponse().setBody("ok"));

      WebClient client =
          new WebClientConfig()
              .loadBalancedWebClientBuilder(registry)
              .baseUrl(server.url("/").toString())
              .build();

      client.get().uri("/ping").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));

      RecordedRequest recordedRequest = server.takeRequest(1, TimeUnit.SECONDS);
      assertAll(
          () -> assertThat(recordedRequest).isNotNull(),
          () -> assertThat(recordedRequest.getHeader("X-B3-TraceId")).isNotBlank(),
          () -> assertThat(recordedRequest.getHeader("X-B3-SpanId")).isNotBlank());
    } finally {
      tracing.close();
    }
  }
}
