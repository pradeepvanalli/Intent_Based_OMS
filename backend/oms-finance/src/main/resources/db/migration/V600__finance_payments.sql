-- Finance: payments and refunds (FR-FIN-01, FR-FIN-06, FR-RET-04)

create table if not exists payment (
  id                text primary key,
  tenant_id         text not null,
  order_id          text not null,
  tender_type       text not null,                 -- card | paypal | gift_card | net_terms | agent_token
  tender_token      text not null,                 -- tokenised reference; never a PAN
  psp               text not null,                 -- gateway name (mock, stripe)
  psp_ref           text,                          -- PSP authorisation reference
  status            text not null,                 -- AUTHORIZED | REAUTHORIZED | CAPTURED | PARTIALLY_CAPTURED | VOIDED | FAILED
  amount_authorized numeric(14,2) not null,
  amount_captured   numeric(14,2) not null default 0,
  currency          text not null,
  auth_expires_at   timestamptz,
  agent_mandate     jsonb,                         -- FR-FIN-06 delegated payment mandate kept as dispute evidence
  failure_code      text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create index if not exists payment_tenant_order_idx on payment(tenant_id, order_id, created_at);
create index if not exists payment_tenant_expiry_idx on payment(tenant_id, auth_expires_at) where status in ('AUTHORIZED','REAUTHORIZED','PARTIALLY_CAPTURED');

create table if not exists refund (
  id            text primary key,
  tenant_id     text not null,
  order_id      text not null,
  payment_id    text not null,
  return_id     text,
  amount        numeric(14,2) not null,
  currency      text not null,
  reason        text,
  status        text not null,                     -- PENDING | SUCCEEDED | FAILED
  psp_ref       text,
  failure_code  text,
  requested_by  text not null,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index if not exists refund_tenant_order_idx on refund(tenant_id, order_id, created_at);
create index if not exists refund_tenant_payment_idx on refund(tenant_id, payment_id);
