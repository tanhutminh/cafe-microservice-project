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
import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.outbox.OutboxMessage;
import com.cafe.orderservice.outbox.OutboxMessageRepository;
import com.cafe.orderservice.outbox.OutboxMessageType;
import com.cafe.orderservice.outbox.OutboxStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga orchestrator for order checkout, living inside order-service since it already owns the Order
 * aggregate and its status lifecycle. Runs as a single orchestrated state machine, not
 * choreography, since there is exactly one step that can fail and needs compensation per leg —
 * report-service is a plain event subscriber, not a saga participant.
 *
 * <p>Two legs, both real Kafka round trips: Verify (reserve stock as a soft hold, OPEN ->
 * PENDING_CONFIRMATION -> CONFIRMED or back to OPEN) and Payment (commit the hold into a real
 * deduction, CONFIRMED -> PAYMENT_PENDING -> PAID or back to CONFIRMED). Cancelling a CONFIRMED
 * order releases the hold via a third, fire-and-forget command with no reply leg.
 *
 * <p>Every outbound command/event goes through the Transactional Outbox pattern (see the outbox
 * package): each publish method below writes a durable {@link OutboxMessage} row in the same
 * transaction as the local state change it accompanies, instead of calling KafkaTemplate directly.
 * A separate OutboxPoller/OutboxMessagePublisher relays queued rows to Kafka afterward. This closes
 * what used to be a dual-write gap — a crash between the local commit and a live Kafka send could
 * leave a saga stuck with no command ever sent, invisible to OrderSagaReconciliationJob (which only
 * scans for steps a *sent* command produces).
 */
@Component
public class OrderSaga {

  private static final Logger log = LoggerFactory.getLogger(OrderSaga.class);

  public static final String RESERVE_STOCK_TOPIC = "inventory.reserve-stock.command";
  public static final String STOCK_RESERVATION_REPLY_TOPIC = "inventory.stock-reservation.reply";
  public static final String COMMIT_STOCK_TOPIC = "inventory.commit-stock.command";
  public static final String STOCK_COMMIT_REPLY_TOPIC = "inventory.stock-commit.reply";
  public static final String RELEASE_STOCK_TOPIC = "inventory.release-stock.command";
  public static final String ORDER_PAID_TOPIC = "order.paid";

  private final OrderService orderService;
  private final OrderSagaStateService sagaStateService;
  private final OutboxMessageRepository outboxMessageRepository;
  private final ObjectMapper objectMapper;
  private final SagaReconciliationProperties reconciliationProperties;
  private final Validator validator;
  private final Tracer tracer;
  private final Propagator propagator;

  public OrderSaga(
      OrderService orderService,
      OrderSagaStateService sagaStateService,
      OutboxMessageRepository outboxMessageRepository,
      ObjectMapper objectMapper,
      SagaReconciliationProperties reconciliationProperties,
      Validator validator,
      Tracer tracer,
      Propagator propagator) {
    this.orderService = orderService;
    this.sagaStateService = sagaStateService;
    this.outboxMessageRepository = outboxMessageRepository;
    this.objectMapper = objectMapper;
    this.reconciliationProperties = reconciliationProperties;
    this.validator = validator;
    this.tracer = tracer;
    this.propagator = propagator;
  }

  // ---- Verify leg ----

  /**
   * Verify steps 1+2, one atomic transaction: order to PENDING_CONFIRMATION, saga row STARTED, then
   * immediately queue the reservation command and advance the saga step to
   * STOCK_RESERVATION_REQUESTED — all-or-nothing, so there's no window where the order/saga commit
   * lands without a durable record of the command that must eventually follow it.
   */
  @Transactional
  public Order startCheckout(Long orderId) {
    Order order = orderService.checkout(orderId);
    sagaStateService.start(orderId);
    publishReservationCommand(order);
    return order;
  }

  /**
   * Queues the reservation command in the outbox and advances the saga step. Not itself
   * transactional — always called from within an ambient @Transactional (startCheckout or
   * retryOrCompensate), whose commit is what actually makes the queued row durable.
   */
  public void publishReservationCommand(Order order) {
    String correlationId = sagaStateService.getCurrentCorrelationId(order.getId());
    enqueue(
        OutboxMessageType.RESERVE_STOCK,
        order,
        correlationId,
        new InventoryReserveStockCommand(order.getId(), toLineItems(order)));
    sagaStateService.markStockReservationRequested(order.getId());
    log.info(
        "Order saga: queued stock reservation for order {} (correlation {})",
        order.getId(),
        correlationId);
  }

  /**
   * Verify step 3: apply inventory-service's reply — CONFIRMED (stock held) or compensate (back to
   * OPEN).
   */
  @KafkaListener(topics = STOCK_RESERVATION_REPLY_TOPIC)
  @Transactional
  public void onStockReservationReply(
      InventoryStockReservationReply reply,
      @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
    validate(reply);
    if (sagaStateService.shouldIgnoreReply(reply.orderId(), correlationId)) {
      log.info(
          "Order saga: ignoring stale/settled stock-reservation reply for order {}",
          reply.orderId());
      return;
    }

    if (reply.success()) {
      orderService.markConfirmed(reply.orderId());
      sagaStateService.markConfirmed(reply.orderId());
      log.info("Order saga: order {} CONFIRMED (stock held)", reply.orderId());
    } else {
      orderService.compensateToOpen(reply.orderId(), reply.reason());
      sagaStateService.markCompensated(reply.orderId());
      log.info(
          "Order saga: order {} compensated back to OPEN — {}", reply.orderId(), reply.reason());
    }
  }

  // ---- Payment leg ----

  /**
   * Payment steps 1+2, one atomic transaction — same fold as startCheckout, for the same reason.
   */
  @Transactional
  public Order startPayment(Long orderId, String paymentMethod) {
    Order order = orderService.startPayment(orderId, paymentMethod);
    sagaStateService.startPaymentAttempt(orderId);
    publishCommitCommand(order);
    return order;
  }

  /**
   * Queues the commit command in the outbox and advances the saga step — same shape as
   * publishReservationCommand, see its Javadoc for the transactional-boundary reasoning.
   */
  public void publishCommitCommand(Order order) {
    String correlationId = sagaStateService.getCurrentCorrelationId(order.getId());
    enqueue(
        OutboxMessageType.COMMIT_STOCK,
        order,
        correlationId,
        new InventoryCommitStockCommand(order.getId(), toLineItems(order)));
    sagaStateService.markPaymentRequested(order.getId());
    log.info(
        "Order saga: queued stock commit for order {} (correlation {})",
        order.getId(),
        correlationId);
  }

  /**
   * Payment step 3: apply inventory-service's reply — PAID or compensate (back to CONFIRMED, stock
   * stays held).
   */
  @KafkaListener(topics = STOCK_COMMIT_REPLY_TOPIC)
  @Transactional
  public void onStockCommitReply(
      InventoryStockCommitReply reply, @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
    validate(reply);
    if (sagaStateService.shouldIgnoreReply(reply.orderId(), correlationId)) {
      log.info(
          "Order saga: ignoring stale/settled stock-commit reply for order {}", reply.orderId());
      return;
    }

    if (reply.success()) {
      Order order = orderService.markPaid(reply.orderId());
      sagaStateService.markCompleted(reply.orderId());
      publishOrderPaid(order, correlationId);
      log.info("Order saga: order {} PAID", reply.orderId());
    } else {
      orderService.revertToConfirmed(reply.orderId(), reply.reason());
      sagaStateService.markConfirmed(reply.orderId());
      log.info("Order saga: order {} reverted to CONFIRMED — {}", reply.orderId(), reply.reason());
    }
  }

  // ---- Cancel-after-CONFIRMED compensation ----

  /**
   * One atomic transaction: cancel the order, and — only if it was CONFIRMED, meaning a stock hold
   * actually exists — queue the release of that hold in the same commit. Replaces what used to be
   * two separate calls from OrderController (cancel, then a live release send), the same dual-write
   * shape the checkout/payment legs had.
   */
  @Transactional
  public Order cancelOrder(Long orderId) {
    Order order = orderService.getOrder(orderId);
    boolean wasConfirmed = order.getStatus() == OrderStatus.CONFIRMED;
    Order cancelled = orderService.cancel(orderId);
    if (wasConfirmed) {
      releaseReservedStock(cancelled);
    }
    return cancelled;
  }

  /**
   * Fire-and-forget: queues release of a stock hold that was never committed. No reply, no saga
   * state change (order is already CANCELLED). Always called from within an ambient @Transactional
   * (cancelOrder).
   */
  public void releaseReservedStock(Order order) {
    String correlationId = UUID.randomUUID().toString();
    enqueue(
        OutboxMessageType.RELEASE_STOCK,
        order,
        correlationId,
        new InventoryReleaseStockCommand(order.getId(), toLineItems(order)));
    log.info(
        "Order saga: queued stock hold release for cancelled order {} (correlation {})",
        order.getId(),
        correlationId);
  }

  // ---- Reconciliation ----

  /**
   * Called by OrderSagaReconciliationJob for a saga the sweep found stuck past
   * app.saga-reconciliation.stuck-threshold on either leg - the Reconciliation pattern's response
   * to a lost/undelivered reply that shouldIgnoreReply's redelivery/staleness guards were never
   * designed to detect (there's no message to receive at all).
   *
   * <p>Re-checks the step fresh inside this transaction first: the sweep's query and this call
   * aren't atomic, so a real reply may have arrived and already settled the saga in between - in
   * that case this is a no-op. Retrying re-queues the same *correlationId* (not a new one) into the
   * outbox: same Kafka key (orderId) as the original means Kafka guarantees both land in the same
   * partition and get processed in order by a single consumer thread, so inventory-service's
   * Transactional Inbox (InboxMessage, PK'd on correlationId) treats the redelivery as a duplicate
   * at receipt time instead of double-applying the effect - if the original attempt already
   * finished (PROCESSED), it resends the stored reply without re-running business logic; otherwise
   * (still queued/processing) it's simply dropped, since the original attempt already owns
   * answering it. Even in the pathological case of genuinely concurrent processing (a consumer
   * rebalance mid-flight), the primary key constraint on correlation_id rejects the second INSERT
   * and rolls back that whole transaction.
   *
   * <p>The two legs compensate to different targets: a stuck reservation gives up back to OPEN
   * (nothing was ever held), but a stuck commit gives up back to CONFIRMED, not OPEN - the stock
   * hold from the verify leg is still legitimately in place, only the payment attempt timed out, so
   * the cashier just needs to retry payment, not re-verify stock.
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
      log.info(
          "Order saga: reconciliation re-queued {} for order {} (attempt {})",
          step,
          orderId,
          sagaStateService.getRetryCount(orderId));
    } else if (step == SagaStep.STOCK_RESERVATION_REQUESTED) {
      String reason =
          "Inventory reservation timed out after "
              + reconciliationProperties.maxRetries()
              + " retries";
      orderService.compensateToOpen(orderId, reason);
      sagaStateService.markCompensated(orderId);
      log.info(
          "Order saga: order {} compensated back to OPEN by reconciliation — {}", orderId, reason);
    } else {
      String reason =
          "Payment confirmation timed out after "
              + reconciliationProperties.maxRetries()
              + " retries";
      orderService.revertToConfirmed(orderId, reason);
      sagaStateService.markConfirmed(orderId);
      log.info(
          "Order saga: order {} reverted to CONFIRMED by reconciliation — {}", orderId, reason);
    }
  }

  private void publishOrderPaid(Order order, String correlationId) {
    BigDecimal grandTotal =
        order.getItems().stream()
            .map(item -> item.getPriceSnapshot().multiply(BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    OrderPaidEvent event =
        new OrderPaidEvent(
            order.getId(),
            order.getTable().getId(),
            order.getClosedAt(),
            toLineItems(order),
            grandTotal,
            order.getPaymentMethod());
    enqueue(OutboxMessageType.ORDER_PAID, order, correlationId, event);
  }

  /**
   * Rejects a structurally invalid reply (null orderId - see the command/reply records' Bean
   * Validation annotations in common-lib) before it ever reaches {@code shouldIgnoreReply} (a null
   * orderId would otherwise NPE/IllegalArgumentException out of the JPA lookup there). Same
   * two-step shape as a REST controller's {@code @Valid}: annotations declare the constraint, this
   * call enforces it. Thrown {@link ConstraintViolationException} propagates out of the listener
   * method like any other exception, routing to the DLQ via KafkaErrorHandlingConfig exactly like a
   * deserialization failure would.
   */
  private void validate(Object reply) {
    var violations = validator.validate(reply);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
  }

  private void enqueue(OutboxMessageType type, Order order, String correlationId, Object payload) {
    OutboxMessage message =
        OutboxMessage.builder()
            .orderId(order.getId())
            .messageType(type)
            .correlationId(correlationId)
            .payload(serialize(payload))
            .status(OutboxStatus.PENDING)
            .attemptCount(0)
            .traceparent(captureTraceParent())
            .build();
    outboxMessageRepository.save(message);
  }

  /**
   * Captures the current W3C traceparent string so {@link
   * com.cafe.orderservice.outbox.OutboxMessagePublisher} can restore it into a child span on the
   * poller thread, which has no live trace context of its own - see that class's Javadoc for the
   * full distributed-tracing picture. Null when there's no live span (e.g. a scheduler-thread
   * caller like OrderSagaReconciliationJob); publishOne() then just starts a fresh root span
   * instead of erroring.
   */
  private String captureTraceParent() {
    Span current = tracer.currentSpan();
    if (current == null) {
      return null;
    }
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(current.context(), carrier, Map::put);
    return carrier.get("traceparent");
  }

  private String serialize(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize outbox payload", e);
    }
  }

  private List<OrderLineItem> toLineItems(Order order) {
    return order.getItems().stream()
        .map(item -> new OrderLineItem(item.getMenuItemId(), item.getQuantity()))
        .toList();
  }
}
