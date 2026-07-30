package com.cafe.orderservice.order;

import com.cafe.orderservice.order.dto.AddOrderItemRequest;
import com.cafe.orderservice.order.dto.CheckoutRequest;
import com.cafe.orderservice.order.dto.CreateOrderRequest;
import com.cafe.orderservice.order.dto.MoveTableRequest;
import com.cafe.orderservice.order.dto.OrderResponse;
import com.cafe.orderservice.saga.OrderCheckoutSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
    public OrderResponse getOrder(@Parameter(description = "The order's id", example = "101") @PathVariable Long id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    @GetMapping(params = "tableId")
    @Operation(summary = "Get the current order for a table (the one occupying it right now, excluding CANCELLED)")
    public OrderResponse getCurrentOrderForTable(@Parameter(description = "The table's id", example = "3") @RequestParam Long tableId) {
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
    public OrderResponse addItem(@Parameter(description = "The order's id", example = "101") @PathVariable Long id, @Valid @RequestBody AddOrderItemRequest request) {
        return OrderResponse.from(orderService.addItem(id, request.menuItemId(), request.quantity()));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @Operation(summary = "Remove a line item from an OPEN order")
    public OrderResponse removeItem(@Parameter(description = "The order's id", example = "101") @PathVariable Long id,
                                     @Parameter(description = "The order line item's id (not the menu item's id)", example = "42") @PathVariable Long itemId) {
        return OrderResponse.from(orderService.removeItem(id, itemId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order (frees the table)")
    public OrderResponse cancel(@Parameter(description = "The order's id", example = "101") @PathVariable Long id) {
        return OrderResponse.from(orderService.cancel(id));
    }

    @PostMapping("/{id}/move")
    @Operation(summary = "Move an order to a different AVAILABLE table (blocked mid-checkout)")
    public OrderResponse moveTable(@Parameter(description = "The order's id", example = "101") @PathVariable Long id, @Valid @RequestBody MoveTableRequest request) {
        return OrderResponse.from(orderService.moveTable(id, request.tableId()));
    }

    @PostMapping("/{id}/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Start the checkout saga (returns immediately - does NOT mean the order is paid yet)",
            description = "Moves the order to PENDING_CONFIRMATION and asks inventory-service to reserve "
                    + "stock over Kafka. The response you get back here still shows PENDING_CONFIRMATION, "
                    + "not PAID: this call only starts the saga and returns 202 as soon as that first local "
                    + "step commits. Poll GET /api/orders/{id} every ~1s until status settles to PAID "
                    + "(stock reserved, done) or back to OPEN with failureReason set (e.g. an ingredient "
                    + "ran out - fix the order and try checkout again)."
    )
    public OrderResponse checkout(@Parameter(description = "The order's id", example = "101") @PathVariable Long id, @Valid @RequestBody CheckoutRequest request) {
        Order order = orderCheckoutSaga.startCheckout(id, request.paymentMethod());
        orderCheckoutSaga.publishReservationCommand(order);
        return OrderResponse.from(order);
    }
}
