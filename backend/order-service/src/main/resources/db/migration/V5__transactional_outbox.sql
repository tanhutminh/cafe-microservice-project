-- Transactional Outbox pattern: the send-side mirror of inventory-service's Transactional
-- Inbox (see inventory-service's V6__transactional_inbox.sql). OrderSaga writes a row
-- here in the same transaction as the order/saga-state change it belongs to, instead of calling
-- KafkaTemplate directly - closing the dual-write gap where a crash between the local commit and
-- the live Kafka send could leave a saga stuck with no command ever sent, undetected by
-- OrderSagaReconciliationJob. A separate poller later claims PENDING rows
-- (SELECT ... FOR UPDATE SKIP LOCKED) and relays them to Kafka in its own transaction, marking
-- PUBLISHED only once the broker has acknowledged the record - see OutboxMessagePublisher.
CREATE TABLE outbox_messages (
    id             BIGSERIAL    PRIMARY KEY,
    order_id       BIGINT       NOT NULL REFERENCES orders (id),
    message_type   VARCHAR(30)  NOT NULL,
    correlation_id VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    attempt_count  INT          NOT NULL DEFAULT 0,
    error_reason   VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- Poller polls "next PENDING batch ordered by creation time" - this is that query's access path.
CREATE INDEX idx_outbox_messages_status_created_at ON outbox_messages (status, created_at);
