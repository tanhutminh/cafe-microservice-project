package com.cafe.orderservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Exercises findMenuItemsAsMap against a real HTTP server (see WebClientConfigTest for the same
 * MockWebServer pattern) rather than mocking WebClient - the query-string shape of a batch request
 * and the map-building/missing-id logic are exactly the kind of thing that looks right on paper but
 * silently misbehaves at the wire level.
 */
class MenuServiceClientTest {

  private MockWebServer server;
  private MenuServiceClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    WebClient.Builder builder =
        WebClient.builder().observationRegistry(ObservationRegistry.create());
    client = new MenuServiceClient(builder, server.url("/").toString());
  }

  @AfterEach
  void tearDown() throws IOException {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void findMenuItemsAsMap_returnsDetailsKeyedByMenuItemId() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                [
                  {"id":1,"name":"Latte","price":50000,"available":true},
                  {"id":2,"name":"Mocha","price":55000,"available":false}
                ]
                """));

    Map<Long, MenuItemDetails> result = client.findMenuItemsAsMap(List.of(1L, 2L));

    assertAll(
        () -> assertThat(result).hasSize(2),
        () ->
            assertThat(result.get(1L))
                .isEqualTo(new MenuItemDetails(1L, "Latte", BigDecimal.valueOf(50000), true)),
        () ->
            assertThat(result.get(2L))
                .isEqualTo(new MenuItemDetails(2L, "Mocha", BigDecimal.valueOf(55000), false)));
  }

  @Test
  void findMenuItemsAsMap_sendsEveryIdAsARepeatedQueryParam() throws InterruptedException {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                [
                  {"id":1,"name":"Latte","price":50000,"available":true},
                  {"id":2,"name":"Mocha","price":55000,"available":true},
                  {"id":3,"name":"Espresso","price":40000,"available":true}
                ]
                """));

    client.findMenuItemsAsMap(List.of(1L, 2L, 3L));

    RecordedRequest recordedRequest = server.takeRequest();
    assertAll(
        () -> assertThat(recordedRequest.getPath()).startsWith("/api/menu-items/batch?"),
        () -> assertThat(recordedRequest.getPath()).contains("ids=1"),
        () -> assertThat(recordedRequest.getPath()).contains("ids=2"),
        () -> assertThat(recordedRequest.getPath()).contains("ids=3"));
  }

  @Test
  void findMenuItemsAsMap_idMissingFromResponse_throwsResourceNotFoundException() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("[{\"id\":1,\"name\":\"Latte\",\"price\":50000,\"available\":true}]"));

    assertThatThrownBy(() -> client.findMenuItemsAsMap(List.of(1L, 2L)))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
