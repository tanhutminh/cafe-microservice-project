-- Distributed tracing: carries the W3C traceparent string across two poller-thread gaps, the
-- same way correlation_id already carries saga identity across them. On inbox_messages,
-- StockReservationListener captures the live span from the Kafka consumer thread when
-- persisting each row; InboxMessageProcessor.processOne() restores it into a child span since
-- the poller thread that actually runs business logic has no live trace context of its own. On
-- outbox_messages, that same processOne() span is captured again when queuing the reply, so
-- OutboxMessagePublisher's poller thread can restore it too - closing the loop back to
-- order-service. NULL on either table when there was no live parent span to capture; the
-- restoring side then just starts a fresh root span instead of erroring.
ALTER TABLE inbox_messages ADD COLUMN traceparent VARCHAR(64);
ALTER TABLE outbox_messages ADD COLUMN traceparent VARCHAR(64);
