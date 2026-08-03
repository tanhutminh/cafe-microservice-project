package com.cafe.orderservice.saga;

import com.cafe.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderSagaStateService {

    private final OrderSagaStateRepository sagaStateRepository;

    public OrderSagaStateService(OrderSagaStateRepository sagaStateRepository) {
        this.sagaStateRepository = sagaStateRepository;
    }

    /** Starts (or restarts) the saga for an order and returns the fresh correlation id to publish with. */
    @Transactional
    public String start(Long orderId) {
        String correlationId = UUID.randomUUID().toString();
        OrderSagaState state = OrderSagaState.builder()
                .orderId(orderId)
                .correlationId(correlationId)
                .step(SagaStep.STARTED)
                .requestedAt(Instant.now())
                .build();
        sagaStateRepository.save(state);
        return correlationId;
    }

    @Transactional(readOnly = true)
    public String getCurrentCorrelationId(Long orderId) {
        return sagaStateRepository.findById(orderId)
                .map(OrderSagaState::getCorrelationId)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
    }

    @Transactional
    public void markStockReservationRequested(Long orderId) {
        updateStep(orderId, SagaStep.STOCK_RESERVATION_REQUESTED);
    }

    /** Verify success path — stock is held, awaiting payment. */
    @Transactional
    public void markConfirmed(Long orderId) {
        updateStep(orderId, SagaStep.CONFIRMED);
    }

    /**
     * Starts (or restarts) the payment leg on the *existing* saga row for this order — unlike
     * start(), which inserts a new row, this order already has one from the checkout leg.
     * Fresh correlationId (same reasoning as start()'s) and a reset retry count, since this is
     * a new attempt distinct from the checkout leg's retries.
     */
    @Transactional
    public String startPaymentAttempt(Long orderId) {
        OrderSagaState state = sagaStateRepository.findById(orderId)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
        String correlationId = UUID.randomUUID().toString();
        state.setCorrelationId(correlationId);
        state.setRetryCount(0);
        sagaStateRepository.save(state);
        return correlationId;
    }

    @Transactional
    public void markPaymentRequested(Long orderId) {
        updateStep(orderId, SagaStep.PAYMENT_REQUESTED);
    }

    @Transactional
    public void markCompleted(Long orderId) {
        updateStep(orderId, SagaStep.COMPLETED);
    }

    @Transactional
    public void markCompensated(Long orderId) {
        updateStep(orderId, SagaStep.COMPENSATED);
    }

    /** Read fresh inside OrderCheckoutSaga.retryOrCompensate's transaction, to guard against a
     *  reply arriving concurrently with the reconciliation sweep (see that method's Javadoc). */
    @Transactional(readOnly = true)
    public SagaStep getCurrentStep(Long orderId) {
        return sagaStateRepository.findById(orderId)
                .map(OrderSagaState::getStep)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
    }

    @Transactional(readOnly = true)
    public int getRetryCount(Long orderId) {
        return sagaStateRepository.findById(orderId)
                .map(OrderSagaState::getRetryCount)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
    }

    @Transactional
    public void incrementRetryCount(Long orderId) {
        OrderSagaState state = sagaStateRepository.findById(orderId)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
        state.setRetryCount(state.getRetryCount() + 1);
        sagaStateRepository.save(state);
    }

    /**
     * Guards against two distinct kinds of stale replies: Kafka redelivering a reply after the
     * saga already reached a terminal step, and a reply for an attempt that's no longer current
     * (the order was checked out again — e.g. after a prior failure — before this one arrived).
     */
    @Transactional(readOnly = true)
    public boolean shouldIgnoreReply(Long orderId, String correlationId) {
        return sagaStateRepository.findById(orderId)
                .map(state -> state.getStep() == SagaStep.COMPLETED
                        || state.getStep() == SagaStep.COMPENSATED
                        || !state.getCorrelationId().equals(correlationId))
                .orElse(true);
    }

    private void updateStep(Long orderId, SagaStep step) {
        OrderSagaState state = sagaStateRepository.findById(orderId)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
        state.setStep(step);
        sagaStateRepository.save(state);
    }
}
