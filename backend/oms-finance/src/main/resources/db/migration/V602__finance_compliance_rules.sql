-- Finance: L0 compliance rules (FR-FIN-03, FR-CAP-05)

create table if not exists compliance_rule (
  tenant_id    text not null,
  id           text not null,
  name         text not null,
  scope        text not null default 'line',       -- line | order
  when_expr    text not null,                      -- CEL over roots line, order, tenant
  action       text not null,                      -- block | hold | require_signature
  reason_code  text not null,
  severity     text not null default 'error',      -- error | warning
  message      text,
  priority     int not null default 100,
  active       boolean not null default true,
  updated_at   timestamptz not null default now(),
  primary key (tenant_id, id)
);
create index if not exists compliance_rule_tenant_active_idx on compliance_rule(tenant_id, active, priority);
