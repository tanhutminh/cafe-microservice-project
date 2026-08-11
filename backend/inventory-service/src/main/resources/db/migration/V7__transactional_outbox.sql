-- Transactional Outbox pattern: the send-side mirror of this service's own Transactional Inbox
-- (see V6__transactional_inbox.sql). InboxMessageProcessor writes a row here in the same
-- transaction as the stock mutation + inbox status transition it belongs to, instead of calling
-- KafkaTemplate directly - closing the dual-write gap where a crash between the local commit
-- and a live Kafka reply send could permanently lose the reply order-service is waiting on.
-- A separate poller later claims PENDING rows (SELECT ... FOR UPDATE SKIP LOCKED) and relays
-- them to Kafka in its own transaction, marking PUBLISHED only once the broker has acknowledged
-- the record - see OutboxMessagePublisher. No FK on order_id: like inbox_messages, this service
-- doesn't own an orders table.
CREATE TABLE outbox_messages (
    id             BIGSERIAL    PRIMARY KEY,
    order_id       BIGINT       NOT NULL,
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
