package com.cafe.inventoryservice.inbox;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@ExtendWith(MockitoExtension.class)
class InboxPollerTest {

  private static final InboxProperties PROPERTIES =
      new InboxProperties(Duration.ofMillis(500), 20, 5);

  @Mock private InboxMessageProcessor processor;

  @Test
  void poll_processesEveryClaimedMessage() {
    when(processor.claimBatch()).thenReturn(List.of("a", "b"));

    new InboxPoller(processor, PROPERTIES).poll();

    assertAll(
        () -> {
          InOrder order = inOrder(processor);
          order.verify(processor).processOne("a");
          order.verify(processor).processOne("b");
        },
        () -> verify(processor, never()).recordFailure(anyString(), anyString()));
  }

  @Test
  void poll_recordsFailureAndKeepsProcessingRestOfBatchWhenOneMessageThrows() {
    when(processor.claimBatch()).thenReturn(List.of("a", "b"));
    doThrow(new RuntimeException("boom")).when(processor).processOne("a");

    new InboxPoller(processor, PROPERTIES).poll();

    assertAll(
        () -> verify(processor).processOne("a"),
        () -> verify(processor).recordFailure("a", "boom"),
        () -> verify(processor).processOne("b"));
  }

  @Test
  void configureTasks_registersPollAtConfiguredPollInterval() {
    ScheduledTaskRegistrar registrar = mock(ScheduledTaskRegistrar.class);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

    new InboxPoller(processor, PROPERTIES).configureTasks(registrar);

    verify(registrar).addFixedDelayTask(taskCaptor.capture(), eq(PROPERTIES.pollInterval()));

    when(processor.claimBatch()).thenReturn(List.of());
    taskCaptor.getValue().run();
    verify(processor).claimBatch();
  }
}
