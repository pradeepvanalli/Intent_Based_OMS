-- IU-04: typed transfer recommendations (recommend only; nothing here moves stock). One OPEN row per suggested move;
-- a newer run for the SKU closes the previous rows.
create table if not exists inventory_transfer_recommendation (
  tenant_id      text not null,
  id             text not null,
  sku            text not null,
  from_node      text not null,
  to_node        text not null,
  qty            int not null check (qty > 0),
  splits_avoided int not null default 0,
  cost           numeric(12, 2) not null,
  split_savings  numeric(12, 2) not null,
  currency       text not null default 'USD',
  status         text not null default 'OPEN' check (status in ('OPEN', 'CLOSED')),
  generated_at   timestamptz not null,
  closed_at      timestamptz,
  primary key (tenant_id, id)
);
create index if not exists inventory_transfer_recommendation_open_idx on inventory_transfer_recommendation(tenant_id, sku) where status = 'OPEN';

-- IU-06: each backorder resolution linked to the backorder_next_action decision that chose what to do with the line.
alter table inventory_backorder_resolution add column if not exists decision_record_id text;
alter table inventory_backorder_resolution add column if not exists chosen_option text;
