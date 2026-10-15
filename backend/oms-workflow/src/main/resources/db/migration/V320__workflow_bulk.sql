-- Bulk operations (FR-WF-07): throttled, dry-runnable, roll-backable actions over a set of subjects.

create table if not exists bulk_operation (
  id             text primary key,
  tenant_id      text not null,
  action         text not null,
  params         jsonb not null default '{}'::jsonb,
  subject_ids    jsonb not null default '[]'::jsonb,
  dry_run        boolean not null default false,
  per_second     int not null default 0,              -- 0 = unthrottled
  status         text not null,                       -- QUEUED, RUNNING, COMPLETED, FAILED, ROLLING_BACK, ROLLED_BACK
  total          int not null default 0,
  done           int not null default 0,
  failed         int not null default 0,
  results        jsonb not null default '[]'::jsonb,  -- [{subjectId, status, applied, error, rollback}]
  error          text,
  submitted_by   text,
  created_at     timestamptz not null,
  started_at     timestamptz,
  finished_at    timestamptz,
  rolled_back_at timestamptz
);
create index if not exists bulk_operation_tenant_idx on bulk_operation(tenant_id, created_at desc);
