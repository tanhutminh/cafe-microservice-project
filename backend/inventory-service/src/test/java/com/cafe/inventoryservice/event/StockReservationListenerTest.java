package com.cafe.inventoryservice.event;

import com.cafe.common.event.InventoryCommitStockCommand;
import com.cafe.common.event.InventoryReleaseStockCommand;
import com.cafe.common.event.InventoryReserveStockCommand;
import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.inbox.InboxMessage;
import com.cafe.inventoryservice.inbox.InboxMessageProcessor;
import com.cafe.inventoryservice.inbox.InboxMessageRepository;
import com.cafe.inventoryservice.inbox.InboxMessageType;
import com.cafe.inventoryservice.inbox.InboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockReservationListenerTest {

    private static final String CORRELATION_ID = "corr-1";
    private static final Long ORDER_ID = 7L;

    @Mock
    private InboxMessageRepository inboxMessageRepository;
    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private StockReservationListener listener;

    @BeforeEach
    void setUp() {
        // NOOP: these tests exercise inbox-enqueue logic, not tracing behavior itself (see
        // onReserveStockCommand_capturesLiveSpanTraceparentOntoEnqueuedMessage below for that) -
        // Tracer.NOOP's currentSpan() returns null, so captureTraceParent() harmlessly stores a
        // null traceparent for these tests.
        listener = new StockReservationListener(inboxMessageRepository, new ObjectMapper(), kafkaTemplate,
                Validation.buildDefaultValidatorFactory().getValidator(), Tracer.NOOP, Propagator.NOOP);
    }

    private InventoryReserveStockCommand reserveCommand() {
        return new InventoryReserveStockCommand(ORDER_ID, List.of(new OrderLineItem(1L, 2)));
    }

    private InventoryCommitStockCommand commitCommand() {
        return new InventoryCommitStockCommand(ORDER_ID, List.of(new OrderLineItem(1L, 2)));
    }

    private InventoryReleaseStockCommand releaseCommand() {
        return new InventoryReleaseStockCommand(ORDER_ID, List.of(new OrderLineItem(1L, 2)));
    }

    @Test
    void onReserveStockCommand_firstSeen_enqueuesPendingMessage() {
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.empty());

        listener.onReserveStockCommand(reserveCommand(), CORRELATION_ID);

        ArgumentCaptor<InboxMessage> captor = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inboxMessageRepository).save(captor.capture());
        InboxMessage saved = captor.getValue();

        assertAll(
                () -> assertThat(saved.getCorrelationId()).isEqualTo(CORRELATION_ID),
                () -> assertThat(saved.getOrderId()).isEqualTo(ORDER_ID),
                () -> assertThat(saved.getMessageType()).isEqualTo(InboxMessageType.RESERVE_STOCK),
                () -> assertThat(saved.getStatus()).isEqualTo(InboxStatus.PENDING),
                () -> assertThat(saved.getAttemptCount()).isZero(),
                () -> assertThat(saved.getPayload()).contains("\"menuItemId\":1"),
                // NOOP tracer (see setUp) -> captureTraceParent() has nothing live to capture.
                () -> assertThat(saved.getTraceparent()).isNull(),
                () -> verify(kafkaTemplate, never()).send(any(Message.class))
        );
    }

    @Test
    void onReserveStockCommand_invalidPayload_rejectedBeforeEnqueue() {
        InventoryReserveStockCommand invalid = new InventoryReserveStockCommand(ORDER_ID, Collections.emptyList());

        assertThatThrownBy(() -> listener.onReserveStockCommand(invalid, CORRELATION_ID))
                .isInstanceOf(ConstraintViolationException.class);

        assertAll(
                () -> verify(inboxMessageRepository, never()).findById(any()),
                () -> verify(inboxMessageRepository, never()).save(any())
        );
    }

    @Test
    void onReserveStockCommand_duplicateProcessed_resendsStoredReply() {
        InboxMessage processed = InboxMessage.builder()
                .correlationId(CORRELATION_ID)
                .orderId(ORDER_ID)
                .messageType(InboxMessageType.RESERVE_STOCK)
                .payload("[]")
                .status(InboxStatus.PROCESSED)
                .resultSuccess(true)
                .attemptCount(0)
                .build();
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(processed));

        listener.onReserveStockCommand(reserveCommand(), CORRELATION_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<Object>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());

        assertAll(
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                        .isEqualTo(InboxMessageProcessor.RESERVATION_REPLY_TOPIC),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.KEY)).isEqualTo(String.valueOf(ORDER_ID)),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.CORRELATION_ID)).isEqualTo(CORRELATION_ID),
                () -> assertThat(captor.getValue().getPayload()).isEqualTo(InventoryStockReservationReply.success(ORDER_ID)),
                () -> verify(inboxMessageRepository, never()).save(any())
        );
    }

    @ParameterizedTest
    @EnumSource(value = InboxStatus.class, names = {"PENDING", "PROCESSING", "FAILED"})
    void onReserveStockCommand_duplicateNotYetProcessed_doesNothing(InboxStatus status) {
        InboxMessage existing = InboxMessage.builder()
                .correlationId(CORRELATION_ID)
                .orderId(ORDER_ID)
                .messageType(InboxMessageType.RESERVE_STOCK)
                .payload("[]")
                .status(status)
                .attemptCount(0)
                .build();
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(existing));

        listener.onReserveStockCommand(reserveCommand(), CORRELATION_ID);

        assertAll(
                () -> verify(kafkaTemplate, never()).send(any(Message.class)),
                () -> verify(inboxMessageRepository, never()).save(any())
        );
    }

    @Test
    void onCommitStockCommand_firstSeen_enqueuesPendingMessage() {
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.empty());

        listener.onCommitStockCommand(commitCommand(), CORRELATION_ID);

        ArgumentCaptor<InboxMessage> captor = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inboxMessageRepository).save(captor.capture());
        InboxMessage saved = captor.getValue();

        assertAll(
                () -> assertThat(saved.getCorrelationId()).isEqualTo(CORRELATION_ID),
                () -> assertThat(saved.getOrderId()).isEqualTo(ORDER_ID),
                () -> assertThat(saved.getMessageType()).isEqualTo(InboxMessageType.COMMIT_STOCK),
                () -> assertThat(saved.getStatus()).isEqualTo(InboxStatus.PENDING),
                () -> assertThat(saved.getPayload()).contains("\"menuItemId\":1"),
                () -> verify(kafkaTemplate, never()).send(any(Message.class))
        );
    }

    @Test
    void onCommitStockCommand_duplicateProcessed_resendsStoredReply() {
        InboxMessage processed = InboxMessage.builder()
                .correlationId(CORRELATION_ID)
                .orderId(ORDER_ID)
                .messageType(InboxMessageType.COMMIT_STOCK)
                .payload("[]")
                .status(InboxStatus.PROCESSED)
                .resultSuccess(false)
                .resultReason("insufficient stock")
                .attemptCount(0)
                .build();
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(processed));

        listener.onCommitStockCommand(commitCommand(), CORRELATION_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<Object>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());

        assertAll(
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                        .isEqualTo(InboxMessageProcessor.COMMIT_REPLY_TOPIC),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.KEY)).isEqualTo(String.valueOf(ORDER_ID)),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.CORRELATION_ID)).isEqualTo(CORRELATION_ID),
                () -> assertThat(captor.getValue().getPayload())
                        .isEqualTo(InventoryStockCommitReply.failure(ORDER_ID, "insufficient stock")),
                () -> verify(inboxMessageRepository, never()).save(any())
        );
    }

    @Test
    void onReleaseStockCommand_firstSeen_enqueuesPendingMessage() {
        when(inboxMessageRepository.existsById(CORRELATION_ID)).thenReturn(false);

        listener.onReleaseStockCommand(releaseCommand(), CORRELATION_ID);

        ArgumentCaptor<InboxMessage> captor = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inboxMessageRepository).save(captor.capture());
        InboxMessage saved = captor.getValue();

        assertAll(
                () -> assertThat(saved.getCorrelationId()).isEqualTo(CORRELATION_ID),
                () -> assertThat(saved.getOrderId()).isEqualTo(ORDER_ID),
                () -> assertThat(saved.getMessageType()).isEqualTo(InboxMessageType.RELEASE_STOCK),
                () -> assertThat(saved.getStatus()).isEqualTo(InboxStatus.PENDING),
                () -> assertThat(saved.getPayload()).contains("\"menuItemId\":1"),
                () -> verify(kafkaTemplate, never()).send(any(Message.class))
        );
    }

    @Test
    void onReleaseStockCommand_duplicate_doesNothing() {
        when(inboxMessageRepository.existsById(CORRELATION_ID)).thenReturn(true);

        listener.onReleaseStockCommand(releaseCommand(), CORRELATION_ID);

        assertAll(
                () -> verify(inboxMessageRepository, never()).save(any()),
                () -> verify(kafkaTemplate, never()).send(any(Message.class))
        );
    }

    /**
     * Distributed tracing: proves enqueue() actually captures the live current span (the one
     * spring.kafka.listener.observation-enabled auto-extracted from the inbound record before
     * this listener method ran) into the saved row's traceparent, via Propagator.inject - rather
     * than just not crashing, which is all the NOOP-based tests above prove.
     */
    @Test
    void onReserveStockCommand_capturesLiveSpanTraceparentOntoEnqueuedMessage() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        Tracer mockTracer = mock(Tracer.class);
        Propagator mockPropagator = mock(Propagator.class);
        Span currentSpan = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(mockTracer.currentSpan()).thenReturn(currentSpan);
        when(currentSpan.context()).thenReturn(context);
        doAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(1);
            Propagator.Setter<Map<String, String>> setter = invocation.getArgument(2);
            setter.set(carrier, "traceparent", traceparent);
            return null;
        }).when(mockPropagator).inject(eq(context), any(), any());

        StockReservationListener tracedListener = new StockReservationListener(inboxMessageRepository,
                new ObjectMapper(), kafkaTemplate, Validation.buildDefaultValidatorFactory().getValidator(),
                mockTracer, mockPropagator);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.empty());

        tracedListener.onReserveStockCommand(reserveCommand(), CORRELATION_ID);

        ArgumentCaptor<InboxMessage> captor = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inboxMessageRepository).save(captor.capture());
        InboxMessage saved = captor.getValue();

        assertAll(
                () -> assertThat(saved.getTraceparent()).isEqualTo(traceparent),
                () -> assertThat(saved.getCorrelationId()).isEqualTo(CORRELATION_ID),
                () -> assertThat(saved.getMessageType()).isEqualTo(InboxMessageType.RESERVE_STOCK),
                () -> verify(mockPropagator).inject(eq(context), any(), any())
        );
    }
}
