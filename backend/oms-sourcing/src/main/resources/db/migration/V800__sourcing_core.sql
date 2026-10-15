-- Sourcing module (FR-SRC-01..08): fulfilment nodes, learned penalties, the tenant's carrier rate card and every plan
-- considered for an order (chosen or not) so replay and analytics can see the alternatives.

create table if not exists node (
  tenant_id   text not null,
  id          text not null,
  type        text not null,
  active      boolean not null default true,
  definition  jsonb not null,              -- Node record: lat/lon/state/country/zip/capabilities/dailyCapacity/cutoff/timezone/health/costProfile/landedCost
  updated_at  timestamptz not null default now(),
  primary key (tenant_id, id)
);
create index if not exists node_tenant_active_idx on node(tenant_id, active, type);

-- Learned penalty per node (sku = '*' for the node-level row) with exponential decay: effective = penalty * 0.5^(days/7).
create table if not exists node_penalty (
  tenant_id   text not null,
  node_id     text not null,
  sku         text not null default '*',
  penalty     double precision not null,
  reason      text,
  updated_at  timestamptz not null default now(),
  primary key (tenant_id, node_id, sku)
);
create index if not exists node_penalty_node_idx on node_penalty(tenant_id, node_id);

create table if not exists shipping_rate_card (
  tenant_id   text primary key,
  definition  jsonb not null,              -- services[] (zones x weight bands), splitPenaltyUsd, onTimeByTransitDays
  updated_at  timestamptz not null default now()
);

create table if not exists sourcing_plan (
  id          text primary key,
  tenant_id   text not null,
  order_id    text not null,
  policy      text not null,
  plan        jsonb not null,
  score       double precision not null default 0,
  chosen      boolean not null default false,
  created_at  timestamptz not null default now()
);
create index if not exists sourcing_plan_order_idx on sourcing_plan(tenant_id, order_id, created_at desc);
create index if not exists sourcing_plan_policy_idx on sourcing_plan(tenant_id, policy, chosen, created_at desc);
