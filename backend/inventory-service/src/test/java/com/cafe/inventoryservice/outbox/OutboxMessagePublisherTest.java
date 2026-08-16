package com.cafe.inventoryservice.outbox;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.inventoryservice.inbox.InboxMessageProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxMessagePublisherTest {

    private static final Long ID = 1L;
    private static final Long ORDER_ID = 7L;
    private static final String CORRELATION_ID = "corr-1";

    @Mock
    private OutboxMessageRepository outboxMessageRepository;
    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;
    @SuppressWarnings("unchecked")
    private final SendResult<Object, Object> sendResult = mockSendResult();

    private OutboxMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties(Duration.ofMillis(500), 20, 3, Duration.ofSeconds(5));
        // NOOP: these tests exercise outbox publishing logic, not tracing behavior itself (see
        // publishOne_restoresStoredTraceparentIntoChildSpanForKafkaSend below for that) - a real
        // no-op Tracer/Propagator avoids mocking the whole Span/SpanBuilder fluent chain just to
        // make publishOne's span-wrapping code path not NPE.
        publisher = new OutboxMessagePublisher(outboxMessageRepository, kafkaTemplate, new ObjectMapper(), properties,
                Tracer.NOOP, Propagator.NOOP);
    }

    @SuppressWarnings("unchecked")
    private static SendResult<Object, Object> mockSendResult() {
        return org.mockito.Mockito.mock(SendResult.class);
    }

    private OutboxMessage processingMessage(OutboxMessageType type) throws Exception {
        String payload = type == OutboxMessageType.RESERVATION_REPLY
                ? new ObjectMapper().writeValueAsString(InventoryStockReservationReply.success(ORDER_ID))
                : new ObjectMapper().writeValueAsString(InventoryStockCommitReply.success(ORDER_ID));
        return OutboxMessage.builder()
                .id(ID)
                .orderId(ORDER_ID)
                .messageType(type)
                .correlationId(CORRELATION_ID)
                .payload(payload)
                .status(OutboxStatus.PROCESSING)
                .attemptCount(0)
                .build();
    }

    @Test
    void claimBatch_locksPendingRowsAndFlipsToProcessing() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        message.setStatus(OutboxStatus.PENDING);
        when(outboxMessageRepository.lockNextByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(message));

        List<Long> claimed = publisher.claimBatch();

        assertAll(
                () -> assertThat(claimed).containsExactly(ID),
                () -> assertThat(message.getStatus()).isEqualTo(OutboxStatus.PROCESSING)
        );
    }

    @Test
    void publishOne_reservationReply_sendsToReservationTopicAndMarksPublished() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));
        when(kafkaTemplate.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publishOne(ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<Object>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());

        assertAll(
                () -> assertThat(message.getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
                () -> assertThat(message.getPublishedAt()).isNotNull(),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                        .isEqualTo(InboxMessageProcessor.RESERVATION_REPLY_TOPIC),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.KEY)).isEqualTo(String.valueOf(ORDER_ID)),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.CORRELATION_ID)).isEqualTo(CORRELATION_ID),
                () -> assertThat(captor.getValue().getPayload()).isEqualTo(InventoryStockReservationReply.success(ORDER_ID))
        );
    }

    @Test
    void publishOne_commitReply_sendsToCommitTopicAndMarksPublished() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.COMMIT_REPLY);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));
        when(kafkaTemplate.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publishOne(ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<Object>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());

        assertAll(
                () -> assertThat(message.getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
                () -> assertThat(message.getPublishedAt()).isNotNull(),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                        .isEqualTo(InboxMessageProcessor.COMMIT_REPLY_TOPIC),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.KEY)).isEqualTo(String.valueOf(ORDER_ID)),
                () -> assertThat(captor.getValue().getHeaders().get(KafkaHeaders.CORRELATION_ID)).isEqualTo(CORRELATION_ID),
                () -> assertThat(captor.getValue().getPayload()).isEqualTo(InventoryStockCommitReply.success(ORDER_ID))
        );
    }

    @Test
    void publishOne_throwsAndLeavesRowProcessingWhenSendFails() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));
        CompletableFuture<SendResult<Object, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaTemplate.send(any(Message.class))).thenReturn(failed);

        Throwable thrown = catchThrowable(() -> publisher.publishOne(ID));

        assertAll(
                () -> assertThat(thrown).isInstanceOf(IllegalStateException.class),
                () -> assertThat(thrown).hasMessageContaining("Failed to publish outbox message " + ID),
                () -> assertThat(thrown).hasCauseInstanceOf(ExecutionException.class),
                () -> assertThat(thrown).hasRootCauseMessage("broker unreachable"),
                () -> assertThat(message.getStatus()).isEqualTo(OutboxStatus.PROCESSING)
        );
    }

    @Test
    void publishOne_throwsWhenMessageMissing() {
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> publisher.publishOne(ID));

        assertAll(
                () -> assertThat(thrown).isInstanceOf(NoSuchElementException.class),
                () -> assertThat(thrown).hasMessageContaining("Outbox message not found: " + ID)
        );
    }

    /**
     * Both cases share the same arrange/act shape and only differ in how close attemptCount
     * starts to app.outbox.max-attempts (3, from the OutboxProperties built in setUp) - one
     * parameterized suite over (initial attempts, expected attempts, expected status) covers the
     * under-max and at-max branches instead of duplicating the whole test body per branch.
     */
    private static Stream<Arguments> recordFailureScenarios() {
        return Stream.of(
                Arguments.of("underMaxAttempts_goesBackToPending", 0, 1, OutboxStatus.PENDING),
                Arguments.of("atMaxAttempts_terminatesAsFailed", 2, 3, OutboxStatus.FAILED)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordFailureScenarios")
    void recordFailure_transitionsStatusBasedOnAttemptCount(
            String caseName, int initialAttempts, int expectedAttempts, OutboxStatus expectedStatus) throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        message.setAttemptCount(initialAttempts);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));

        publisher.recordFailure(ID, "db down");

        assertAll(
                () -> assertThat(message.getAttemptCount()).isEqualTo(expectedAttempts),
                () -> assertThat(message.getStatus()).isEqualTo(expectedStatus),
                () -> assertThat(message.getErrorReason()).isEqualTo("db down")
        );
    }

    /**
     * Distributed tracing: proves publishOne() actually restores the row's stored traceparent
     * (via Propagator.extract, keyed exactly like InboxMessageProcessor's own Propagator.inject
     * writes it) into a child span kept current for the Kafka send, and ends that span - rather
     * than just not crashing, which is all the NOOP-based tests above prove.
     */
    @Test
    void publishOne_restoresStoredTraceparentIntoChildSpanForKafkaSend() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        message.setTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));
        when(kafkaTemplate.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        Tracer mockTracer = mock(Tracer.class);
        Propagator mockPropagator = mock(Propagator.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);

        when(mockPropagator.extract(eq(Map.of("traceparent", message.getTraceparent())), any()))
                .thenReturn(spanBuilder);
        when(spanBuilder.name(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.tag(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(mockTracer.withSpan(span)).thenReturn(spanInScope);

        OutboxMessagePublisher tracedPublisher = new OutboxMessagePublisher(outboxMessageRepository, kafkaTemplate,
                new ObjectMapper(), new OutboxProperties(Duration.ofMillis(500), 20, 3, Duration.ofSeconds(5)),
                mockTracer, mockPropagator);

        tracedPublisher.publishOne(ID);

        assertAll(
                () -> assertThat(message.getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
                () -> verify(mockPropagator).extract(eq(Map.of("traceparent", message.getTraceparent())), any()),
                () -> verify(spanBuilder).name("outbox-publish"),
                () -> verify(spanBuilder).tag("outbox.message.id", String.valueOf(ID)),
                () -> verify(spanBuilder).tag("outbox.message.type", OutboxMessageType.RESERVATION_REPLY.name()),
                () -> verify(spanBuilder).start(),
                () -> verify(mockTracer).withSpan(span),
                () -> verify(span).end()
        );
    }
}
