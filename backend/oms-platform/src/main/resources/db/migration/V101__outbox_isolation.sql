-- One failing subscriber must never stall the outbox. Each event is delivered to each subscriber in its own
-- transaction; subscribers that failed are remembered here and only they are retried; after the attempt limit the
-- event is dead-lettered (kept for inspection, skipped by the relay) instead of being retried forever.
alter table outbox add column if not exists pending_subscribers text[];
alter table outbox add column if not exists dead_lettered_at timestamptz;

-- Rows already parked at the old attempt limit become dead letters.
update outbox set dead_lettered_at = now() where published_at is null and dead_lettered_at is null and attempts >= 12;

drop index if exists outbox_pending_idx;
create index if not exists outbox_pending_idx on outbox(next_attempt_at, position) where published_at is null and dead_lettered_at is null;
create index if not exists outbox_dead_letter_idx on outbox(tenant_id, dead_lettered_at) where dead_lettered_at is not null;
