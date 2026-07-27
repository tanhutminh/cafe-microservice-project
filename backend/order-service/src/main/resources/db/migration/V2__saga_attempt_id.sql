-- Fixes a saga idempotency bug: processed_saga_steps (inventory_db) was keyed by order_id
-- alone, so retrying checkout on an order that previously failed replayed the stale outcome
-- instead of re-evaluating stock. sagaAttemptId is a fresh id per checkout attempt, letting
-- inventory-service dedupe true Kafka redeliveries without conflating them with genuine retries.
ALTER TABLE order_saga_state ADD COLUMN saga_attempt_id VARCHAR(64);
UPDATE order_saga_state SET saga_attempt_id = 'legacy-' || order_id WHERE saga_attempt_id IS NULL;
ALTER TABLE order_saga_state ALTER COLUMN saga_attempt_id SET NOT NULL;
