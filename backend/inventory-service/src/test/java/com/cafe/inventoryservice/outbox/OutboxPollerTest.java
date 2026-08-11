package com.cafe.inventoryservice.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxMessagePublisher publisher;

    @Test
    void poll_publishesEveryClaimedMessage() {
        when(publisher.claimBatch()).thenReturn(List.of(1L, 2L));

        new OutboxPoller(publisher).poll();

        InOrder order = inOrder(publisher);
        order.verify(publisher).publishOne(1L);
        order.verify(publisher).publishOne(2L);
        verify(publisher, never()).recordFailure(anyLong(), anyString());
    }

    @Test
    void poll_recordsFailureAndKeepsProcessingRestOfBatchWhenOnePublishThrows() {
        when(publisher.claimBatch()).thenReturn(List.of(1L, 2L));
        doThrow(new RuntimeException("boom")).when(publisher).publishOne(1L);

        new OutboxPoller(publisher).poll();

        verify(publisher).recordFailure(1L, "boom");
        verify(publisher).publishOne(2L);
    }
}
