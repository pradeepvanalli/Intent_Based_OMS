-- State machine: versioned models, per-entity quantity-level status, holds, SLA timers (FR-SM-01..11)

create table if not exists state_model (
  tenant_id      text not null,
  model_id       text not null,
  version        int not null,
  entity         text not null,
  yaml           text not null,
  parsed         jsonb not null,
  published_at   timestamptz not null default now(),
  published_by   text not null,
  reason         text not null,
  primary key (tenant_id, model_id, version)
);
create index if not exists state_model_tenant_entity_idx on state_model(tenant_id, entity);

-- One row per entity; state = dominant state (non-terminal with most quantity, else terminal with most quantity).
-- parent_id is the "<orderId>" part of a "<orderId>:<lineId>" id for *_line entity types (header roll-up, FR-SM-05).
create table if not exists entity_state (
  tenant_id      text not null,
  entity_type    text not null,
  entity_id      text not null,
  parent_id      text,
  model_id       text not null,
  model_version  int not null,
  state          text not null,
  attributes     jsonb not null default '{}'::jsonb,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  primary key (tenant_id, entity_type, entity_id)
);
create index if not exists entity_state_model_idx on entity_state(tenant_id, model_id, model_version);
create index if not exists entity_state_type_state_idx on entity_state(tenant_id, entity_type, state);
create index if not exists entity_state_parent_idx on entity_state(tenant_id, entity_type, parent_id) where parent_id is not null;

-- Quantity buckets (FR-SM-02): 10 units may be 4 shipped / 3 released / 3 backordered. Rows exist only while qty > 0.
create table if not exists entity_state_qty (
  tenant_id      text not null,
  entity_type    text not null,
  entity_id      text not null,
  state          text not null,
  qty            int not null check (qty > 0),
  since          timestamptz not null default now(),
  primary key (tenant_id, entity_type, entity_id, state)
);

-- Holds overlay (FR-SM-07)
create table if not exists hold (
  id             text primary key,
  tenant_id      text not null,
  type           text not null,
  scope_type     text not null,
  scope_id       text not null,
  blocks         jsonb not null default '[]'::jsonb,
  reason_code    text,
  note           text,
  expires_at     timestamptz,
  placed_by      text not null,
  placed_at      timestamptz not null default now(),
  resolved_by    text,
  resolved_at    timestamptz,
  resolution     text
);
create index if not exists hold_scope_idx on hold(tenant_id, scope_type, scope_id);
create index if not exists hold_active_scope_idx on hold(tenant_id, scope_type, scope_id) where resolved_at is null;
create index if not exists hold_expiry_idx on hold(expires_at) where resolved_at is null and expires_at is not null;

-- SLA timers (FR-SM-08): one open timer per (entity, state) while quantity sits in a state with an SLA.
create table if not exists sla_timer (
  id             text primary key,
  tenant_id      text not null,
  entity_type    text not null,
  entity_id      text not null,
  state          text not null,
  entered_at     timestamptz not null,
  due_at         timestamptz not null,
  on_breach      text not null,
  fired_at       timestamptz,
  cancelled_at   timestamptz
);
create index if not exists sla_timer_entity_idx on sla_timer(tenant_id, entity_type, entity_id);
create index if not exists sla_timer_due_idx on sla_timer(due_at) where fired_at is null and cancelled_at is null;
