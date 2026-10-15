-- Canonical order model (FR-CAP-02). Header + normalised children; extensions are typed jsonb validated against extension_schema.
create table if not exists orders (
  id                  text primary key,
  tenant_id           text not null,
  channel             text not null,
  channel_order_id    text not null,
  order_type          text not null,
  customer_ref        text,
  customer            jsonb not null default '{}'::jsonb,
  intent              jsonb,
  provenance          jsonb,
  totals              jsonb,
  margin              jsonb,
  state_model_id      text not null,
  state_model_version int not null,
  status              text not null,
  ship_method         text,
  currency            text not null default 'USD',
  extensions          jsonb not null default '{}'::jsonb,
  placed_at           timestamptz not null,
  captured_at         timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             int not null default 1,
  primary_node_id     text,
  grand_total         numeric(14,2),
  margin_ratio        double precision,
  below_floor         boolean not null default false,
  agent_order         boolean not null default false,
  search_text         text,
  unique (tenant_id, channel, channel_order_id)
);
create index if not exists orders_tenant_status_idx on orders(tenant_id, status, placed_at desc);
create index if not exists orders_tenant_placed_idx on orders(tenant_id, placed_at desc);
create index if not exists orders_tenant_customer_idx on orders(tenant_id, customer_ref);
create index if not exists orders_tenant_node_idx on orders(tenant_id, primary_node_id);
create index if not exists orders_tenant_type_idx on orders(tenant_id, order_type, status);
create index if not exists orders_search_idx on orders using gin (to_tsvector('simple', coalesce(search_text, '')));

create table if not exists order_line (
  tenant_id            text not null,
  order_id             text not null references orders(id) on delete cascade,
  line_id              text not null,
  sku                  text not null,
  description          text,
  qty                  int not null,
  unit_price           numeric(14,2) not null,
  discount             numeric(14,2) not null default 0,
  tax                  numeric(14,2) not null default 0,
  currency             text not null default 'USD',
  fulfilment_group_id  text,
  status               text,
  attributes           jsonb not null default '{}'::jsonb,
  primary key (tenant_id, order_id, line_id)
);
create index if not exists order_line_sku_idx on order_line(tenant_id, sku);

create table if not exists order_address (
  tenant_id   text not null,
  order_id    text not null references orders(id) on delete cascade,
  type        text not null,
  address     jsonb not null,
  primary key (tenant_id, order_id, type)
);

create table if not exists order_charge (
  id          bigserial primary key,
  tenant_id   text not null,
  order_id    text not null references orders(id) on delete cascade,
  type        text not null,
  code        text,
  amount      numeric(14,2) not null,
  currency    text not null default 'USD',
  description text
);
create index if not exists order_charge_order_idx on order_charge(tenant_id, order_id);

create table if not exists order_tender (
  id          bigserial primary key,
  tenant_id   text not null,
  order_id    text not null references orders(id) on delete cascade,
  type        text not null,
  token       text,
  amount      numeric(14,2) not null,
  currency    text not null default 'USD',
  status      text not null default 'pending',
  payment_id  text,
  details     jsonb not null default '{}'::jsonb
);
create index if not exists order_tender_order_idx on order_tender(tenant_id, order_id);

create table if not exists order_note (
  id               bigserial primary key,
  tenant_id        text not null,
  order_id         text not null references orders(id) on delete cascade,
  source           text not null,
  author           text,
  text             text not null,
  at               timestamptz not null default now(),
  customer_visible boolean not null default false,
  injection_risk   double precision
);
create index if not exists order_note_order_idx on order_note(tenant_id, order_id);

create table if not exists fulfilment_group (
  id                  text primary key,
  tenant_id           text not null,
  order_id            text not null references orders(id) on delete cascade,
  node_id             text,
  policy              text,
  allocations         jsonb not null default '[]'::jsonb,
  promise_date        date,
  on_time_probability double precision,
  ship_method         text,
  estimated_cost      numeric(14,2),
  currency            text not null default 'USD',
  status              text not null default 'planned',
  sourcing_plan_id    text,
  decision_record_id  text,
  explain             jsonb not null default '{}'::jsonb,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);
create index if not exists fulfilment_group_order_idx on fulfilment_group(tenant_id, order_id);
create index if not exists fulfilment_group_node_idx on fulfilment_group(tenant_id, node_id, status);

create table if not exists extension_schema (
  tenant_id   text not null,
  entity      text not null,
  json_schema jsonb not null,
  updated_at  timestamptz not null default now(),
  primary key (tenant_id, entity)
);
