-- FS-01..FS-03 support tables. node_assignment correlates a later delivery/shipment outcome back to the node that
-- was actually assigned (order.sourced carries the node per package; the typed outcome catalog doesn't, since
-- oms-learning has no dependency on oms-order/oms-sourcing to resolve it any other way).
-- promised_eta/carrier/service are captured here because the later order.delivered/order.shipped events don't carry
-- them (confirmed by reading StandardShipOrderWorkflow directly) — without this, on-time/late can't be computed at
-- all, since there's nothing to compare the delivery timestamp against.
create table if not exists node_assignment (
  tenant_id     text not null,
  order_id      text not null,
  node_id       text not null,
  sku_class     text not null default 'default',
  promised_eta  date,
  carrier       text,
  shipped_at    timestamptz,
  assigned_at   timestamptz not null default now(),
  primary key (tenant_id, order_id, node_id)
);
create index if not exists node_assignment_tenant_order_idx on node_assignment(tenant_id, order_id);

-- Raw delivery samples per lane (FS-03, CX-01): carrier x origin-node x transit days. The requirements doc specifies
-- destination-postal-code granularity; that data isn't available to this module (no event payload carries it and
-- oms-learning intentionally has no oms-order dependency to look it up), so lane_key is carrier+origin-node today —
-- coarser than spec, documented, not silently wrong. Narrow it once an order.shipped/delivered payload carries the
-- destination, or this module is given a narrow read-only query into oms-order for just that field.
create table if not exists lane_delivery_sample (
  tenant_id       text not null,
  lane_key        text not null,                      -- "<carrier>|<origin_node_id>"
  transit_days    double precision not null,
  on_time         boolean not null,
  observed_at     timestamptz not null default now()
);
create index if not exists lane_delivery_sample_lane_idx on lane_delivery_sample(tenant_id, lane_key, observed_at desc);

-- Beta-binomial sufficient statistics per node x sku_class x day_part (FS-02): alpha/beta posterior parameters,
-- updated incrementally so the learner never has to replay full history to get today's estimate.
create table if not exists node_reliability_stat (
  tenant_id  text not null,
  node_id    text not null,
  sku_class  text not null default 'default',
  day_part   text not null default 'any',              -- morning | afternoon | evening | night | any
  on_time_alpha  double precision not null default 1,  -- Beta(1,1) uniform prior
  on_time_beta   double precision not null default 1,
  reject_alpha   double precision not null default 1,
  reject_beta    double precision not null default 1,
  sample_size    int not null default 0,
  updated_at     timestamptz not null default now(),
  primary key (tenant_id, node_id, sku_class, day_part)
);
