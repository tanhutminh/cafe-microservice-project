-- Tracks how many times the reconciliation job has re-enqueued the stuck-leg command
-- (reserve-stock or commit-stock) for a saga with no reply, so it can give up and
-- compensate after app.saga-reconciliation.max-retries attempts instead of retrying
-- forever.
ALTER TABLE order_saga_state ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
