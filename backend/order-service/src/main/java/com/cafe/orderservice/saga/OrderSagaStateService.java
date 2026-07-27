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

    /** Starts (or restarts) the saga for an order and returns the fresh attempt id to publish with. */
    @Transactional
    public String start(Long orderId) {
        String sagaAttemptId = UUID.randomUUID().toString();
        OrderSagaState state = OrderSagaState.builder()
                .orderId(orderId)
                .sagaAttemptId(sagaAttemptId)
                .step(SagaStep.STARTED)
                .requestedAt(Instant.now())
                .build();
        sagaStateRepository.save(state);
        return sagaAttemptId;
    }

    @Transactional(readOnly = true)
    public String getCurrentAttemptId(Long orderId) {
        return sagaStateRepository.findById(orderId)
                .map(OrderSagaState::getSagaAttemptId)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
    }

    @Transactional
    public void markStockReservationRequested(Long orderId) {
        updateStep(orderId, SagaStep.STOCK_RESERVATION_REQUESTED);
    }

    @Transactional
    public void markCompleted(Long orderId) {
        updateStep(orderId, SagaStep.COMPLETED);
    }

    @Transactional
    public void markCompensated(Long orderId) {
        updateStep(orderId, SagaStep.COMPENSATED);
    }

    /**
     * Guards against two distinct kinds of stale replies: Kafka redelivering a reply after the
     * saga already reached a terminal step, and a reply for an attempt that's no longer current
     * (the order was checked out again — e.g. after a prior failure — before this one arrived).
     */
    @Transactional(readOnly = true)
    public boolean shouldIgnoreReply(Long orderId, String sagaAttemptId) {
        return sagaStateRepository.findById(orderId)
                .map(state -> state.getStep() == SagaStep.COMPLETED
                        || state.getStep() == SagaStep.COMPENSATED
                        || !state.getSagaAttemptId().equals(sagaAttemptId))
                .orElse(true);
    }

    private void updateStep(Long orderId, SagaStep step) {
        OrderSagaState state = sagaStateRepository.findById(orderId)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
        state.setStep(step);
        sagaStateRepository.save(state);
    }
}
