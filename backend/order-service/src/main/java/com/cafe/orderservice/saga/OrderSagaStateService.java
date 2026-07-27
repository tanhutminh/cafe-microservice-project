package com.cafe.orderservice.saga;

import com.cafe.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OrderSagaStateService {

    private final OrderSagaStateRepository sagaStateRepository;

    public OrderSagaStateService(OrderSagaStateRepository sagaStateRepository) {
        this.sagaStateRepository = sagaStateRepository;
    }

    @Transactional
    public void start(Long orderId) {
        OrderSagaState state = OrderSagaState.builder()
                .orderId(orderId)
                .step(SagaStep.STARTED)
                .requestedAt(Instant.now())
                .build();
        sagaStateRepository.save(state);
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

    /** Idempotency guard: Kafka is at-least-once, so a reply can be redelivered after the saga already settled. */
    @Transactional(readOnly = true)
    public boolean isTerminal(Long orderId) {
        return sagaStateRepository.findById(orderId)
                .map(state -> state.getStep() == SagaStep.COMPLETED || state.getStep() == SagaStep.COMPENSATED)
                .orElse(false);
    }

    private void updateStep(Long orderId, SagaStep step) {
        OrderSagaState state = sagaStateRepository.findById(orderId)
                .orElseThrow(() -> ResourceNotFoundException.of("OrderSagaState", orderId));
        state.setStep(step);
        sagaStateRepository.save(state);
    }
}
