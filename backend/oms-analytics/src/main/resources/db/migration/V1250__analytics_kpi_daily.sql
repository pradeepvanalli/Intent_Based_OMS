-- Analytics: materialised daily KPI points (FR-ANL-01, section 13). One row per tenant, day and KPI key.
-- value = numerator / denominator for ratio KPIs (touchless, promise accuracy, ...), a count-weighted median for
-- duration KPIs (numerator = median x count, denominator = count) and a snapshot for gauges (open orders, open exceptions).
-- Refreshed nightly by KpiDailyRollup; the live dashboard reads these for days older than the live window.
create table if not exists kpi_daily (
  tenant_id    text not null,
  day          date not null,
  key          text not null,
  value        double precision not null,
  numerator    double precision not null default 0,
  denominator  double precision not null default 0,
  computed_at  timestamptz not null default now(),
  primary key (tenant_id, day, key)
);
create index if not exists kpi_daily_tenant_key_day_idx on kpi_daily(tenant_id, key, day);
