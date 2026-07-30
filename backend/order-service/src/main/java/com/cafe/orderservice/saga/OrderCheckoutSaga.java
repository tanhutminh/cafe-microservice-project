package com.cafe.orderservice.saga;

import com.cafe.common.event.InventoryReserveStockCommand;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.common.event.OrderPaidEvent;
import com.cafe.orderservice.order.Order;
import com.cafe.orderservice.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Saga orchestrator for order checkout (plan section 4), living inside order-service since
 * it already owns the Order aggregate and its status lifecycle. Choreography was rejected
 * in favor of this single state machine because there is exactly one step that can fail and
 * needs compensation (inventory reservation) — report-service is a plain event subscriber,
 * not a saga participant.
 */
@Component
public class OrderCheckoutSaga {

    private static final Logger log = LoggerFactory.getLogger(OrderCheckoutSaga.class);

    public static final String RESERVE_STOCK_TOPIC = "inventory.reserve-stock.command";
    public static final String STOCK_RESERVATION_REPLY_TOPIC = "inventory.stock-reservation.reply";
    public static final String ORDER_PAID_TOPIC = "order.paid";

    private final OrderService orderService;
    private final OrderSagaStateService sagaStateService;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final SagaReconciliationProperties reconciliationProperties;

    public OrderCheckoutSaga(OrderService orderService, OrderSagaStateService sagaStateService,
                              KafkaTemplate<Object, Object> kafkaTemplate,
                              SagaReconciliationProperties reconciliationProperties) {
        this.orderService = orderService;
        this.sagaStateService = sagaStateService;
        this.kafkaTemplate = kafkaTemplate;
        this.reconciliationProperties = reconciliationProperties;
    }

    /** Step 1 (plan section 4): local commit — order to PENDING_CONFIRMATION + saga row STARTED, atomically. */
    @Transactional
    public Order startCheckout(Long orderId, String paymentMethod) {
        Order order = orderService.checkout(orderId, paymentMethod);
        sagaStateService.start(orderId);
        return order;
    }

    /** Step 2: publish the reservation command after the step-1 transaction has committed. */
    public void publishReservationCommand(Order order) {
        String correlationId = sagaStateService.getCurrentCorrelationId(order.getId());
        List<OrderLineItem> lines = order.getItems().stream()
                .map(item -> new OrderLineItem(item.getMenuItemId(), item.getQuantity()))
                .toList();

        Message<InventoryReserveStockCommand> message = MessageBuilder
                .withPayload(new InventoryReserveStockCommand(order.getId(), lines))
                .setHeader(KafkaHeaders.TOPIC, RESERVE_STOCK_TOPIC)
                .setHeader(KafkaHeaders.KEY, String.valueOf(order.getId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
                .build();
        kafkaTemplate.send(message);

        sagaStateService.markStockReservationRequested(order.getId());
        log.info("Checkout saga: requested stock reservation for order {} (correlation {})", order.getId(), correlationId);
    }

    /** Step 4: apply inventory-service's reply — complete (PAID) or compensate (back to OPEN). */
    @KafkaListener(topics = STOCK_RESERVATION_REPLY_TOPIC)
    @Transactional
    public void onStockReservationReply(InventoryStockReservationReply reply,
                                         @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
        if (sagaStateService.shouldIgnoreReply(reply.orderId(), correlationId)) {
            log.info("Checkout saga: ignoring stale/settled stock-reservation reply for order {}", reply.orderId());
            return;
        }

        if (reply.success()) {
            Order order = orderService.markPaid(reply.orderId());
            sagaStateService.markCompleted(reply.orderId());
            publishOrderPaid(order, correlationId);
            log.info("Checkout saga: order {} PAID", reply.orderId());
        } else {
            orderService.compensateToOpen(reply.orderId(), reply.reason());
            sagaStateService.markCompensated(reply.orderId());
            log.info("Checkout saga: order {} compensated back to OPEN — {}", reply.orderId(), reply.reason());
        }
    }

    /**
     * Called by OrderSagaReconciliationJob for a saga the sweep found stuck at
     * STOCK_RESERVATION_REQUESTED past app.saga-reconciliation.stuck-threshold - the Reconciliation
     * pattern's response to a lost/undelivered inventory.stock-reservation.reply that
     * shouldIgnoreReply's redelivery/staleness guards were never designed to detect (there's no
     * message to receive at all).
     *
     * Re-checks the step fresh inside this transaction first: the sweep's query and this call
     * aren't atomic, so a real reply may have arrived and already settled the saga in between -
     * in that case this is a no-op. Retrying re-publishes with the *same* correlationId (not a
     * new one): same Kafka key (orderId) as the original means Kafka guarantees both land in the
     * same partition and get processed in order by a single consumer thread, so
     * inventory-service's idempotent receiver (ProcessedSagaStep, PK'd on correlationId) simply
     * replays its stored outcome for the redelivery instead of double-deducting stock - and even
     * in the pathological case of genuinely concurrent processing (a consumer rebalance mid-flight),
     * the primary key constraint rejects the second INSERT and rolls back that whole transaction,
     * deduction included.
     */
    @Transactional
    public void retryOrCompensate(Long orderId) {
        if (sagaStateService.getCurrentStep(orderId) != SagaStep.STOCK_RESERVATION_REQUESTED) {
            return;
        }

        if (sagaStateService.getRetryCount(orderId) < reconciliationProperties.maxRetries()) {
            sagaStateService.incrementRetryCount(orderId);
            Order order = orderService.getOrder(orderId);
            publishReservationCommand(order);
            log.info("Checkout saga: reconciliation re-published stock reservation for order {} (attempt {})",
                    orderId, sagaStateService.getRetryCount(orderId));
        } else {
            String reason = "Inventory reservation timed out after " + reconciliationProperties.maxRetries() + " retries";
            orderService.compensateToOpen(orderId, reason);
            sagaStateService.markCompensated(orderId);
            log.info("Checkout saga: order {} compensated back to OPEN by reconciliation — {}", orderId, reason);
        }
    }

    private void publishOrderPaid(Order order, String correlationId) {
        List<OrderLineItem> lines = order.getItems().stream()
                .map(item -> new OrderLineItem(item.getMenuItemId(), item.getQuantity()))
                .toList();
        BigDecimal grandTotal = order.getItems().stream()
                .map(item -> item.getPriceSnapshot().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        OrderPaidEvent event = new OrderPaidEvent(
                order.getId(), order.getTable().getId(), order.getClosedAt(), lines, grandTotal, order.getPaymentMethod());

        Message<OrderPaidEvent> message = MessageBuilder
                .withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, ORDER_PAID_TOPIC)
                .setHeader(KafkaHeaders.KEY, String.valueOf(order.getId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
                .build();
        kafkaTemplate.send(message);
    }
}
