-- Simulation module (FR-SIM-02, FR-SIM-03): asynchronous batch simulation jobs over a date range. The request is kept
-- verbatim so a job can be re-run; results are committed progressively while the job runs so the UI can poll progress.
create table if not exists simulation_job (
  id               text primary key,
  tenant_id        text not null,
  kind             text not null default 'batch',          -- batch | node_closure | carrier_outage | tariff_change
  label            text,
  request          jsonb not null,                         -- BatchRequest as submitted (from, to, overrides, sampleLimit)
  status           text not null,                          -- QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED
  progress         double precision not null default 0,    -- 0..1
  results          jsonb,                                  -- baseline / simulated / delta / perOrderSample (partial while RUNNING)
  error            text,
  cancel_requested boolean not null default false,
  submitted_by     text not null,
  created_at       timestamptz not null default now(),
  started_at       timestamptz,
  finished_at      timestamptz
);
create index if not exists simulation_job_tenant_created_idx on simulation_job(tenant_id, created_at desc);
create index if not exists simulation_job_tenant_status_idx on simulation_job(tenant_id, status);
