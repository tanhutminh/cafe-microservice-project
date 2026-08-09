package com.cafe.orderservice.saga;

import com.cafe.common.event.InventoryCommitStockCommand;
import com.cafe.common.event.InventoryReleaseStockCommand;
import com.cafe.common.event.InventoryReserveStockCommand;
import com.cafe.common.event.InventoryStockCommitReply;
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
import java.util.UUID;

/**
 * Saga orchestrator for order checkout, living inside order-service since it already owns the
 * Order aggregate and its status lifecycle. Runs as a single orchestrated state machine, not
 * choreography, since there is exactly one step that can fail and needs compensation per leg
 * — report-service is a plain event subscriber, not a saga participant.
 *
 * Two legs, both real Kafka round trips: Verify (reserve stock as a soft hold, OPEN ->
 * PENDING_CONFIRMATION -> CONFIRMED or back to OPEN) and Payment (commit the hold into a real
 * deduction, CONFIRMED -> PAYMENT_PENDING -> PAID or back to CONFIRMED). Cancelling a CONFIRMED
 * order releases the hold via a third, fire-and-forget command with no reply leg.
 */
@Component
public class OrderCheckoutSaga {

    private static final Logger log = LoggerFactory.getLogger(OrderCheckoutSaga.class);

    public static final String RESERVE_STOCK_TOPIC = "inventory.reserve-stock.command";
    public static final String STOCK_RESERVATION_REPLY_TOPIC = "inventory.stock-reservation.reply";
    public static final String COMMIT_STOCK_TOPIC = "inventory.commit-stock.command";
    public static final String STOCK_COMMIT_REPLY_TOPIC = "inventory.stock-commit.reply";
    public static final String RELEASE_STOCK_TOPIC = "inventory.release-stock.command";
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

    // ---- Verify leg ----

    /** Verify step 1: local commit — order to PENDING_CONFIRMATION + saga row STARTED, atomically. */
    @Transactional
    public Order startCheckout(Long orderId) {
        Order order = orderService.checkout(orderId);
        sagaStateService.start(orderId);
        return order;
    }

    /** Verify step 2: publish the reservation command after the step-1 transaction has committed. */
    public void publishReservationCommand(Order order) {
        String correlationId = sagaStateService.getCurrentCorrelationId(order.getId());
        Message<InventoryReserveStockCommand> message = MessageBuilder
                .withPayload(new InventoryReserveStockCommand(order.getId(), toLineItems(order)))
                .setHeader(KafkaHeaders.TOPIC, RESERVE_STOCK_TOPIC)
                .setHeader(KafkaHeaders.KEY, String.valueOf(order.getId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
                .build();
        kafkaTemplate.send(message);

        sagaStateService.markStockReservationRequested(order.getId());
        log.info("Checkout saga: requested stock reservation for order {} (correlation {})", order.getId(), correlationId);
    }

    /** Verify step 3: apply inventory-service's reply — CONFIRMED (stock held) or compensate (back to OPEN). */
    @KafkaListener(topics = STOCK_RESERVATION_REPLY_TOPIC)
    @Transactional
    public void onStockReservationReply(InventoryStockReservationReply reply,
                                         @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
        if (sagaStateService.shouldIgnoreReply(reply.orderId(), correlationId)) {
            log.info("Checkout saga: ignoring stale/settled stock-reservation reply for order {}", reply.orderId());
            return;
        }

        if (reply.success()) {
            orderService.markConfirmed(reply.orderId());
            sagaStateService.markConfirmed(reply.orderId());
            log.info("Checkout saga: order {} CONFIRMED (stock held)", reply.orderId());
        } else {
            orderService.compensateToOpen(reply.orderId(), reply.reason());
            sagaStateService.markCompensated(reply.orderId());
            log.info("Checkout saga: order {} compensated back to OPEN — {}", reply.orderId(), reply.reason());
        }
    }

    // ---- Payment leg ----

    /** Payment step 1: local commit — order to PAYMENT_PENDING, and a fresh saga attempt on the same row. */
    @Transactional
    public Order startPayment(Long orderId, String paymentMethod) {
        Order order = orderService.startPayment(orderId, paymentMethod);
        sagaStateService.startPaymentAttempt(orderId);
        return order;
    }

    /** Payment step 2: publish the commit command after the step-1 transaction has committed. */
    public void publishCommitCommand(Order order) {
        String correlationId = sagaStateService.getCurrentCorrelationId(order.getId());
        Message<InventoryCommitStockCommand> message = MessageBuilder
                .withPayload(new InventoryCommitStockCommand(order.getId(), toLineItems(order)))
                .setHeader(KafkaHeaders.TOPIC, COMMIT_STOCK_TOPIC)
                .setHeader(KafkaHeaders.KEY, String.valueOf(order.getId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
                .build();
        kafkaTemplate.send(message);

        sagaStateService.markPaymentRequested(order.getId());
        log.info("Checkout saga: requested stock commit for order {} (correlation {})", order.getId(), correlationId);
    }

    /** Payment step 3: apply inventory-service's reply — PAID or compensate (back to CONFIRMED, stock stays held). */
    @KafkaListener(topics = STOCK_COMMIT_REPLY_TOPIC)
    @Transactional
    public void onStockCommitReply(InventoryStockCommitReply reply,
                                    @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
        if (sagaStateService.shouldIgnoreReply(reply.orderId(), correlationId)) {
            log.info("Checkout saga: ignoring stale/settled stock-commit reply for order {}", reply.orderId());
            return;
        }

        if (reply.success()) {
            Order order = orderService.markPaid(reply.orderId());
            sagaStateService.markCompleted(reply.orderId());
            publishOrderPaid(order, correlationId);
            log.info("Checkout saga: order {} PAID", reply.orderId());
        } else {
            orderService.revertToConfirmed(reply.orderId(), reply.reason());
            sagaStateService.markConfirmed(reply.orderId());
            log.info("Checkout saga: order {} reverted to CONFIRMED — {}", reply.orderId(), reply.reason());
        }
    }

    // ---- Cancel-after-CONFIRMED compensation ----

    /** Fire-and-forget: releases a stock hold that was never committed. No reply, no saga state change (order is already CANCELLED). */
    public void releaseReservedStock(Order order) {
        String correlationId = UUID.randomUUID().toString();
        Message<InventoryReleaseStockCommand> message = MessageBuilder
                .withPayload(new InventoryReleaseStockCommand(order.getId(), toLineItems(order)))
                .setHeader(KafkaHeaders.TOPIC, RELEASE_STOCK_TOPIC)
                .setHeader(KafkaHeaders.KEY, String.valueOf(order.getId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
                .build();
        kafkaTemplate.send(message);
        log.info("Checkout saga: released stock hold for cancelled order {} (correlation {})", order.getId(), correlationId);
    }

    // ---- Reconciliation ----

    /**
     * Called by OrderSagaReconciliationJob for a saga the sweep found stuck past
     * app.saga-reconciliation.stuck-threshold on either leg - the Reconciliation pattern's
     * response to a lost/undelivered reply that shouldIgnoreReply's redelivery/staleness guards
     * were never designed to detect (there's no message to receive at all).
     *
     * Re-checks the step fresh inside this transaction first: the sweep's query and this call
     * aren't atomic, so a real reply may have arrived and already settled the saga in between -
     * in that case this is a no-op. Retrying re-publishes with the *same* correlationId (not a
     * new one): same Kafka key (orderId) as the original means Kafka guarantees both land in the
     * same partition and get processed in order by a single consumer thread, so
     * inventory-service's Transactional Inbox (InboxMessage, PK'd on correlationId) treats the
     * redelivery as a duplicate at receipt time instead of double-applying the effect - if the
     * original attempt already finished (PROCESSED), it resends the stored reply without
     * re-running business logic; otherwise (still queued/processing) it's simply dropped, since
     * the original attempt already owns answering it. Even in the pathological case of genuinely
     * concurrent processing (a consumer rebalance mid-flight), the primary key constraint on
     * correlation_id rejects the second INSERT and rolls back that whole transaction.
     *
     * The two legs compensate to different targets: a stuck reservation gives up back to OPEN
     * (nothing was ever held), but a stuck commit gives up back to CONFIRMED, not OPEN - the
     * stock hold from the verify leg is still legitimately in place, only the payment attempt
     * timed out, so the cashier just needs to retry payment, not re-verify stock.
     */
    @Transactional
    public void retryOrCompensate(Long orderId) {
        SagaStep step = sagaStateService.getCurrentStep(orderId);
        if (step != SagaStep.STOCK_RESERVATION_REQUESTED && step != SagaStep.PAYMENT_REQUESTED) {
            return;
        }

        if (sagaStateService.getRetryCount(orderId) < reconciliationProperties.maxRetries()) {
            sagaStateService.incrementRetryCount(orderId);
            Order order = orderService.getOrder(orderId);
            if (step == SagaStep.STOCK_RESERVATION_REQUESTED) {
                publishReservationCommand(order);
            } else {
                publishCommitCommand(order);
            }
            log.info("Checkout saga: reconciliation re-published {} for order {} (attempt {})",
                    step, orderId, sagaStateService.getRetryCount(orderId));
        } else if (step == SagaStep.STOCK_RESERVATION_REQUESTED) {
            String reason = "Inventory reservation timed out after " + reconciliationProperties.maxRetries() + " retries";
            orderService.compensateToOpen(orderId, reason);
            sagaStateService.markCompensated(orderId);
            log.info("Checkout saga: order {} compensated back to OPEN by reconciliation — {}", orderId, reason);
        } else {
            String reason = "Payment confirmation timed out after " + reconciliationProperties.maxRetries() + " retries";
            orderService.revertToConfirmed(orderId, reason);
            sagaStateService.markConfirmed(orderId);
            log.info("Checkout saga: order {} reverted to CONFIRMED by reconciliation — {}", orderId, reason);
        }
    }

    private void publishOrderPaid(Order order, String correlationId) {
        BigDecimal grandTotal = order.getItems().stream()
                .map(item -> item.getPriceSnapshot().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        OrderPaidEvent event = new OrderPaidEvent(
                order.getId(), order.getTable().getId(), order.getClosedAt(), toLineItems(order), grandTotal, order.getPaymentMethod());

        Message<OrderPaidEvent> message = MessageBuilder
                .withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, ORDER_PAID_TOPIC)
                .setHeader(KafkaHeaders.KEY, String.valueOf(order.getId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
                .build();
        kafkaTemplate.send(message);
    }

    private List<OrderLineItem> toLineItems(Order order) {
        return order.getItems().stream()
                .map(item -> new OrderLineItem(item.getMenuItemId(), item.getQuantity()))
                .toList();
    }
}
