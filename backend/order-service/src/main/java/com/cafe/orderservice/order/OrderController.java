package com.cafe.orderservice.order;

import com.cafe.common.event.OrderLineItem;
import com.cafe.orderservice.order.dto.AddOrderItemRequest;
import com.cafe.orderservice.order.dto.CheckoutRequest;
import com.cafe.orderservice.order.dto.CreateOrderRequest;
import com.cafe.orderservice.order.dto.MoveTableRequest;
import com.cafe.orderservice.order.dto.OrderResponse;
import com.cafe.orderservice.order.dto.PayRequest;
import com.cafe.orderservice.saga.OrderSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "The POS order lifecycle and its order saga")
public class OrderController {

  private final OrderService orderService;
  private final OrderSaga orderSaga;

  public OrderController(OrderService orderService, OrderSaga orderSaga) {
    this.orderService = orderService;
    this.orderSaga = orderSaga;
  }

  @GetMapping("/{id}")
  @Operation(
      summary =
          "Get an order by id - also how the POS polls checkout progress until it settles to PAID or back to OPEN")
  public OrderResponse getOrder(
      @Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id) {
    return OrderResponse.from(orderService.getOrder(id));
  }

  @GetMapping(params = "tableId")
  @Operation(
      summary =
          "Get the current order for a table (the one occupying it right now, excluding CANCELLED)")
  public OrderResponse getCurrentOrderForTable(
      @Parameter(description = "The table's id", example = "3") @RequestParam @Positive
          Long tableId) {
    return OrderResponse.from(orderService.getCurrentOrderForTable(tableId));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary =
          "Open a new order on an already-OCCUPIED table with its full item list, and "
              + "immediately start checkout",
      description =
          "The table must already be OCCUPIED (see POST /api/tables/{id}/occupy) - the POS screen "
              + "builds the item list locally as a draft cart and only calls this once, when staff hit "
              + "Confirm, instead of persisting each item pick separately.")
  public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest request) {
    return OrderResponse.from(
        orderSaga.createAndCheckout(request.tableId(), toLineItems(request.items())));
  }

  @PostMapping("/{id}/cancel")
  @Operation(
      summary = "Cancel an order (frees the table)",
      description =
          "Blocked while either saga leg is in flight (PENDING_CONFIRMATION / PAYMENT_PENDING). "
              + "Cancelling a CONFIRMED order releases its stock hold back to inventory-service.")
  public OrderResponse cancel(
      @Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id) {
    return OrderResponse.from(orderSaga.cancelOrder(id));
  }

  @PostMapping("/{id}/move")
  @Operation(summary = "Move an order to a different AVAILABLE table (blocked mid-checkout)")
  public OrderResponse moveTable(
      @Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id,
      @Valid @RequestBody MoveTableRequest request) {
    return OrderResponse.from(orderService.moveTable(id, request.tableId()));
  }

  @PostMapping("/{id}/checkout")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(
      summary =
          "Verify: replace the order's item list with the given one and start the verify saga, "
              + "soft-reserving stock (returns immediately - not paid yet)",
      description =
          "Only legal from OPEN today - re-submitting a failed order's draft cart (fix an item and "
              + "try again). Moves the order to PENDING_CONFIRMATION and asks inventory-service to hold "
              + "stock over Kafka (Ingredient.reservedQuantity, not yet deducted from currentStock). "
              + "The response you get back here still shows PENDING_CONFIRMATION: this call only "
              + "starts the saga and returns 202 as soon as that first local step commits. Poll "
              + "GET /api/orders/{id} every ~1s until status settles to CONFIRMED (stock held, ready "
              + "for payment) or back to OPEN with failureReason set (e.g. an ingredient ran out - "
              + "fix the order and try again).")
  public OrderResponse checkout(
      @Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id,
      @Valid @RequestBody CheckoutRequest request) {
    return OrderResponse.from(orderSaga.startCheckout(id, toLineItems(request.items())));
  }

  private static List<OrderLineItem> toLineItems(List<AddOrderItemRequest> items) {
    return items.stream()
        .map(item -> new OrderLineItem(item.menuItemId(), item.quantity()))
        .toList();
  }

  @PostMapping("/{id}/pay")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(
      summary =
          "Pay: start the payment saga, committing the stock hold (returns immediately - not paid yet)",
      description =
          "Only valid once CONFIRMED. Moves the order to PAYMENT_PENDING and asks "
              + "inventory-service to turn the earlier hold into a real currentStock deduction over "
              + "Kafka. Poll GET /api/orders/{id} every ~1s until status settles to PAID (order.paid "
              + "published) or back to CONFIRMED with failureReason set - the stock hold is untouched, "
              + "just retry payment.")
  public OrderResponse pay(
      @Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id,
      @Valid @RequestBody PayRequest request) {
    return OrderResponse.from(orderSaga.startPayment(id, request.paymentMethod()));
  }
}
