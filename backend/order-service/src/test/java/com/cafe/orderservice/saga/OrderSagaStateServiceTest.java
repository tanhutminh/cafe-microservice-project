package com.cafe.orderservice.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafe.common.exception.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class OrderSagaStateServiceTest {

    private static final Long ORDER_ID = 42L;

    @Mock
    private OrderSagaStateRepository sagaStateRepository;

    private OrderSagaStateService service;

    @BeforeEach
    void setUp() {
        service = new OrderSagaStateService(sagaStateRepository);
    }

    private OrderSagaState existingState(SagaStep step, String correlationId) {
        return OrderSagaState.builder()
                .orderId(ORDER_ID)
                .correlationId(correlationId)
                .step(step)
                .requestedAt(Instant.now())
                .updatedAt(Instant.now())
                .retryCount(0)
                .build();
    }

	@Test
    void start_savesFreshStartedStateAndReturnsItsCorrelationId() {
        String correlationId = service.start(ORDER_ID);

        ArgumentCaptor<OrderSagaState> captor = ArgumentCaptor.forClass(OrderSagaState.class);
        verify(sagaStateRepository).save(captor.capture());
        OrderSagaState saved = captor.getValue();
        assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getStep()).isEqualTo(SagaStep.STARTED);
        assertThat(saved.getCorrelationId()).isEqualTo(correlationId);
        assertThat(correlationId).isNotBlank();
    }

	@Test
    void getCurrentCorrelationId_returnsStoredValue() {
        when(sagaStateRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(existingState(SagaStep.STOCK_RESERVATION_REQUESTED, "corr-1")));

        assertThat(service.getCurrentCorrelationId(ORDER_ID)).isEqualTo("corr-1");
    }

	@Test
    void getCurrentCorrelationId_throwsWhenMissing() {
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentCorrelationId(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

	@Test
    void startPaymentAttempt_mintsNewCorrelationIdAndResetsRetryCount() {
        OrderSagaState existing = existingState(SagaStep.CONFIRMED, "corr-verify");
        existing.setRetryCount(2);
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

        String newCorrelationId = service.startPaymentAttempt(ORDER_ID);

        assertThat(newCorrelationId).isNotEqualTo("corr-verify");
        assertThat(existing.getCorrelationId()).isEqualTo(newCorrelationId);
        assertThat(existing.getRetryCount()).isZero();
        verify(sagaStateRepository).save(existing);
    }

	@Test
    void startPaymentAttempt_throwsWhenMissing() {
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startPaymentAttempt(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

	@Test
    void markStockReservationRequested_updatesStep() {
        OrderSagaState existing = existingState(SagaStep.STARTED, "corr-1");
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

        service.markStockReservationRequested(ORDER_ID);

        assertThat(existing.getStep()).isEqualTo(SagaStep.STOCK_RESERVATION_REQUESTED);
        verify(sagaStateRepository).save(existing);
    }

	@Test
    void markConfirmed_updatesStep() {
        OrderSagaState existing = existingState(SagaStep.STOCK_RESERVATION_REQUESTED, "corr-1");
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

        service.markConfirmed(ORDER_ID);

        assertThat(existing.getStep()).isEqualTo(SagaStep.CONFIRMED);
    }

	@Test
    void markPaymentRequested_updatesStep() {
        OrderSagaState existing = existingState(SagaStep.CONFIRMED, "corr-1");
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

        service.markPaymentRequested(ORDER_ID);

        assertThat(existing.getStep()).isEqualTo(SagaStep.PAYMENT_REQUESTED);
    }

	@Test
    void markCompleted_updatesStep() {
        OrderSagaState existing = existingState(SagaStep.PAYMENT_REQUESTED, "corr-1");
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

        service.markCompleted(ORDER_ID);

        assertThat(existing.getStep()).isEqualTo(SagaStep.COMPLETED);
    }

	@Test
    void markCompensated_updatesStep() {
        OrderSagaState existing = existingState(SagaStep.STOCK_RESERVATION_REQUESTED, "corr-1");
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

        service.markCompensated(ORDER_ID);

        assertThat(existing.getStep()).isEqualTo(SagaStep.COMPENSATED);
    }

	@Test
    void updateStep_throwsWhenMissing() {
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markConfirmed(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

	@Test
    void getCurrentStep_returnsStoredStep() {
        when(sagaStateRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(existingState(SagaStep.PAYMENT_REQUESTED, "corr-1")));

        assertThat(service.getCurrentStep(ORDER_ID)).isEqualTo(SagaStep.PAYMENT_REQUESTED);
    }

	@Test
    void getCurrentStep_throwsWhenMissing() {
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentStep(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

	@Test
    void getRetryCount_returnsStoredValue() {
        OrderSagaState existing = existingState(SagaStep.STOCK_RESERVATION_REQUESTED, "corr-1");
        existing.setRetryCount(3);
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

        assertThat(service.getRetryCount(ORDER_ID)).isEqualTo(3);
    }

	@Test
    void getRetryCount_throwsWhenMissing() {
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRetryCount(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

	@Test
    void incrementRetryCount_incrementsByOne() {
        OrderSagaState existing = existingState(SagaStep.STOCK_RESERVATION_REQUESTED, "corr-1");
        existing.setRetryCount(1);
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

        service.incrementRetryCount(ORDER_ID);

        assertThat(existing.getRetryCount()).isEqualTo(2);
        verify(sagaStateRepository).save(existing);
    }

	@Test
    void incrementRetryCount_throwsWhenMissing() {
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.incrementRetryCount(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

	@Test
    void shouldIgnoreReply_trueWhenNoStateFound() {
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThat(service.shouldIgnoreReply(ORDER_ID, "corr-1")).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = SagaStep.class, names = {"COMPLETED", "COMPENSATED", "CONFIRMED"})
    void shouldIgnoreReply_trueForTerminalOrIdleSteps(SagaStep step) {
        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(existingState(step, "corr-1")));

        assertThat(service.shouldIgnoreReply(ORDER_ID, "corr-1")).isTrue();
    }

	@Test
    void shouldIgnoreReply_trueWhenCorrelationIdIsStale() {
        when(sagaStateRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(existingState(SagaStep.STOCK_RESERVATION_REQUESTED, "corr-current")));

        assertThat(service.shouldIgnoreReply(ORDER_ID, "corr-old")).isTrue();
    }

	@Test
    void shouldIgnoreReply_falseForCurrentAttemptAwaitingReply() {
        when(sagaStateRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(existingState(SagaStep.STOCK_RESERVATION_REQUESTED, "corr-current")));

        assertThat(service.shouldIgnoreReply(ORDER_ID, "corr-current")).isFalse();
    }
}
