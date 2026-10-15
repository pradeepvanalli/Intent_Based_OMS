-- Workflow engine (FR-WF-01, FR-WF-02, FR-WF-05, FR-WF-06, FR-WF-08): durable step machine on PostgreSQL.

-- One row per workflow instance. The row is locked (FOR UPDATE SKIP LOCKED) while a step runs, which gives
-- per-instance serialisation of steps and signals without a separate scheduler cluster.
create table if not exists workflow_instance (
  id               text primary key,
  tenant_id        text not null,
  type             text not null,
  version          int not null,
  business_key     text not null,
  status           text not null,                    -- RUNNING, WAITING_SIGNAL, WAITING_TIMER, WAITING_TASK, COMPENSATING, COMPLETED, FAILED, COMPENSATED, CANCELLED
  current_step     text not null,
  vars             jsonb not null default '{}'::jsonb,
  started_at       timestamptz not null,
  updated_at       timestamptz not null,
  next_wake_at     timestamptz,                      -- when a worker should pick the instance up next (null = parked)
  timeout_at       timestamptz,                      -- deadline of the current wait (signal/task timeout or sleep end)
  wait_signals     jsonb not null default '[]'::jsonb,
  wait_task_id     text,
  on_timeout_step  text,
  on_signal_step   text,
  on_task_step     text,
  last_error       text,
  attempt          int not null default 0,
  compensations    jsonb not null default '[]'::jsonb,
  completed_at     timestamptz,
  result           jsonb,
  cancel_reason    text,
  unique (tenant_id, type, business_key)
);
create index if not exists workflow_instance_tenant_status_wake_idx on workflow_instance(tenant_id, status, next_wake_at);
-- Partial index covering exactly the rows a worker can pick up.
create index if not exists workflow_instance_due_idx on workflow_instance(next_wake_at)
  where status in ('RUNNING','WAITING_SIGNAL','WAITING_TIMER','WAITING_TASK','COMPENSATING') and next_wake_at is not null;

-- Activity attempt log and result cache. One row per (workflow, step, name); the row is updated on every attempt
-- and a status of OK means the cached result is replayed instead of re-executing the body (FR-WF-02 idempotent replays).
create table if not exists workflow_activity (
  id            text primary key,
  workflow_id   text not null references workflow_instance(id) on delete cascade,
  step          text not null,
  name          text not null,
  attempt       int not null default 0,
  status        text not null,                       -- OK, FAILED
  result        jsonb,
  error         text,
  started_at    timestamptz not null,
  finished_at   timestamptz,
  unique (workflow_id, step, name)
);

-- Signal queue. Consumed in id order under the instance row lock (FR-WF-05 per-order ordering);
-- the unique constraint on (workflow_id, idempotency_key) deduplicates redelivered webhooks.
create table if not exists workflow_signal (
  id              bigserial primary key,
  workflow_id     text not null references workflow_instance(id) on delete cascade,
  name            text not null,
  payload         jsonb not null default '{}'::jsonb,
  idempotency_key text,
  received_at     timestamptz not null,
  consumed_at     timestamptz,
  unique (workflow_id, idempotency_key)
);
create index if not exists workflow_signal_pending_idx on workflow_signal(workflow_id, id) where consumed_at is null;

-- Per-instance timeline: steps, activities, signals, timers, tasks, logs, errors, compensations (FR-WF-06).
create table if not exists workflow_timeline (
  id            bigserial primary key,
  workflow_id   text not null references workflow_instance(id) on delete cascade,
  at            timestamptz not null,
  kind          text not null,
  name          text,
  status        text,
  details       jsonb not null default '{}'::jsonb
);
create index if not exists workflow_timeline_wf_idx on workflow_timeline(workflow_id, id);
