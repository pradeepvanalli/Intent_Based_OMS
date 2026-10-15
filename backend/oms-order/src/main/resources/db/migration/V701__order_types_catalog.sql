-- Order types (FR-CAP-03) and the SKU catalogue used to enrich captured lines (FR-CAP-05 item sanity, compliance, P&L).
create table if not exists order_type (
  tenant_id   text not null,
  name        text not null,
  definition  jsonb not null,
  updated_at  timestamptz not null default now(),
  primary key (tenant_id, name)
);

create table if not exists catalog_item (
  tenant_id   text not null,
  sku         text not null,
  name        text,
  attributes  jsonb not null default '{}'::jsonb,
  updated_at  timestamptz not null default now(),
  primary key (tenant_id, sku)
);
create index if not exists catalog_item_category_idx on catalog_item(tenant_id, (attributes->>'category'));
