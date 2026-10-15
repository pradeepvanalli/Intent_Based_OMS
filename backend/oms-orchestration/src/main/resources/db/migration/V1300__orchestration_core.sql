-- oms-orchestration: shipments, mock WMS releases, customer communications (ARCHITECTURE §3.13, FR-CS-01, FR-WF-05).

create table if not exists shipment (
  id                  text primary key,
  tenant_id           text not null,
  order_id            text not null,
  fulfilment_group_id text,
  node_id             text,
  carrier             text,
  service             text,
  tracking            text,
  status              text not null default 'created',
  lines               jsonb not null default '[]'::jsonb,
  shipped_at          timestamptz,
  delivered_at        timestamptz,
  eta                 timestamptz,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);
create index if not exists shipment_order_idx on shipment(tenant_id, order_id);
create index if not exists shipment_tracking_idx on shipment(tenant_id, tracking);

-- Releases handed to the (mock) WMS; one row per fulfilment group release (FR-WF-05, mock integration).
create table if not exists wms_release (
  id                  text primary key,
  tenant_id           text not null,
  order_id            text not null,
  fulfilment_group_id text not null,
  node_id             text,
  lines               jsonb not null default '[]'::jsonb,
  status              text not null default 'sent',
  sent_at             timestamptz not null default now(),
  cancelled_at        timestamptz,
  unique (tenant_id, fulfilment_group_id)
);
create index if not exists wms_release_order_idx on wms_release(tenant_id, order_id);

-- Customer communications shown on the order view (FR-CS-01) and produced by playbooks (FR-MON-04).
create table if not exists customer_communication (
  id         text primary key,
  tenant_id  text not null,
  order_id   text,
  channel    text not null,
  direction  text not null default 'outbound',
  template   text,
  subject    text,
  summary    text,
  sent_by    text,
  at         timestamptz not null default now()
);
create index if not exists customer_communication_order_idx on customer_communication(tenant_id, order_id, at);
