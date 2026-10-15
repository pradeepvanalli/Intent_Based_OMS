-- Platform: tenancy, security, ledger, outbox, audit, config, webhooks, idempotency

create table if not exists tenant (
  tenant_id      text primary key,
  name           text not null,
  status         text not null default 'active',
  settings       jsonb not null default '{}'::jsonb,
  created_at     timestamptz not null default now()
);

create table if not exists tenant_key (
  tenant_id      text not null references tenant(tenant_id),
  purpose        text not null,
  key_material   text not null,
  active         boolean not null default true,
  created_at     timestamptz not null default now(),
  retired_at     timestamptz,
  primary key (tenant_id, purpose, created_at)
);

create table if not exists api_key (
  key_id         text primary key,
  tenant_id      text not null references tenant(tenant_id),
  key_hash       text not null,
  principal_type text not null,
  subject_id     text not null,
  roles          jsonb not null default '[]'::jsonb,
  permissions    jsonb not null default '[]'::jsonb,
  trust_tier     int not null default 1,
  label          text,
  created_at     timestamptz not null default now(),
  revoked_at     timestamptz
);
create index if not exists api_key_tenant_idx on api_key(tenant_id);

create table if not exists app_user (
  tenant_id      text not null references tenant(tenant_id),
  user_id        text not null,
  display_name   text,
  password_hash  text,
  roles          jsonb not null default '[]'::jsonb,
  active         boolean not null default true,
  created_at     timestamptz not null default now(),
  primary key (tenant_id, user_id)
);

create table if not exists tenant_config (
  tenant_id      text not null references tenant(tenant_id),
  key            text not null,
  value          jsonb not null,
  updated_at     timestamptz not null default now(),
  primary key (tenant_id, key)
);

-- Event ledger -------------------------------------------------------------
create table if not exists aggregate_version (
  tenant_id      text not null,
  aggregate_type text not null,
  aggregate_id   text not null,
  version        bigint not null default 0,
  updated_at     timestamptz not null default now(),
  primary key (tenant_id, aggregate_type, aggregate_id)
);

create table if not exists event (
  position       bigserial primary key,
  event_id       text not null unique,
  tenant_id      text not null,
  aggregate_type text not null,
  aggregate_id   text not null,
  sequence       bigint not null,
  type           text not null,
  payload        jsonb not null default '{}'::jsonb,
  actor          text not null,
  causation_id   text,
  correlation_id text,
  metadata       jsonb not null default '{}'::jsonb,
  occurred_at    timestamptz not null default now(),
  unique (tenant_id, aggregate_type, aggregate_id, sequence)
);
create index if not exists event_tenant_type_time_idx on event(tenant_id, type, occurred_at);
create index if not exists event_tenant_agg_time_idx on event(tenant_id, aggregate_type, occurred_at);
create index if not exists event_correlation_idx on event(tenant_id, correlation_id);

-- The ledger is append-only: reject updates and deletes at the database level.
create or replace function reject_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'table % is append-only', tg_table_name;
end $$;
drop trigger if exists event_append_only on event;
create trigger event_append_only before update or delete on event for each row execute function reject_mutation();

create table if not exists outbox (
  position        bigserial primary key,
  event_id        text not null unique,
  tenant_id       text not null,
  aggregate_type  text not null,
  aggregate_id    text not null,
  type            text not null,
  cloud_event     jsonb not null,
  attempts        int not null default 0,
  next_attempt_at timestamptz not null default now(),
  last_error      text,
  created_at      timestamptz not null default now(),
  published_at    timestamptz
);
create index if not exists outbox_pending_idx on outbox(next_attempt_at) where published_at is null;

-- Audit --------------------------------------------------------------------
create table if not exists audit_log (
  id             bigserial primary key,
  tenant_id      text not null,
  actor          text not null,
  action         text not null,
  object_type    text not null,
  object_id      text,
  reason         text,
  details        jsonb not null default '{}'::jsonb,
  at             timestamptz not null default now()
);
create index if not exists audit_tenant_obj_idx on audit_log(tenant_id, object_type, object_id);
drop trigger if exists audit_append_only on audit_log;
create trigger audit_append_only before update or delete on audit_log for each row execute function reject_mutation();

-- Webhooks -----------------------------------------------------------------
create table if not exists webhook_subscription (
  id             text primary key,
  tenant_id      text not null references tenant(tenant_id),
  url            text not null,
  secret         text not null,
  event_filter   jsonb not null default '["*"]'::jsonb,
  active         boolean not null default true,
  created_at     timestamptz not null default now()
);
create table if not exists webhook_delivery (
  id              bigserial primary key,
  subscription_id text not null,
  event_id        text not null,
  status          text not null,
  error           text,
  delivered_at    timestamptz,
  created_at      timestamptz not null default now()
);

-- Idempotency (FR-CAP-04 and any mutating API) -------------------------------
create table if not exists idempotency_key (
  tenant_id      text not null,
  scope          text not null,
  key            text not null,
  request_hash   text not null,
  response       jsonb,
  status_code    int,
  created_at     timestamptz not null default now(),
  primary key (tenant_id, scope, key)
);
