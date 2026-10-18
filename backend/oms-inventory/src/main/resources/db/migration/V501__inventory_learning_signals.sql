-- Inventory signals for the self-optimizing layer (SOO IU-01..IU-06).

-- IU-03 stock age: when the oldest unit of an on-hand position arrived. Set when on-hand goes from zero to positive
-- (or from a feed's explicit receivedAt), kept while stock remains, cleared when it reaches zero.
alter table inventory_position add column if not exists received_at timestamptz;
-- IU-05: which supplier an inbound (po / asn / in_transit) row comes from, so ATP can weight it by reliability.
alter table inventory_position add column if not exists supplier_id text;
update inventory_position set received_at = as_of
 where received_at is null and supply_type = 'on_hand' and demand_type = '' and qty > 0;

-- IU-02: when a cart reservation became an order, so time-to-convert curves can be learned.
alter table inventory_reservation add column if not exists converted_at timestamptz;
create index if not exists inventory_reservation_created_idx on inventory_reservation(tenant_id, created_at);

-- IU-05: supplier / ASN receipts (expected vs received), fed by integrations.
create table if not exists inventory_inbound_receipt (
  id            text primary key,
  tenant_id     text not null,
  supplier_id   text not null,
  asn_id        text,
  sku           text not null,
  node_id       text not null,
  expected_qty  int not null check (expected_qty >= 0),
  received_qty  int not null check (received_qty >= 0),
  expected_at   timestamptz not null,
  received_at   timestamptz not null,
  created_at    timestamptz not null default now()
);
create index if not exists inventory_inbound_receipt_supplier_idx on inventory_inbound_receipt(tenant_id, supplier_id, received_at desc);
create unique index if not exists inventory_inbound_receipt_asn_uq on inventory_inbound_receipt(tenant_id, supplier_id, asn_id, sku, node_id) where asn_id is not null;

-- IU-03: one inventory.stranded signal per aged position (sku, node, received_at), never repeated.
create table if not exists inventory_stranded_signal (
  tenant_id    text not null,
  sku          text not null,
  node_id      text not null,
  received_at  timestamptz not null,
  qty          int not null,
  days_aged    int not null,
  order_id     text,
  emitted_at   timestamptz not null default now(),
  primary key (tenant_id, sku, node_id, received_at)
);

-- IU-06: how backorders resolved, per sku (converted = later released or fulfilled, cancelled = cancelled).
create table if not exists inventory_backorder_stat (
  tenant_id   text not null,
  sku         text not null,
  converted   int not null default 0,
  cancelled   int not null default 0,
  updated_at  timestamptz not null default now(),
  primary key (tenant_id, sku)
);
-- Idempotency for backorder resolutions: one count per resolving ledger event.
create table if not exists inventory_backorder_resolution (
  tenant_id   text not null,
  event_id    text not null,
  order_id    text not null,
  line_id     text,
  sku         text,
  outcome     text not null check (outcome in ('converted', 'cancelled')),
  resolved_at timestamptz not null default now(),
  primary key (tenant_id, event_id)
);
