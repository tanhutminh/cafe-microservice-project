-- Fixes a saga idempotency bug: keying processed_saga_steps by order_id alone meant retrying
-- checkout on an order that previously failed replayed the stale outcome instead of
-- re-evaluating stock against the (possibly changed) cart/inventory. saga_attempt_id is a
-- fresh id per checkout attempt (order-service, order_saga_state.saga_attempt_id) — a
-- redelivered Kafka command still dedupes correctly, but a new attempt is evaluated fresh.
ALTER TABLE processed_saga_steps DROP CONSTRAINT processed_saga_steps_pkey;
ALTER TABLE processed_saga_steps ADD COLUMN saga_attempt_id VARCHAR(64);
UPDATE processed_saga_steps SET saga_attempt_id = 'legacy-' || order_id WHERE saga_attempt_id IS NULL;
ALTER TABLE processed_saga_steps ALTER COLUMN saga_attempt_id SET NOT NULL;
ALTER TABLE processed_saga_steps ADD PRIMARY KEY (saga_attempt_id);
CREATE INDEX idx_processed_saga_steps_order_id ON processed_saga_steps (order_id);
