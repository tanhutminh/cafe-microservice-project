package com.cafe.orderservice.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Drives the Transactional Outbox's async relay: claims a batch of PENDING messages, then publishes
 * each one, isolating one message's failure from the rest of the batch - same per-item try/catch
 * shape as inventory-service's InboxPoller (the receive-side mirror of this class). Not
 * itself @Transactional: claimBatch() and publishOne()/recordFailure() are separate transactions on
 * OutboxMessagePublisher, called through that bean's proxy so each gets its own transaction
 * boundary (self-invocation within one class would silently bypass @Transactional). Disabled
 * entirely (bean not registered) when app.outbox.poll-enabled is false - an operational pause
 * switch, e.g. for a test whose mocked collaborators shouldn't observe a background poll.
 *
 * <p>Implements {@link SchedulingConfigurer} instead of annotating {@link #poll()} with
 * {@code @Scheduled} so the fixed delay is genuinely sourced from {@link
 * OutboxProperties#pollInterval()} - the same bound, application.yml-backed value every other
 * {@code app.outbox.*} tunable already goes through - rather than a second, separately-defaulted
 * {@code ${app.outbox.poll-interval:...}} placeholder that {@code @Scheduled} would otherwise
 * require and that could drift out of sync with it. Trade-off: Spring only wraps
 * {@code @Scheduled}-annotated methods in an observation-aware runnable, so a task registered this
 * way - via {@link ScheduledTaskRegistrar#addFixedDelayTask} - never produces a {@code
 * tasks.scheduled.execution} observation at all, unlike a real {@code @Scheduled} method's.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.outbox",
    name = "poll-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxPoller implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

  private final OutboxMessagePublisher publisher;
  private final OutboxProperties properties;

  public OutboxPoller(OutboxMessagePublisher publisher, OutboxProperties properties) {
    this.publisher = publisher;
    this.properties = properties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(this::poll, properties.pollInterval());
  }

  public void poll() {
    List<Long> claimed = publisher.claimBatch();
    for (Long id : claimed) {
      try {
        publisher.publishOne(id);
      } catch (Exception e) {
        log.error("Outbox poller: failed to publish message {}", id, e);
        publisher.recordFailure(id, e.getMessage());
      }
    }
  }
}
