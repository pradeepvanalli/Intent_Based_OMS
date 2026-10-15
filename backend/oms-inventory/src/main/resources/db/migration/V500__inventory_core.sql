-- Inventory (FR-INV-01..05): positions, reservations, sync/reconciliation log.

-- One row per (sku, node, supply type, demand type). Supply rows carry demand_type '' (the sentinel for "none" so the
-- composite primary key works); demand rows (reserved | allocated | safety) are external demand fed by WMS/POS.
-- The primary key INCLUDEs qty and confidence so ATP aggregation is an index-only scan (NFR-01: 20k reads/s).
create table if not exists inventory_position (
  tenant_id    text not null,
  sku          text not null,
  node_id      text not null,
  supply_type  text not null check (supply_type in ('on_hand', 'po', 'asn', 'in_transit')),
  demand_type  text not null default '' check (demand_type in ('', 'reserved', 'allocated', 'safety')),
  qty          int not null default 0,
  confidence   double precision not null default 1.0 check (confidence >= 0 and confidence <= 1),
  as_of        timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  primary key (tenant_id, sku, node_id, supply_type, demand_type) include (qty, confidence)
);
-- Node-centric lookups (feeds, reconciliation by node, sourcing coverage).
create index if not exists inventory_position_node_idx on inventory_position(tenant_id, node_id, sku);

-- Reservations (FR-INV-02, NFR-06). Lifecycle: ACTIVE -> RELEASED | EXPIRED | CONSUMED. Conversion (cart -> order)
-- keeps the row ACTIVE (it still consumes ATP) and clears the TTL; consumption on shipment decrements on_hand.
create table if not exists inventory_reservation (
  id           text primary key,
  tenant_id    text not null,
  sku          text not null,
  node_id      text not null,
  qty          int not null check (qty > 0),
  source       text not null,
  ref_id       text not null,
  order_id     text,
  expires_at   timestamptz,
  status       text not null check (status in ('ACTIVE', 'RELEASED', 'EXPIRED', 'CONSUMED')),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index if not exists inventory_reservation_ref_idx on inventory_reservation(tenant_id, ref_id);
create index if not exists inventory_reservation_order_idx on inventory_reservation(tenant_id, order_id) where order_id is not null;
create index if not exists inventory_reservation_expiry_idx on inventory_reservation(tenant_id, status, expires_at);
-- Active demand per sku+node, covering qty so the ATP reservation sum never touches the heap.
create index if not exists inventory_reservation_active_idx on inventory_reservation(tenant_id, sku, node_id) include (qty) where status = 'ACTIVE';

-- Feed and reconciliation log (FR-INV-04). Append-only.
create table if not exists inventory_sync_log (
  id           text primary key,
  tenant_id    text not null,
  source       text not null,
  positions    int not null,
  received_at  timestamptz not null default now(),
  drift        jsonb
);
create index if not exists inventory_sync_log_tenant_time_idx on inventory_sync_log(tenant_id, received_at desc);
drop trigger if exists inventory_sync_log_append_only on inventory_sync_log;
create trigger inventory_sync_log_append_only before update or delete on inventory_sync_log for each row execute function reject_mutation();
