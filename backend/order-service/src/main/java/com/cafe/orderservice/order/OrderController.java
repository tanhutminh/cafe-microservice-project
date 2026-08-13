package com.cafe.orderservice.order;

import com.cafe.orderservice.order.dto.AddOrderItemRequest;
import com.cafe.orderservice.order.dto.CreateOrderRequest;
import com.cafe.orderservice.order.dto.MoveTableRequest;
import com.cafe.orderservice.order.dto.OrderResponse;
import com.cafe.orderservice.order.dto.PayRequest;
import com.cafe.orderservice.order.dto.UpdateOrderItemQuantityRequest;
import com.cafe.orderservice.saga.OrderCheckoutSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "The POS order lifecycle and its checkout saga")
public class OrderController {

    private final OrderService orderService;
    private final OrderCheckoutSaga orderCheckoutSaga;

    public OrderController(OrderService orderService, OrderCheckoutSaga orderCheckoutSaga) {
        this.orderService = orderService;
        this.orderCheckoutSaga = orderCheckoutSaga;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order by id - also how the POS polls checkout progress until it settles to PAID or back to OPEN")
    public OrderResponse getOrder(@Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    @GetMapping(params = "tableId")
    @Operation(summary = "Get the current order for a table (the one occupying it right now, excluding CANCELLED)")
    public OrderResponse getCurrentOrderForTable(@Parameter(description = "The table's id", example = "3") @RequestParam @Positive Long tableId) {
        return OrderResponse.from(orderService.getCurrentOrderForTable(tableId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a new order on an AVAILABLE table")
    public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return OrderResponse.from(orderService.createOrder(request.tableId()));
    }

    @PostMapping("/{id}/items")
    @Operation(summary = "Add a line item - looks up the menu item's name/price via menu-service and snapshots it onto the order")
    public OrderResponse addItem(@Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id, @Valid @RequestBody AddOrderItemRequest request) {
        return OrderResponse.from(orderService.addItem(id, request.menuItemId(), request.quantity()));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @Operation(summary = "Remove a line item from an OPEN order")
    public OrderResponse removeItem(@Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id,
                                     @Parameter(description = "The order line item's id (not the menu item's id)", example = "42") @PathVariable @Positive Long itemId) {
        return OrderResponse.from(orderService.removeItem(id, itemId));
    }

    @PatchMapping("/{id}/items/{itemId}")
    @Operation(summary = "Set a line item's quantity on an OPEN order (min 1 - use DELETE to remove it entirely)")
    public OrderResponse updateItemQuantity(@Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id,
                                             @Parameter(description = "The order line item's id (not the menu item's id)", example = "42") @PathVariable @Positive Long itemId,
                                             @Valid @RequestBody UpdateOrderItemQuantityRequest request) {
        return OrderResponse.from(orderService.updateItemQuantity(id, itemId, request.quantity()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Cancel an order (frees the table)",
            description = "Blocked while either saga leg is in flight (PENDING_CONFIRMATION / PAYMENT_PENDING). "
                    + "Cancelling a CONFIRMED order releases its stock hold back to inventory-service."
    )
    public OrderResponse cancel(@Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id) {
        return OrderResponse.from(orderCheckoutSaga.cancelOrder(id));
    }

    @PostMapping("/{id}/move")
    @Operation(summary = "Move an order to a different AVAILABLE table (blocked mid-checkout)")
    public OrderResponse moveTable(@Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id, @Valid @RequestBody MoveTableRequest request) {
        return OrderResponse.from(orderService.moveTable(id, request.tableId()));
    }

    @PostMapping("/{id}/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Verify: start the verify saga, soft-reserving stock (returns immediately - not paid yet)",
            description = "Moves the order to PENDING_CONFIRMATION and asks inventory-service to hold "
                    + "stock over Kafka (Ingredient.reservedQuantity, not yet deducted from currentStock). "
                    + "The response you get back here still shows PENDING_CONFIRMATION: this call only "
                    + "starts the saga and returns 202 as soon as that first local step commits. Poll "
                    + "GET /api/orders/{id} every ~1s until status settles to CONFIRMED (stock held, ready "
                    + "for payment) or back to OPEN with failureReason set (e.g. an ingredient ran out - "
                    + "fix the order and try again)."
    )
    public OrderResponse checkout(@Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id) {
        return OrderResponse.from(orderCheckoutSaga.startCheckout(id));
    }

    @PostMapping("/{id}/pay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Pay: start the payment saga, committing the stock hold (returns immediately - not paid yet)",
            description = "Only valid once CONFIRMED. Moves the order to PAYMENT_PENDING and asks "
                    + "inventory-service to turn the earlier hold into a real currentStock deduction over "
                    + "Kafka. Poll GET /api/orders/{id} every ~1s until status settles to PAID (order.paid "
                    + "published) or back to CONFIRMED with failureReason set - the stock hold is untouched, "
                    + "just retry payment."
    )
    public OrderResponse pay(@Parameter(description = "The order's id", example = "101") @PathVariable @Positive Long id, @Valid @RequestBody PayRequest request) {
        return OrderResponse.from(orderCheckoutSaga.startPayment(id, request.paymentMethod()));
    }
}
