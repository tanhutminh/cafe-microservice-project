-- Transactional Inbox pattern (M4): the async mirror of Transactional Outbox on the consumer
-- side. Replaces processed_saga_steps' synchronous Idempotent Consumer: the Kafka listener
-- now only persists the incoming saga command here (status PENDING) and acks - it never runs
-- business logic inline. A separate poller later claims PENDING rows
-- (SELECT ... FOR UPDATE SKIP LOCKED) and executes the actual reserve/commit/release step in
-- its own transaction, atomically with the status transition to PROCESSED/FAILED. correlation_id
-- (the Kafka Correlation Identifier header) is the natural idempotency key: an insert only
-- happens if the row doesn't already exist yet, so a redelivered command is either dropped
-- (still pending/being processed) or answered by resending the stored outcome (already
-- processed) - see StockReservationListener and InboxMessageProcessor.
CREATE TABLE inbox_messages (
    correlation_id VARCHAR(64)  PRIMARY KEY,
    order_id       BIGINT       NOT NULL,
    message_type   VARCHAR(30)  NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    result_success BOOLEAN,
    result_reason  VARCHAR(500),
    attempt_count  INT          NOT NULL DEFAULT 0,
    error_reason   VARCHAR(500),
    received_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ
);

-- Poller polls "next PENDING batch ordered by receipt time" - this is that query's access path.
CREATE INDEX idx_inbox_messages_status_received_at ON inbox_messages (status, received_at);

DROP TABLE processed_saga_steps;
