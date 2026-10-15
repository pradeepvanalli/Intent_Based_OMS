-- Finance: tariff tables with effective dates for landed cost (FR-FIN-05)

create table if not exists tariff_rate (
  id              text primary key,
  tenant_id       text not null,
  origin_country  text not null,                   -- ISO 3166-1 alpha-2, or '*' for any origin
  dest_country    text not null,                   -- ISO 3166-1 alpha-2, or '*' for any destination
  hs_code_prefix  text not null default '',        -- longest matching prefix wins; '' matches every HS code
  duty_rate       numeric(9,6) not null default 0, -- fraction of customs value, e.g. 0.065
  brokerage_fee   numeric(14,2) not null default 0,-- flat fee per shipment in the order currency
  surcharge_rate  numeric(9,6) not null default 0, -- fraction of customs value (MPF, HMF, carrier surcharges)
  effective_from  timestamptz not null,
  effective_to    timestamptz,                     -- null = open-ended
  note            text,
  updated_at      timestamptz not null default now()
);
create index if not exists tariff_rate_lookup_idx on tariff_rate(tenant_id, origin_country, dest_country, effective_from);
