package com.cafe.inventoryservice.event;

import com.cafe.common.event.InventoryCommitStockCommand;
import com.cafe.common.event.InventoryReleaseStockCommand;
import com.cafe.common.event.InventoryReserveStockCommand;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.inbox.InboxMessage;
import com.cafe.inventoryservice.inbox.InboxMessageProcessor;
import com.cafe.inventoryservice.inbox.InboxMessageRepository;
import com.cafe.inventoryservice.inbox.InboxMessageType;
import com.cafe.inventoryservice.inbox.InboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        listener = new StockReservationListener(inboxMessageRepository, new ObjectMapper(), kafkaTemplate);
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
        assertThat(saved.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getMessageType()).isEqualTo(InboxMessageType.RESERVE_STOCK);
        assertThat(saved.getStatus()).isEqualTo(InboxStatus.PENDING);
        assertThat(saved.getPayload()).contains("\"menuItemId\":1");
        verify(kafkaTemplate, never()).send(any(Message.class));
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
        assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                .isEqualTo(InboxMessageProcessor.RESERVATION_REPLY_TOPIC);
        verify(inboxMessageRepository, never()).save(any());
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

        verify(kafkaTemplate, never()).send(any(Message.class));
        verify(inboxMessageRepository, never()).save(any());
    }

    @Test
    void onCommitStockCommand_firstSeen_enqueuesPendingMessage() {
        when(inboxMessageRepository.findById(CORRELATION_ID)).thenReturn(Optional.empty());

        listener.onCommitStockCommand(commitCommand(), CORRELATION_ID);

        ArgumentCaptor<InboxMessage> captor = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inboxMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageType()).isEqualTo(InboxMessageType.COMMIT_STOCK);
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
        assertThat(captor.getValue().getHeaders().get(KafkaHeaders.TOPIC))
                .isEqualTo(InboxMessageProcessor.COMMIT_REPLY_TOPIC);
    }

    @Test
    void onReleaseStockCommand_firstSeen_enqueuesPendingMessage() {
        when(inboxMessageRepository.existsById(CORRELATION_ID)).thenReturn(false);

        listener.onReleaseStockCommand(releaseCommand(), CORRELATION_ID);

        ArgumentCaptor<InboxMessage> captor = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inboxMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageType()).isEqualTo(InboxMessageType.RELEASE_STOCK);
        verify(kafkaTemplate, never()).send(any(Message.class));
    }

    @Test
    void onReleaseStockCommand_duplicate_doesNothing() {
        when(inboxMessageRepository.existsById(CORRELATION_ID)).thenReturn(true);

        listener.onReleaseStockCommand(releaseCommand(), CORRELATION_ID);

        verify(inboxMessageRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(any(Message.class));
    }
}
