package com.cafe.orderservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

  private static final OutboxProperties PROPERTIES =
      new OutboxProperties(Duration.ofMillis(500), 20, 5, Duration.ofSeconds(5));

  @Mock private OutboxMessagePublisher publisher;

  @Test
  void poll_publishesEveryClaimedMessage() {
    when(publisher.claimBatch()).thenReturn(List.of(1L, 2L));

    new OutboxPoller(publisher, PROPERTIES).poll();

    assertAll(
        () -> {
          InOrder order = inOrder(publisher);
          order.verify(publisher).publishOne(1L);
          order.verify(publisher).publishOne(2L);
        },
        () -> verify(publisher, never()).recordFailure(anyLong(), anyString()));
  }

  @Test
  void poll_recordsFailureAndKeepsProcessingRestOfBatchWhenOnePublishThrows() {
    when(publisher.claimBatch()).thenReturn(List.of(1L, 2L));
    doThrow(new RuntimeException("boom")).when(publisher).publishOne(1L);

    new OutboxPoller(publisher, PROPERTIES).poll();

    assertAll(
        () -> verify(publisher).publishOne(1L),
        () -> verify(publisher).recordFailure(1L, "boom"),
        () -> verify(publisher).publishOne(2L));
  }

  @Test
  void configureTasks_registersPollAtConfiguredPollInterval() {
    ScheduledTaskRegistrar registrar = mock(ScheduledTaskRegistrar.class);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

    new OutboxPoller(publisher, PROPERTIES).configureTasks(registrar);

    verify(registrar).addFixedDelayTask(taskCaptor.capture(), eq(PROPERTIES.pollInterval()));

    when(publisher.claimBatch()).thenReturn(List.of());
    taskCaptor.getValue().run();
    verify(publisher).claimBatch();
  }

  private ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withBean(OutboxMessagePublisher.class, () -> mock(OutboxMessagePublisher.class))
        .withBean(OutboxProperties.class, () -> PROPERTIES)
        .withUserConfiguration(OutboxPoller.class);
  }

  @Test
  void isRegisteredWhenPollEnabledIsMissing() {
    contextRunner().run(context -> assertThat(context).hasSingleBean(OutboxPoller.class));
  }

  @Test
  void isRegisteredWhenPollEnabledIsTrue() {
    contextRunner()
        .withPropertyValues("app.outbox.poll-enabled=true")
        .run(context -> assertThat(context).hasSingleBean(OutboxPoller.class));
  }

  @Test
  void isNotRegisteredWhenPollEnabledIsFalse() {
    contextRunner()
        .withPropertyValues("app.outbox.poll-enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(OutboxPoller.class));
  }
}
