package com.cafe.inventoryservice.inbox;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.outbox.OutboxMessage;
import com.cafe.inventoryservice.outbox.OutboxMessageRepository;
import com.cafe.inventoryservice.outbox.OutboxMessageType;
import com.cafe.inventoryservice.outbox.OutboxStatus;
import com.cafe.inventoryservice.reservation.StockReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxMessageProcessorTest {

    private static final String CORRELATION_ID = "corr-1";
    private static final Long ORDER_ID = 7L;

    @Mock
    private InboxMessageRepository inboxMessageRepository;
    @Mock
    private StockReservationService stockReservationService;
    @Mock
    private OutboxMessageRepository outboxMessageRepository;

    private InboxMessageProcessor processor;

    @BeforeEach
    void setUp() {
        InboxProperties properties = new InboxProperties(java.time.Duration.ofMillis(500), 20, 3);
        // NOOP: these tests exercise inbox processing logic, not tracing behavior itself (see
        // processOne_bridgesStoredTraceparentThroughSpanIntoQueuedReplyTraceparent below for
        // that) - a real no-op Tracer/Propagator avoids mocking the whole Span/SpanBuilder
        // fluent chain just to make processOne's span-wrapping code path not NPE.
        processor = new InboxMessageProcessor(inboxMessageRepository, stockReservationService,
                outboxMessageRepository, new ObjectMapper(), properties, Tracer.NOOP, Propagator.NOOP);
    }

    private InboxMessage pendingMessage(InboxMessageType type) throws Exception {
        String payload = new ObjectMapper().writeValueAsString(List.of(new OrderLineItem(1L, 2)));
        return InboxMessage.builder()
                .correlationId(CORRELATION_ID)
                .orderId(ORDER_ID)
                .messageType(type)
                .payload(payload)
                .status(InboxStatus.PROCESSING)
                .attemptCount(0)
                .build();
    }

    @Test
    void claimBatch_locksPendingRowsAndFlipsToProcessing() {
        InboxMessage message = InboxMessage.builder()
                .correlationId(CORRELATION_ID)
                .orderId(ORDER_ID)
                .messageType(InboxMessageType.RESERVE_STOCK)
                .payload("[]")
                .status(InboxStatus.PENDING)
                .attemptCount(0)
                .build();
        when(inboxMessageRepository.lockNextByStatus(eq(InboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(message));

        List<String> claimed = processor.claimBatch();

        assertAll(
                () -> assertThat(claimed).containsExactly(CORRELATION_ID),
                () -> assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSING)
        );
    }

    @Test
    void processOne_reserveStock_marksProcessedAndQueuesReplyInOutbox() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RESERVE_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));
        when(stockReservationService.reserve(eq(ORDER_ID), any()))
                .thenReturn(InventoryStockReservationReply.success(ORDER_ID));

        processor.processOne(CORRELATION_ID);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());
        OutboxMessage queued = captor.getValue();

        assertAll(
                () -> assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED),
                () -> assertThat(message.getResultSuccess()).isTrue(),
                () -> assertThat(message.getResultReason()).isNull(),
                () -> assertThat(message.getProcessedAt()).isNotNull(),
                () -> assertThat(queued.getOrderId()).isEqualTo(ORDER_ID),
                () -> assertThat(queued.getMessageType()).isEqualTo(OutboxMessageType.RESERVATION_REPLY),
                () -> assertThat(queued.getCorrelationId()).isEqualTo(CORRELATION_ID),
                () -> assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(queued.getPayload()).contains("\"orderId\":7"),
                // NOOP tracer (see setUp) -> captureTraceParent() has nothing live to capture.
                () -> assertThat(queued.getTraceparent()).isNull()
        );
    }

    @Test
    void processOne_commitStock_marksProcessedAndQueuesReplyInOutbox() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.COMMIT_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));
        when(stockReservationService.commit(eq(ORDER_ID), any()))
                .thenReturn(InventoryStockCommitReply.success(ORDER_ID));

        processor.processOne(CORRELATION_ID);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());
        OutboxMessage queued = captor.getValue();

        assertAll(
                () -> assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED),
                () -> assertThat(message.getResultSuccess()).isTrue(),
                () -> assertThat(message.getResultReason()).isNull(),
                () -> assertThat(message.getProcessedAt()).isNotNull(),
                () -> assertThat(queued.getOrderId()).isEqualTo(ORDER_ID),
                () -> assertThat(queued.getMessageType()).isEqualTo(OutboxMessageType.COMMIT_REPLY),
                () -> assertThat(queued.getCorrelationId()).isEqualTo(CORRELATION_ID),
                () -> assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(queued.getTraceparent()).isNull()
        );
    }

    @Test
    void processOne_releaseStock_marksProcessedWithoutQueuingReply() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RELEASE_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));

        processor.processOne(CORRELATION_ID);

        assertAll(
                () -> assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED),
                () -> assertThat(message.getResultSuccess()).isTrue(),
                () -> assertThat(message.getResultReason()).isNull(),
                () -> verify(outboxMessageRepository, never()).save(any(OutboxMessage.class))
        );
    }

    @Test
    void processOne_throwsWhenMessageMissing() {
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> processor.processOne(CORRELATION_ID));

        assertAll(
                () -> assertThat(thrown).isInstanceOf(NoSuchElementException.class),
                () -> assertThat(thrown).hasMessageContaining("Inbox message not found: " + CORRELATION_ID)
        );
    }

    /**
     * Both cases share the same arrange/act shape and only differ in how close attemptCount
     * starts to app.inbox.max-attempts (3, from the InboxProperties built in setUp) - one
     * parameterized suite over (initial attempts, expected attempts, expected status) covers the
     * under-max and at-max branches instead of duplicating the whole test body per branch.
     */
    private static Stream<Arguments> recordFailureScenarios() {
        return Stream.of(
                Arguments.of("underMaxAttempts_goesBackToPending", 0, 1, InboxStatus.PENDING),
                Arguments.of("atMaxAttempts_terminatesAsFailed", 2, 3, InboxStatus.FAILED)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordFailureScenarios")
    void recordFailure_transitionsStatusBasedOnAttemptCount(
            String caseName, int initialAttempts, int expectedAttempts, InboxStatus expectedStatus) throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RESERVE_STOCK);
        message.setAttemptCount(initialAttempts);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));

        processor.recordFailure(CORRELATION_ID, "db down");

        assertAll(
                () -> assertThat(message.getAttemptCount()).isEqualTo(expectedAttempts),
                () -> assertThat(message.getStatus()).isEqualTo(expectedStatus),
                () -> assertThat(message.getErrorReason()).isEqualTo("db down")
        );
    }

    /**
     * Distributed tracing: proves processOne() restores the row's stored traceparent (via
     * Propagator.extract) into a span kept current for the whole method body, and that
     * publishReply's own capture (via Propagator.inject, reading tracer.currentSpan()) records
     * *that same* span onto the queued reply's traceparent - closing the loop described in this
     * class's Javadoc - rather than just not crashing, which is all the NOOP-based tests above
     * prove.
     */
    @Test
    void processOne_bridgesStoredTraceparentThroughSpanIntoQueuedReplyTraceparent() throws Exception {
        String inboundTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String outboundTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-a1b2c3d4e5f60718-01";
        InboxMessage message = pendingMessage(InboxMessageType.RESERVE_STOCK);
        message.setTraceparent(inboundTraceparent);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));
        when(stockReservationService.reserve(eq(ORDER_ID), any()))
                .thenReturn(InventoryStockReservationReply.success(ORDER_ID));

        Tracer mockTracer = mock(Tracer.class);
        Propagator mockPropagator = mock(Propagator.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);

        when(mockPropagator.extract(eq(Map.of("traceparent", inboundTraceparent)), any()))
                .thenReturn(spanBuilder);
        when(spanBuilder.name(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.tag(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(mockTracer.withSpan(span)).thenReturn(spanInScope);
        when(mockTracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        doAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(1);
            Propagator.Setter<Map<String, String>> setter = invocation.getArgument(2);
            setter.set(carrier, "traceparent", outboundTraceparent);
            return null;
        }).when(mockPropagator).inject(eq(context), any(), any());

        InboxMessageProcessor tracedProcessor = new InboxMessageProcessor(inboxMessageRepository,
                stockReservationService, outboxMessageRepository, new ObjectMapper(),
                new InboxProperties(java.time.Duration.ofMillis(500), 20, 3), mockTracer, mockPropagator);

        tracedProcessor.processOne(CORRELATION_ID);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());

        assertAll(
                () -> assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED),
                () -> assertThat(captor.getValue().getTraceparent()).isEqualTo(outboundTraceparent),
                () -> verify(mockPropagator).extract(eq(Map.of("traceparent", inboundTraceparent)), any()),
                () -> verify(spanBuilder).name("inbox-process"),
                () -> verify(spanBuilder).tag("inbox.correlation.id", CORRELATION_ID),
                () -> verify(spanBuilder).tag("inbox.message.type", InboxMessageType.RESERVE_STOCK.name()),
                () -> verify(spanBuilder).start(),
                () -> verify(mockTracer).withSpan(span),
                () -> verify(span).end()
        );
    }
}
