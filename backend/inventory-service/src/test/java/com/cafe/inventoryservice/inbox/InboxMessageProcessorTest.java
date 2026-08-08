package com.cafe.inventoryservice.inbox;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.reservation.StockReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private InboxMessageProcessor processor;

    @BeforeEach
    void setUp() {
        InboxProperties properties = new InboxProperties(java.time.Duration.ofMillis(500), 20, 3);
        processor = new InboxMessageProcessor(inboxMessageRepository, stockReservationService,
                kafkaTemplate, new ObjectMapper(), properties);
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

        assertThat(claimed).containsExactly(CORRELATION_ID);
        assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSING);
    }

    @Test
    void processOne_reserveStock_marksProcessedAndPublishesReply() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RESERVE_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));
        when(stockReservationService.reserve(eq(ORDER_ID), any()))
                .thenReturn(InventoryStockReservationReply.success(ORDER_ID));

        processor.processOne(CORRELATION_ID);

        assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(message.getResultSuccess()).isTrue();
        assertThat(message.getProcessedAt()).isNotNull();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                .isEqualTo(InboxMessageProcessor.RESERVATION_REPLY_TOPIC);
        assertThat(captor.getValue().getHeaders().get(KafkaHeaders.CORRELATION_ID)).isEqualTo(CORRELATION_ID);
    }

    @Test
    void processOne_commitStock_marksProcessedAndPublishesReply() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.COMMIT_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));
        when(stockReservationService.commit(eq(ORDER_ID), any()))
                .thenReturn(InventoryStockCommitReply.failure(ORDER_ID, "boom"));

        processor.processOne(CORRELATION_ID);

        assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(message.getResultSuccess()).isFalse();
        assertThat(message.getResultReason()).isEqualTo("boom");

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                .isEqualTo(InboxMessageProcessor.COMMIT_REPLY_TOPIC);
    }

    @Test
    void processOne_releaseStock_marksProcessedWithoutPublishingReply() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RELEASE_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));

        processor.processOne(CORRELATION_ID);

        assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(message.getResultSuccess()).isTrue();
        verify(kafkaTemplate, never()).send(any(Message.class));
    }

    @Test
    void processOne_throwsWhenMessageMissing() {
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.processOne(CORRELATION_ID))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void recordFailure_goesBackToPendingWhenUnderMaxAttempts() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RESERVE_STOCK);
        message.setAttemptCount(0);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));

        processor.recordFailure(CORRELATION_ID, "db down");

        assertThat(message.getAttemptCount()).isEqualTo(1);
        assertThat(message.getStatus()).isEqualTo(InboxStatus.PENDING);
        assertThat(message.getErrorReason()).isEqualTo("db down");
    }

    @Test
    void recordFailure_terminatesAsFailedAtMaxAttempts() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RESERVE_STOCK);
        message.setAttemptCount(2);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));

        processor.recordFailure(CORRELATION_ID, "db down again");

        assertThat(message.getAttemptCount()).isEqualTo(3);
        assertThat(message.getStatus()).isEqualTo(InboxStatus.FAILED);
    }
}
