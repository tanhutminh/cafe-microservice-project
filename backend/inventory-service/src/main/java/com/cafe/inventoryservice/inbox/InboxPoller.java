package com.cafe.inventoryservice.inbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Drives the Transactional Inbox's async worker: claims a batch of PENDING messages, then processes
 * each one, isolating one message's failure from the rest of the batch - same per-item try/catch
 * shape as order-service's {@code OrderSagaReconciliationJob} sweep. Not itself @Transactional:
 * claimBatch() and processOne()/recordFailure() are separate transactions on InboxMessageProcessor,
 * called through that bean's proxy so each gets its own transaction boundary (self-invocation
 * within one class would silently bypass @Transactional).
 *
 * <p>Implements {@link SchedulingConfigurer} instead of annotating {@link #poll()} with
 * {@code @Scheduled} so the fixed delay is genuinely sourced from {@link
 * InboxProperties#pollInterval()} - the same bound, application.yml-backed value every other {@code
 * app.inbox.*} tunable already goes through - rather than a second, separately-defaulted {@code
 * ${app.inbox.poll-interval:...}} placeholder that {@code @Scheduled} would otherwise require and
 * that could drift out of sync with it. Trade-off: Spring only wraps {@code @Scheduled}-annotated
 * methods in an observation-aware runnable, so a task registered this way - via {@link
 * ScheduledTaskRegistrar#addFixedDelayTask} - never produces a {@code tasks.scheduled.execution}
 * observation at all, unlike a real {@code @Scheduled} method's.
 *
 * <p>Unlike order-service's {@code OutboxPoller}, this bean has no enable/disable toggle: that one
 * exists so a real {@code @SpringBootTest} with mocked collaborators can suppress order-service's
 * background poll; this module's poller tests construct {@code InboxPoller}/{@code OutboxPoller}
 * directly against mocks rather than through a Spring context, so there's no running scheduler here
 * for a toggle to suppress.
 */
@Component
public class InboxPoller implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(InboxPoller.class);

  private final InboxMessageProcessor processor;
  private final InboxProperties properties;

  public InboxPoller(InboxMessageProcessor processor, InboxProperties properties) {
    this.processor = processor;
    this.properties = properties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(this::poll, properties.pollInterval());
  }

  public void poll() {
    List<String> claimed = processor.claimBatch();
    for (String correlationId : claimed) {
      try {
        processor.processOne(correlationId);
      } catch (Exception e) {
        log.error("Inbox poller: failed to process message {}", correlationId, e);
        processor.recordFailure(correlationId, e.getMessage());
      }
    }
  }
}
