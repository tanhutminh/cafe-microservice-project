package com.cafe.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cafe.common.event.OrderLineItem;
import com.cafe.orderservice.config.SecurityConfig;
import com.cafe.orderservice.saga.OrderSaga;
import com.cafe.orderservice.table.DiningTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * @WebMvcTest slice test - the template for this codebase's first MockMvc-based controller coverage
 * (see the other 6 REST controllers for the same pattern applied). Unlike the plain unit tests
 * elsewhere in this codebase, this actually drives requests through Spring MVC's real
 * argument-resolution/validation pipeline, so it verifies what a hand-rolled
 * GlobalExceptionHandlerTest can only simulate: that @Positive/@Valid annotations on the controller
 * itself actually get enforced. @Import(SecurityConfig.class) pulls in the real SecurityFilterChain
 * + HeaderAuthenticationFilter (no @WithMockUser - this project's auth is custom
 * X-Username/X-User-Role headers, not Spring Security's own user store).
 */
@WebMvcTest(controllers = OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private OrderService orderService;
  @MockitoBean private OrderSaga orderSaga;

  private Order sampleOrder() {
    DiningTable table = DiningTable.builder().id(3L).tableNumber("Bàn 3").capacity(4).build();
    return Order.builder()
        .id(101L)
        .table(table)
        .status(OrderStatus.OPEN)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  /**
   * One case per distinct constraint reachable through OrderController - not just a representative
   * subset - covering every request-body DTO it validates
   * (CreateOrderRequest/AddOrderItemRequest/CheckoutRequest/MoveTableRequest/PayRequest).
   * AddOrderItemRequest's own constraints are exercised once via CreateOrderRequest's nested
   * `@Valid List&lt;AddOrderItemRequest&gt; items` cascade (Spring reports nested violations as
   * "items[0].&lt;field&gt;") - CheckoutRequest reuses the identical DTO/cascade, so it only needs
   * its own `@NotEmpty items` case, which additionally exercises a materially different code path
   * (checkout mixes a `@Positive` path id with a `@Valid` body, so it routes through
   * HandlerMethodValidationException/ParameterErrors instead of createOrder's plain
   * MethodArgumentNotValidException - see the pay_invalidPaymentMethodOnValidOrder case below for
   * the same distinction).
   */
  private static Stream<Arguments> validationFailures() {
    return Stream.of(
        Arguments.of(
            "getOrder_negativeId",
            (Supplier<MockHttpServletRequestBuilder>) () -> get("/api/orders/-1"),
            "id",
            "must be greater than 0"),
        Arguments.of(
            "createOrder_missingTableId",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuItemId\":12,\"quantity\":2}]}"),
            "tableId",
            "must not be null"),
        Arguments.of(
            "createOrder_negativeTableId",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":-1,\"items\":[{\"menuItemId\":12,\"quantity\":2}]}"),
            "tableId",
            "must be greater than 0"),
        Arguments.of(
            "createOrder_emptyItems",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":3,\"items\":[]}"),
            "items",
            "must not be empty"),
        Arguments.of(
            "createOrder_missingMenuItemIdInItems",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":3,\"items\":[{\"quantity\":2}]}"),
            "items[0].menuItemId",
            "must not be null"),
        Arguments.of(
            "createOrder_negativeMenuItemIdInItems",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":3,\"items\":[{\"menuItemId\":-1,\"quantity\":2}]}"),
            "items[0].menuItemId",
            "must be greater than 0"),
        Arguments.of(
            "createOrder_zeroQuantityInItems",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":3,\"items\":[{\"menuItemId\":12,\"quantity\":0}]}"),
            "items[0].quantity",
            "must be greater than or equal to 1"),
        Arguments.of(
            "checkout_emptyItems",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"),
            "items",
            "must not be empty"),
        Arguments.of(
            "createOrder_tooManyItems",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":3,\"items\":" + tooManyItemsJson() + "}"),
            "items",
            "size must be between 0 and 50"),
        Arguments.of(
            "checkout_tooManyItems",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":" + tooManyItemsJson() + "}"),
            "items",
            "size must be between 0 and 50"),
        Arguments.of(
            "moveTable_missingTableId",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
            "tableId",
            "must not be null"),
        Arguments.of(
            "moveTable_negativeTableId",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":-1}"),
            "tableId",
            "must be greater than 0"),
        Arguments.of(
            "pay_missingPaymentMethod",
            // A blank string (as opposed to an absent field) would trigger both
            // @NotBlank and @Pattern simultaneously - Bean Validation runs every
            // constraint independently and doesn't guarantee violation order, so
            // that input can't isolate @NotBlank's own message deterministically. A
            // missing/null value only trips @NotBlank: @Pattern (like most
            // constraints besides @NotNull/@NotBlank/@NotEmpty) treats null as valid.
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
            "paymentMethod",
            "must not be blank"),
        Arguments.of(
            "pay_invalidPaymentMethodOnValidOrder",
            // Regression test for the real bug this template caught during manual
            // verification: pay() has BOTH a @Positive id AND a @Valid PayRequest
            // body, so a body violation here arrives as a ParameterErrors
            // HandlerMethodValidationException result - the handler must report
            // "paymentMethod", not the parameter's own name ("request").
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"BITCOIN\"}"),
            "paymentMethod",
            "must match \"CASH|CARD\""));
  }

  private static String tooManyItemsJson() {
    return IntStream.rangeClosed(1, 51)
        .mapToObj(i -> "{\"menuItemId\":" + i + ",\"quantity\":1}")
        .collect(Collectors.joining(",", "[", "]"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("validationFailures")
  void invalidRequest_returns400WithFieldViolation(
      String caseName,
      Supplier<MockHttpServletRequestBuilder> requestSupplier,
      String expectedField,
      String expectedMessage)
      throws Exception {
    mockMvc
        .perform(requestSupplier.get().header("X-Username", "admin").header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.validationErrors[0].field").value(expectedField))
        .andExpect(jsonPath("$.validationErrors[0].message").value(expectedMessage));
  }

  private static Stream<Arguments> nonConvertibleIdSegments() {
    return Stream.of(
        Arguments.of("nonNumeric", "abc"),
        // A common client-side bug: JS stringifies undefined/null into the URL itself
        // (e.g. `/api/orders/${maybeUndefinedId}`) instead of never sending the request -
        // "null" the string, not a missing/absent path segment.
        Arguments.of("literalNullString", "null"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonConvertibleIdSegments")
  void getOrder_nonConvertibleId_returns400NotUnexpected500(String caseName, String idSegment)
      throws Exception {
    // Regression test: a path segment that fails Long conversion never reaches @Positive -
    // it fails during Spring's own argument resolution instead, exercising
    // GlobalExceptionHandler.handleTypeMismatch end to end through the real
    // @RestControllerAdvice wiring. Without it this used to fall through to the catch-all
    // handler and misreport a client input error as a 500.
    mockMvc
        .perform(
            get("/api/orders/" + idSegment)
                .header("X-Username", "admin")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Invalid value for 'id': " + idSegment));
  }

  @Test
  void getOrder_missingAuthHeaders_returns401() throws Exception {
    mockMvc.perform(get("/api/orders/101")).andExpect(status().isUnauthorized());
  }

  @Test
  void cancel_negativeId_returns400BeforeReachingSaga() throws Exception {
    mockMvc
        .perform(
            post("/api/orders/-5/cancel")
                .header("X-Username", "admin")
                .header("X-User-Role", "CASHIER"))
        .andExpect(status().isBadRequest());

    verify(orderSaga, never()).cancelOrder(anyLong());
  }

  private static Stream<Arguments> happyPathRequests() {
    return Stream.of(
        Arguments.of(
            "getOrder",
            (Supplier<MockHttpServletRequestBuilder>) () -> get("/api/orders/101"),
            HttpStatus.OK),
        Arguments.of(
            "getCurrentOrderForTable",
            (Supplier<MockHttpServletRequestBuilder>)
                () -> get("/api/orders").param("tableId", "3"),
            HttpStatus.OK),
        Arguments.of(
            "createOrder",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":3,\"items\":[{\"menuItemId\":12,\"quantity\":2}]}"),
            HttpStatus.CREATED),
        Arguments.of(
            "moveTable",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":5}"),
            HttpStatus.OK),
        Arguments.of(
            "checkout",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuItemId\":12,\"quantity\":2}]}"),
            HttpStatus.ACCEPTED),
        Arguments.of(
            "cancel",
            (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/orders/101/cancel"),
            HttpStatus.OK),
        Arguments.of(
            "pay",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/orders/101/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"CASH\"}"),
            HttpStatus.ACCEPTED));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("happyPathRequests")
  void validRequest_returnsExpectedStatusWithOrder(
      String caseName,
      Supplier<MockHttpServletRequestBuilder> requestSupplier,
      HttpStatus expectedStatus)
      throws Exception {
    switch (caseName) {
      case "getOrder" -> when(orderService.getOrder(101L)).thenReturn(sampleOrder());
      case "getCurrentOrderForTable" ->
          when(orderService.getCurrentOrderForTable(3L)).thenReturn(sampleOrder());
      case "createOrder" ->
          when(orderSaga.createAndCheckout(eq(3L), eq(List.of(new OrderLineItem(12L, 2)))))
              .thenReturn(sampleOrder());
      case "moveTable" -> when(orderService.moveTable(101L, 5L)).thenReturn(sampleOrder());
      case "checkout" ->
          when(orderSaga.startCheckout(eq(101L), eq(List.of(new OrderLineItem(12L, 2)))))
              .thenReturn(sampleOrder());
      case "cancel" -> when(orderSaga.cancelOrder(101L)).thenReturn(sampleOrder());
      case "pay" -> when(orderSaga.startPayment(101L, "CASH")).thenReturn(sampleOrder());
    }

    MvcResult result =
        mockMvc
            .perform(
                requestSupplier.get().header("X-Username", "admin").header("X-User-Role", "ADMIN"))
            .andExpect(status().is(expectedStatus.value()))
            .andReturn();

    assertOrderResponse(objectMapper.readTree(result.getResponse().getContentAsString()));
  }

  private void assertOrderResponse(JsonNode body) {
    assertAll(
        () -> assertThat(body.get("id").asLong()).isEqualTo(101L),
        () -> assertThat(body.get("tableId").asLong()).isEqualTo(3L),
        () -> assertThat(body.get("tableNumber").asText()).isEqualTo("Bàn 3"),
        () -> assertThat(body.get("status").asText()).isEqualTo("OPEN"),
        () -> assertThat(body.get("paymentMethod").isNull()).isTrue(),
        () -> assertThat(body.get("failureReason").isNull()).isTrue(),
        () -> assertThat(body.get("items").size()).isEqualTo(0),
        () -> assertThat(body.get("grandTotal").asDouble()).isEqualTo(0.0),
        () -> assertThat(body.get("createdAt").isNull()).isFalse(),
        () -> assertThat(body.get("closedAt").isNull()).isTrue());
  }
}
