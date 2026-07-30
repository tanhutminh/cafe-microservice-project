package com.cafe.orderservice.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Reconciliation pattern: a periodic sweep that repairs sagas an event-driven exchange alone
 * can't recover from - order-service published inventory.reserve-stock.command and is still
 * waiting on inventory.stock-reservation.reply, but the reply never came (inventory-service down
 * for a while, the message got lost, etc). Without this, such an order would sit at
 * PENDING_CONFIRMATION forever; onStockReservationReply's shouldIgnoreReply guards only handle
 * stale/duplicate *replies that do arrive*, not a reply that never arrives at all.
 *
 * Pure orchestration, no business logic of its own: finds candidates, delegates the actual
 * retry-or-compensate decision to OrderCheckoutSaga (the orchestrator that owns every other saga
 * transition too), and isolates one saga's failure from the rest of the sweep.
 */
@Component
public class OrderSagaReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaReconciliationJob.class);

    private final OrderSagaStateRepository sagaStateRepository;
    private final OrderCheckoutSaga orderCheckoutSaga;
    private final SagaReconciliationProperties properties;

    public OrderSagaReconciliationJob(OrderSagaStateRepository sagaStateRepository,
                                       OrderCheckoutSaga orderCheckoutSaga,
                                       SagaReconciliationProperties properties) {
        this.sagaStateRepository = sagaStateRepository;
        this.orderCheckoutSaga = orderCheckoutSaga;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.saga-reconciliation.sweep-interval:30s}")
    public void sweep() {
        Instant threshold = Instant.now().minus(properties.stuckThreshold());
        List<OrderSagaState> stuck = sagaStateRepository.findByStepAndUpdatedAtBefore(
                SagaStep.STOCK_RESERVATION_REQUESTED, threshold);

        if (stuck.isEmpty()) {
            return;
        }
        log.info("Saga reconciliation sweep: found {} stuck saga(s)", stuck.size());

        for (OrderSagaState state : stuck) {
            try {
                orderCheckoutSaga.retryOrCompensate(state.getOrderId());
            } catch (Exception e) {
                log.error("Saga reconciliation sweep: failed to process order {}", state.getOrderId(), e);
            }
        }
    }
}
