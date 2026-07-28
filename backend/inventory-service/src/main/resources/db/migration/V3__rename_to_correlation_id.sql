-- Renames saga_attempt_id to correlation_id to match the Kafka header name
-- (KafkaHeaders.CORRELATION_ID) it's now sourced from, and to name the concept consistently:
-- this is the Correlation Identifier pattern, not a custom "attempt id".
ALTER TABLE processed_saga_steps RENAME COLUMN saga_attempt_id TO correlation_id;
