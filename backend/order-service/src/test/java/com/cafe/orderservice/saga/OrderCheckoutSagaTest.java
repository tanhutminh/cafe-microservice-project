package com.cafe.orderservice.saga;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.orderservice.order.Order;
import com.cafe.orderservice.order.OrderItem;
import com.cafe.orderservice.order.OrderService;
import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.outbox.OutboxMessage;
import com.cafe.orderservice.outbox.OutboxMessageRepository;
import com.cafe.orderservice.outbox.OutboxMessageType;
import com.cafe.orderservice.outbox.OutboxStatus;
import com.cafe.orderservice.table.DiningTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCheckoutSagaTest {

    private static final Long ORDER_ID = 42L;
    private static final String CORRELATION_ID = "corr-1";

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private OrderService orderService;
    @Mock
    private OrderSagaStateService sagaStateService;
    @Mock
    private OutboxMessageRepository outboxMessageRepository;

    private OrderCheckoutSaga saga;

    @BeforeEach
    void setUp() {
        SagaReconciliationProperties properties = new SagaReconciliationProperties(Duration.ofSeconds(60), 3);
        // findAndRegisterModules() mirrors what Spring Boot's autoconfigured ObjectMapper bean
        // does for the real injected instance (registers JavaTimeModule for Instant fields like
        // OrderPaidEvent.closedAt) - a bare `new ObjectMapper()` doesn't have it.
        saga = new OrderCheckoutSaga(orderService, sagaStateService, outboxMessageRepository,
                new ObjectMapper().findAndRegisterModules(), properties, VALIDATOR);
    }

    private Order order(OrderStatus status) {
        DiningTable table = DiningTable.builder().id(3L).tableNumber("T3").capacity(4).build();
        Order order = Order.builder()
                .id(ORDER_ID)
                .table(table)
                .status(status)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        OrderItem item = OrderItem.builder()
                .id(1L)
                .order(order)
                .menuItemId(9L)
                .nameSnapshot("Latte")
                .priceSnapshot(BigDecimal.valueOf(50000))
                .quantity(2)
                .build();
        order.getItems().add(item);
        return order;
    }

    private OrderSagaState sagaState(SagaStep step, String correlationId, int retryCount) {
        return OrderSagaState.builder()
                .orderId(ORDER_ID)
                .correlationId(correlationId)
                .step(step)
                .requestedAt(Instant.now())
                .updatedAt(Instant.now())
                .retryCount(retryCount)
                .build();
    }

    /**
     * Fake repository backed by a single in-memory row, so a real {@link OrderSagaStateService}
     * built on top of it runs its actual logic (shouldIgnoreReply, updateStep, retry
     * bookkeeping...) against genuine saga state instead of a directly stubbed method result.
     * {@code initial} is null for a fresh checkout with no saga row yet - {@code start()} then
     * creates the first one, which subsequent {@code findById} calls see via this same fake.
     */
    private OrderSagaStateRepository fakeSagaStateRepository(OrderSagaState initial) {
        OrderSagaStateRepository repository = mock(OrderSagaStateRepository.class);
        AtomicReference<OrderSagaState> stored = new AtomicReference<>(initial);
        when(repository.findById(ORDER_ID)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        // lenient: the "ignored"/"no-op" tests intentionally never trigger a save at all - that's
        // the behavior under test - so strict stubbing would flag this stub as unused there.
        lenient().when(repository.save(any(OrderSagaState.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return stored.get();
        });
        return repository;
    }

    private OrderCheckoutSaga sagaWith(OrderSagaStateRepository sagaStateRepository) {
        SagaReconciliationProperties properties = new SagaReconciliationProperties(Duration.ofSeconds(60), 3);
        return new OrderCheckoutSaga(orderService, new OrderSagaStateService(sagaStateRepository),
                outboxMessageRepository, new ObjectMapper().findAndRegisterModules(), properties, VALIDATOR);
    }

    @Test
    void startCheckout_persistsReserveStockOutboxRowAndAdvancesSagaStep() {
        Order pendingOrder = order(OrderStatus.PENDING_CONFIRMATION);
        when(orderService.checkout(ORDER_ID)).thenReturn(pendingOrder);

        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(null);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        Order result = saga.startCheckout(ORDER_ID);

        OrderSagaState state = sagaStateRepository.findById(ORDER_ID).orElseThrow();
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());
        OutboxMessage queued = captor.getValue();

        assertAll(
                () -> assertThat(result).isSameAs(pendingOrder),
                () -> assertThat(state.getStep()).isEqualTo(SagaStep.STOCK_RESERVATION_REQUESTED),
                () -> assertThat(state.getCorrelationId()).isNotBlank(),
                () -> assertThat(queued.getOrderId()).isEqualTo(ORDER_ID),
                () -> assertThat(queued.getMessageType()).isEqualTo(OutboxMessageType.RESERVE_STOCK),
                () -> assertThat(queued.getCorrelationId()).isEqualTo(state.getCorrelationId()),
                () -> assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(queued.getPayload()).contains("\"orderId\":42")
        );
    }

    @Test
    void startPayment_persistsCommitStockOutboxRowAndAdvancesSagaStep() {
        Order confirmedOrder = order(OrderStatus.PAYMENT_PENDING);
        when(orderService.startPayment(ORDER_ID, "CASH")).thenReturn(confirmedOrder);

        // Pre-existing row from a settled verify leg - startPaymentAttempt reuses this row
        // rather than creating a new one, minting a fresh correlationId and resetting retries.
        OrderSagaState existing = sagaState(SagaStep.CONFIRMED, "corr-verify", 2);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(existing);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        saga.startPayment(ORDER_ID, "CASH");

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());

        assertAll(
                () -> assertThat(existing.getStep()).isEqualTo(SagaStep.PAYMENT_REQUESTED),
                () -> assertThat(existing.getCorrelationId()).isNotEqualTo("corr-verify"),
                () -> assertThat(existing.getRetryCount()).isZero(),
                () -> assertThat(captor.getValue().getMessageType()).isEqualTo(OutboxMessageType.COMMIT_STOCK),
                () -> assertThat(captor.getValue().getCorrelationId()).isEqualTo(existing.getCorrelationId())
        );
    }

    private static Stream<Arguments> cancelOrderScenarios() {
        return Stream.of(
                Arguments.of("previouslyConfirmed_queuesRelease", OrderStatus.CONFIRMED, true),
                Arguments.of("previouslyOpen_doesNotQueueRelease", OrderStatus.OPEN, false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cancelOrderScenarios")
    void cancelOrder_queuesReleaseOnlyWhenPreviouslyConfirmed(String caseName, OrderStatus initialStatus, boolean expectRelease) {
        Order initial = order(initialStatus);
        Order cancelled = order(OrderStatus.CANCELLED);
        when(orderService.getOrder(ORDER_ID)).thenReturn(initial);
        when(orderService.cancel(ORDER_ID)).thenReturn(cancelled);

        Order result = saga.cancelOrder(ORDER_ID);

        assertThat(result).isSameAs(cancelled);
        if (expectRelease) {
            ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
            verify(outboxMessageRepository).save(captor.capture());
            assertAll(
                    () -> assertThat(captor.getValue().getMessageType()).isEqualTo(OutboxMessageType.RELEASE_STOCK),
                    () -> assertThat(captor.getValue().getOrderId()).isEqualTo(ORDER_ID)
            );
        } else {
            verify(outboxMessageRepository, never()).save(any(OutboxMessage.class));
        }
    }

    @Test
    void onStockReservationReply_success_marksOrderAndSagaConfirmed() {
        // Exercises the real OrderSagaStateService.shouldIgnoreReply logic instead of stubbing
        // its result directly: the saga genuinely is awaiting this correlationId at
        // STOCK_RESERVATION_REQUESTED, so a matching success reply must not be ignored.
        OrderSagaState state = sagaState(SagaStep.STOCK_RESERVATION_REQUESTED, CORRELATION_ID, 0);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(state);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        saga.onStockReservationReply(InventoryStockReservationReply.success(ORDER_ID), CORRELATION_ID);

        verify(orderService).markConfirmed(ORDER_ID);
        assertThat(state.getStep()).isEqualTo(SagaStep.CONFIRMED);
    }

    /**
     * Both onStockReservationReply and onStockCommitReply call the same private validate()
     * before touching any saga/order state, and both reply records share the identical
     * @NotNull @Positive orderId constraint - one parameterized suite over (handler, invalid
     * payload) pairs covers both entry points and both constraints instead of testing only one
     * handler/one constraint and leaving the other three combinations unverified.
     */
    private static Stream<Arguments> invalidReplyPayloads() {
        return Stream.of(
                Arguments.of("onStockReservationReply_nullOrderId",
                        (Consumer<OrderCheckoutSaga>) s -> s.onStockReservationReply(
                                new InventoryStockReservationReply(null, true, null), CORRELATION_ID)),
                Arguments.of("onStockReservationReply_negativeOrderId",
                        (Consumer<OrderCheckoutSaga>) s -> s.onStockReservationReply(
                                new InventoryStockReservationReply(-1L, true, null), CORRELATION_ID)),
                Arguments.of("onStockCommitReply_nullOrderId",
                        (Consumer<OrderCheckoutSaga>) s -> s.onStockCommitReply(
                                new InventoryStockCommitReply(null, true, null), CORRELATION_ID)),
                Arguments.of("onStockCommitReply_negativeOrderId",
                        (Consumer<OrderCheckoutSaga>) s -> s.onStockCommitReply(
                                new InventoryStockCommitReply(-1L, true, null), CORRELATION_ID))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReplyPayloads")
    void invalidReplyPayload_rejectedBeforeTouchingSagaState(String caseName, Consumer<OrderCheckoutSaga> invoker) {
        assertThatThrownBy(() -> invoker.accept(saga))
                .isInstanceOf(ConstraintViolationException.class);

        verify(sagaStateService, never()).shouldIgnoreReply(any(), any());
        verify(orderService, never()).markConfirmed(anyLong());
        verify(orderService, never()).markPaid(anyLong());
    }

    @Test
    void onStockReservationReply_failure_compensatesToOpen() {
        OrderSagaState state = sagaState(SagaStep.STOCK_RESERVATION_REQUESTED, CORRELATION_ID, 0);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(state);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        saga.onStockReservationReply(InventoryStockReservationReply.failure(ORDER_ID, "out of stock"), CORRELATION_ID);

        verify(orderService).compensateToOpen(ORDER_ID, "out of stock");
        assertThat(state.getStep()).isEqualTo(SagaStep.COMPENSATED);
    }

    @Test
    void onStockReservationReply_ignoredWhenStale() {
        // CONFIRMED is a terminal/idle step for the verify leg - shouldIgnoreReply treats it as
        // stale even with a matching correlationId, so this reply must be dropped untouched.
        OrderSagaState state = sagaState(SagaStep.CONFIRMED, CORRELATION_ID, 0);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(state);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        saga.onStockReservationReply(InventoryStockReservationReply.success(ORDER_ID), CORRELATION_ID);

        verify(orderService, never()).markConfirmed(anyLong());
        assertThat(state.getStep()).isEqualTo(SagaStep.CONFIRMED);
    }

    @Test
    void onStockCommitReply_success_marksPaidAndQueuesOrderPaidOutboxRow() {
        Order paidOrder = order(OrderStatus.PAID);
        paidOrder.setClosedAt(Instant.now());
        when(orderService.markPaid(ORDER_ID)).thenReturn(paidOrder);

        OrderSagaState state = sagaState(SagaStep.PAYMENT_REQUESTED, CORRELATION_ID, 0);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(state);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        saga.onStockCommitReply(InventoryStockCommitReply.success(ORDER_ID), CORRELATION_ID);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());

        assertAll(
                () -> assertThat(state.getStep()).isEqualTo(SagaStep.COMPLETED),
                () -> assertThat(captor.getValue().getMessageType()).isEqualTo(OutboxMessageType.ORDER_PAID),
                () -> assertThat(captor.getValue().getCorrelationId()).isEqualTo(CORRELATION_ID)
        );
    }

    @Test
    void onStockCommitReply_failure_revertsToConfirmed() {
        OrderSagaState state = sagaState(SagaStep.PAYMENT_REQUESTED, CORRELATION_ID, 0);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(state);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        saga.onStockCommitReply(InventoryStockCommitReply.failure(ORDER_ID, "payment declined"), CORRELATION_ID);

        verify(orderService).revertToConfirmed(ORDER_ID, "payment declined");
        assertThat(state.getStep()).isEqualTo(SagaStep.CONFIRMED);
        verify(outboxMessageRepository, never()).save(any(OutboxMessage.class));
    }

    @Test
    void retryOrCompensate_underMaxRetries_reQueuesReservationCommand() {
        OrderSagaState state = sagaState(SagaStep.STOCK_RESERVATION_REQUESTED, CORRELATION_ID, 1);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(state);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);
        when(orderService.getOrder(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_CONFIRMATION));

        saga.retryOrCompensate(ORDER_ID);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());
        verify(orderService, never()).compensateToOpen(anyLong(), any());

        assertAll(
                () -> assertThat(state.getRetryCount()).isEqualTo(2),
                () -> assertThat(captor.getValue().getMessageType()).isEqualTo(OutboxMessageType.RESERVE_STOCK),
                () -> assertThat(captor.getValue().getCorrelationId()).isEqualTo(CORRELATION_ID)
        );
    }

    /**
     * Both legs share the same "give up after maxRetries" shape but compensate to different
     * targets (see retryOrCompensate's Javadoc for why) - one parameterized suite over
     * (initialStep, expected service call, expected final step) covers both instead of
     * duplicating the whole arrange/act block per leg.
     */
    private static Stream<Arguments> atMaxRetriesLegs() {
        return Stream.of(
                Arguments.of("reservationLeg_compensatesToOpen", SagaStep.STOCK_RESERVATION_REQUESTED,
                        (BiConsumer<OrderService, Long>) (svc, id) -> verify(svc).compensateToOpen(eq(id), any()),
                        SagaStep.COMPENSATED),
                Arguments.of("paymentLeg_revertsToConfirmed", SagaStep.PAYMENT_REQUESTED,
                        (BiConsumer<OrderService, Long>) (svc, id) -> verify(svc).revertToConfirmed(eq(id), any()),
                        SagaStep.CONFIRMED)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("atMaxRetriesLegs")
    void retryOrCompensate_atMaxRetries_compensatesAppropriateLeg(
            String caseName, SagaStep initialStep, BiConsumer<OrderService, Long> verifyServiceCall, SagaStep expectedFinalStep) {
        OrderSagaState state = sagaState(initialStep, CORRELATION_ID, 3);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(state);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        saga.retryOrCompensate(ORDER_ID);

        verifyServiceCall.accept(orderService, ORDER_ID);
        assertThat(state.getStep()).isEqualTo(expectedFinalStep);
    }

    @Test
    void retryOrCompensate_noOpWhenSagaAlreadySettled() {
        OrderSagaState state = sagaState(SagaStep.COMPLETED, CORRELATION_ID, 0);
        OrderSagaStateRepository sagaStateRepository = fakeSagaStateRepository(state);
        OrderCheckoutSaga saga = sagaWith(sagaStateRepository);

        saga.retryOrCompensate(ORDER_ID);

        assertThat(state.getRetryCount()).isZero();
        verify(outboxMessageRepository, never()).save(any(OutboxMessage.class));
    }
}
