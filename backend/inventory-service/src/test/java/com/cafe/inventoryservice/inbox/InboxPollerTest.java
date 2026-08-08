package com.cafe.inventoryservice.inbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxPollerTest {

    @Mock
    private InboxMessageProcessor processor;

    @Test
    void poll_processesEveryClaimedMessage() {
        when(processor.claimBatch()).thenReturn(List.of("a", "b"));

        new InboxPoller(processor).poll();

        InOrder order = inOrder(processor);
        order.verify(processor).processOne("a");
        order.verify(processor).processOne("b");
        verify(processor, org.mockito.Mockito.never()).recordFailure(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void poll_recordsFailureAndKeepsProcessingRestOfBatchWhenOneMessageThrows() {
        when(processor.claimBatch()).thenReturn(List.of("a", "b"));
        doThrow(new RuntimeException("boom")).when(processor).processOne("a");

        new InboxPoller(processor).poll();

        verify(processor).recordFailure("a", "boom");
        verify(processor).processOne("b");
    }
}
