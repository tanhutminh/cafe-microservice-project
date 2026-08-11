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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

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
    private OutboxMessageRepository outboxMessageRepository;

    private InboxMessageProcessor processor;

    @BeforeEach
    void setUp() {
        InboxProperties properties = new InboxProperties(java.time.Duration.ofMillis(500), 20, 3);
        processor = new InboxMessageProcessor(inboxMessageRepository, stockReservationService,
                outboxMessageRepository, new ObjectMapper(), properties);
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
    void processOne_reserveStock_marksProcessedAndQueuesReplyInOutbox() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RESERVE_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));
        when(stockReservationService.reserve(eq(ORDER_ID), any()))
                .thenReturn(InventoryStockReservationReply.success(ORDER_ID));

        processor.processOne(CORRELATION_ID);

        assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(message.getResultSuccess()).isTrue();
        assertThat(message.getProcessedAt()).isNotNull();

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());
        OutboxMessage queued = captor.getValue();
        assertThat(queued.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(queued.getMessageType()).isEqualTo(OutboxMessageType.RESERVATION_REPLY);
        assertThat(queued.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void processOne_commitStock_marksProcessedAndQueuesReplyInOutbox() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.COMMIT_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));
        when(stockReservationService.commit(eq(ORDER_ID), any()))
                .thenReturn(InventoryStockCommitReply.success(ORDER_ID));

        processor.processOne(CORRELATION_ID);

        assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(message.getResultSuccess()).isTrue();
        assertThat(message.getResultReason()).isNull();

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageType()).isEqualTo(OutboxMessageType.COMMIT_REPLY);
    }

    @Test
    void processOne_releaseStock_marksProcessedWithoutQueuingReply() throws Exception {
        InboxMessage message = pendingMessage(InboxMessageType.RELEASE_STOCK);
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.of(message));

        processor.processOne(CORRELATION_ID);

        assertThat(message.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(message.getResultSuccess()).isTrue();
        verify(outboxMessageRepository, never()).save(any(OutboxMessage.class));
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
