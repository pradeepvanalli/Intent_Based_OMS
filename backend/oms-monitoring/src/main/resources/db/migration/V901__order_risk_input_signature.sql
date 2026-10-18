-- Breach-risk scoring re-decides an order only when its material inputs change (or after
-- oms.monitoring.risk-rescore-minutes). input_signature is a hash of the bucketed inputs last decided on; checked_at
-- orders the sweep so a skipped order goes to the back of the queue like a re-scored one.
alter table order_risk add column if not exists input_signature text;
alter table order_risk add column if not exists checked_at timestamptz;
