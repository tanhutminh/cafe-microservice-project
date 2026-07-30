-- Tracks how many times the reconciliation job has re-published the reserve-stock
-- command for a saga stuck without a reply, so it can give up and compensate after
-- app.saga-reconciliation.max-retries attempts instead of retrying forever.
ALTER TABLE order_saga_state ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
