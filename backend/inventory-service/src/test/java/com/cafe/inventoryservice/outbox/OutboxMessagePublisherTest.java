package com.cafe.inventoryservice.outbox;

import com.cafe.inventoryservice.inbox.InboxMessageProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        publisher = new OutboxMessagePublisher(outboxMessageRepository, kafkaTemplate, new ObjectMapper(), properties);
    }

    @SuppressWarnings("unchecked")
    private static SendResult<Object, Object> mockSendResult() {
        return org.mockito.Mockito.mock(SendResult.class);
    }

    private OutboxMessage processingMessage(OutboxMessageType type) throws Exception {
        String payload = type == OutboxMessageType.RESERVATION_REPLY
                ? new ObjectMapper().writeValueAsString(com.cafe.common.event.InventoryStockReservationReply.success(ORDER_ID))
                : new ObjectMapper().writeValueAsString(com.cafe.common.event.InventoryStockCommitReply.success(ORDER_ID));
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

        assertThat(claimed).containsExactly(ID);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
    }

    @Test
    void publishOne_reservationReply_sendsToReservationTopicAndMarksPublished() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));
        when(kafkaTemplate.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publishOne(ID);

        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(message.getPublishedAt()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<Object>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                .isEqualTo(InboxMessageProcessor.RESERVATION_REPLY_TOPIC);
        assertThat(captor.getValue().getHeaders().get(KafkaHeaders.CORRELATION_ID)).isEqualTo(CORRELATION_ID);
    }

    @Test
    void publishOne_commitReply_sendsToCommitTopic() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.COMMIT_REPLY);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));
        when(kafkaTemplate.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publishOne(ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<Object>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                .isEqualTo(InboxMessageProcessor.COMMIT_REPLY_TOPIC);
    }

    @Test
    void publishOne_throwsAndLeavesRowProcessingWhenSendFails() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));
        CompletableFuture<SendResult<Object, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaTemplate.send(any(Message.class))).thenReturn(failed);

        assertThatThrownBy(() -> publisher.publishOne(ID)).isInstanceOf(IllegalStateException.class);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
    }

    @Test
    void publishOne_throwsWhenMessageMissing() {
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publisher.publishOne(ID)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void recordFailure_goesBackToPendingWhenUnderMaxAttempts() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        message.setAttemptCount(0);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));

        publisher.recordFailure(ID, "db down");

        assertThat(message.getAttemptCount()).isEqualTo(1);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.getErrorReason()).isEqualTo("db down");
    }

    @Test
    void recordFailure_terminatesAsFailedAtMaxAttempts() throws Exception {
        OutboxMessage message = processingMessage(OutboxMessageType.RESERVATION_REPLY);
        message.setAttemptCount(2);
        when(outboxMessageRepository.findById(ID)).thenReturn(Optional.of(message));

        publisher.recordFailure(ID, "db down again");

        assertThat(message.getAttemptCount()).isEqualTo(3);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }
}
