package com.cafe.orderservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import io.micrometer.observation.ObservationRegistry;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("MenuItem not found: 2");
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 499})
  void findMenuItemsAsMap_clientErrorStatus_throwsMenuServiceContractException(int status) {
    server.enqueue(new MockResponse().setResponseCode(status));

    Throwable thrown = catchThrowable(() -> client.findMenuItemsAsMap(List.of(1L)));

    assertAll(
        () -> assertThat(thrown).isInstanceOf(MenuServiceContractException.class),
        () ->
            assertThat(thrown)
                .hasMessage("menu-service rejected the request: " + HttpStatusCode.valueOf(status)),
        () ->
            assertThat(thrown.getCause())
                .isInstanceOfSatisfying(
                    WebClientResponseException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(status)),
        () -> assertThat(server.getRequestCount()).isEqualTo(1));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 503, 599})
  void findMenuItemsAsMap_serverErrorStatus_throwsWebClientResponseException(int status) {
    server.enqueue(new MockResponse().setResponseCode(status));

    Throwable thrown = catchThrowable(() -> client.findMenuItemsAsMap(List.of(1L)));

    assertAll(
        () ->
            assertThat(thrown)
                .isInstanceOfSatisfying(
                    WebClientResponseException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(status)),
        () -> assertThat(server.getRequestCount()).isEqualTo(1));
  }

  @Test
  @Timeout(10)
  void findMenuItemsAsMap_noResponse_throwsWebClientRequestExceptionAfterResponseTimeout() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

    Throwable thrown = catchThrowable(() -> client.findMenuItemsAsMap(List.of(1L)));

    assertAll(
        () -> assertThat(thrown).isInstanceOf(WebClientRequestException.class),
        () -> assertThat(thrown).hasCauseInstanceOf(ReadTimeoutException.class),
        () -> assertThat(server.getRequestCount()).isEqualTo(1));
  }

  @Test
  void autowiredConstructor_usesBaseUrlFromProperties() {
    WebClient.Builder builder = mock(WebClient.Builder.class, Answers.RETURNS_SELF);
    when(builder.build()).thenReturn(WebClient.builder().build());

    new MenuServiceClient(builder, new MenuServiceClientProperties("http://some-other-host:1234"));

    verify(builder).baseUrl("http://some-other-host:1234");
  }
}
