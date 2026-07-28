package com.cafe.orderservice.order;

import com.cafe.orderservice.order.dto.AddOrderItemRequest;
import com.cafe.orderservice.order.dto.CheckoutRequest;
import com.cafe.orderservice.order.dto.CreateOrderRequest;
import com.cafe.orderservice.order.dto.MoveTableRequest;
import com.cafe.orderservice.order.dto.OrderResponse;
import com.cafe.orderservice.saga.OrderCheckoutSaga;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderCheckoutSaga orderCheckoutSaga;

    public OrderController(OrderService orderService, OrderCheckoutSaga orderCheckoutSaga) {
        this.orderService = orderService;
        this.orderCheckoutSaga = orderCheckoutSaga;
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    @GetMapping(params = "tableId")
    public OrderResponse getCurrentOrderForTable(@RequestParam Long tableId) {
        return OrderResponse.from(orderService.getCurrentOrderForTable(tableId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return OrderResponse.from(orderService.createOrder(request.tableId()));
    }

    @PostMapping("/{id}/items")
    public OrderResponse addItem(@PathVariable Long id, @Valid @RequestBody AddOrderItemRequest request) {
        return OrderResponse.from(orderService.addItem(id, request.menuItemId(), request.quantity()));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public OrderResponse removeItem(@PathVariable Long id, @PathVariable Long itemId) {
        return OrderResponse.from(orderService.removeItem(id, itemId));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        return OrderResponse.from(orderService.cancel(id));
    }

    @PostMapping("/{id}/move")
    public OrderResponse moveTable(@PathVariable Long id, @Valid @RequestBody MoveTableRequest request) {
        return OrderResponse.from(orderService.moveTable(id, request.tableId()));
    }

    /**
     * Kicks off the checkout saga (plan section 4) and returns immediately — the order is
     * PENDING_CONFIRMATION here, not yet PAID. Clients poll GET /api/orders/{id} until it
     * settles to PAID or back to OPEN (with failureReason set).
     */
    @PostMapping("/{id}/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrderResponse checkout(@PathVariable Long id, @Valid @RequestBody CheckoutRequest request) {
        Order order = orderCheckoutSaga.startCheckout(id, request.paymentMethod());
        orderCheckoutSaga.publishReservationCommand(order);
        return OrderResponse.from(order);
    }
}
