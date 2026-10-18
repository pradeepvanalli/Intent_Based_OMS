-- Fulfilment and promise learning (FS-01..FS-06, CX-01..CX-04). order.sourced now carries, per package, the
-- destination ZIP3, ship method, SKU class, node backlog at sourcing time and the on-time probability the plan stated,
-- so each delivery can be attributed to a lane (carrier x origin x ZIP3), a node x SKU class x day part, and a backlog
-- level, and compared with what was promised.
alter table node_assignment add column if not exists dest_zip3 text;
alter table node_assignment add column if not exists ship_method text;
alter table node_assignment add column if not exists day_part text;
alter table node_assignment add column if not exists backlog_units int;
alter table node_assignment add column if not exists stated_on_time double precision;
alter table node_assignment add column if not exists delivered_at timestamptz;
alter table node_assignment add column if not exists delivered_on_time boolean;
create index if not exists node_assignment_tenant_node_delivered_idx on node_assignment(tenant_id, node_id, delivered_at) where delivered_at is not null;
create index if not exists node_assignment_tenant_method_idx on node_assignment(tenant_id, ship_method, node_id) where carrier is not null;

-- CX-01: the stated on-time probability next to each lane sample, so calibration = observed rate - mean stated.
alter table lane_delivery_sample add column if not exists stated_on_time double precision;
-- One sample per (lane, order, node): a redelivered order.delivered event must not count twice.
alter table lane_delivery_sample add column if not exists order_id text;
alter table lane_delivery_sample add column if not exists node_id text;
create unique index if not exists lane_delivery_sample_order_uq on lane_delivery_sample(tenant_id, lane_key, order_id, node_id) where order_id is not null;

-- CX-02: storefront promise impressions and whether the shopper bought. One row per impression; buffer_days is the
-- padding added to the computed promise for that impression (the lever the buffer learner tunes per segment).
create table if not exists promise_conversion (
  tenant_id      text not null,
  impression_id  text not null,
  segment        text not null default 'default',
  order_id       text,
  promised_days  int not null,
  buffer_days    int not null default 0,
  converted      boolean not null,
  observed_at    timestamptz not null default now(),
  primary key (tenant_id, impression_id)
);
create index if not exists promise_conversion_segment_idx on promise_conversion(tenant_id, segment, observed_at desc);
create index if not exists promise_conversion_order_idx on promise_conversion(tenant_id, order_id) where order_id is not null;
