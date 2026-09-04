package com.cafe.orderservice.saga;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciliation pattern: a periodic sweep that repairs sagas an event-driven exchange alone can't
 * recover from, on either saga leg - order-service published a command (reserve or commit) and is
 * still waiting on the matching reply, but the reply never came (inventory-service down for a
 * while, the message got lost, etc). Without this, such an order would sit at PENDING_CONFIRMATION
 * or PAYMENT_PENDING forever; the reply handlers' shouldIgnoreReply guards only handle
 * stale/duplicate *replies that do arrive*, not a reply that never arrives at all.
 *
 * <p>Pure orchestration, no business logic of its own: finds candidates, delegates the actual
 * retry-or-compensate decision to OrderSaga (the orchestrator that owns every other saga transition
 * too, and knows which leg a given stuck saga is on), and isolates one saga's failure from the rest
 * of the sweep.
 */
@Component
public class OrderSagaReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(OrderSagaReconciliationJob.class);

  private final OrderSagaStateRepository sagaStateRepository;
  private final OrderSaga orderSaga;
  private final SagaReconciliationProperties properties;

  public OrderSagaReconciliationJob(
      OrderSagaStateRepository sagaStateRepository,
      OrderSaga orderSaga,
      SagaReconciliationProperties properties) {
    this.sagaStateRepository = sagaStateRepository;
    this.orderSaga = orderSaga;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${app.saga-reconciliation.sweep-interval:30s}")
  public void sweep() {
    Instant threshold = Instant.now().minus(properties.stuckThreshold());
    List<OrderSagaState> stuck =
        sagaStateRepository.findAllByStepInAndUpdatedAtBefore(
            Set.of(SagaStep.STOCK_RESERVATION_REQUESTED, SagaStep.PAYMENT_REQUESTED), threshold);

    if (stuck.isEmpty()) {
      return;
    }
    log.info("Saga reconciliation sweep: found {} stuck saga(s)", stuck.size());

    for (OrderSagaState state : stuck) {
      try {
        orderSaga.retryOrCompensate(state.getOrderId());
      } catch (Exception e) {
        log.error("Saga reconciliation sweep: failed to process order {}", state.getOrderId(), e);
      }
    }
  }
}
