-- Distributed tracing: carries the W3C traceparent string across OutboxMessagePublisher's
-- poller-thread gap, the same way correlation_id already carries saga identity across it.
-- OrderCheckoutSaga.enqueue() captures the live span at business-logic time; publishOne()
-- restores it into a child span before sending, since the poller thread that actually calls
-- KafkaTemplate.send() has no live trace context of its own. NULL when the row was enqueued
-- with no live parent span (e.g. OrderSagaReconciliationJob's scheduler-thread retries) -
-- publishOne() then just starts a fresh root span instead of erroring.
ALTER TABLE outbox_messages ADD COLUMN traceparent VARCHAR(64);
