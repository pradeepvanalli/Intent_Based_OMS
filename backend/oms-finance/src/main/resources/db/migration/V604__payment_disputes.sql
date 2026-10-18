-- FD-06: chargebacks arrive by gateway reference (Stripe sends the payment intent or charge id), so payments are
-- looked up by psp_ref; and a dispute is stored once per gateway dispute id, so webhook retries change nothing.
create index if not exists payment_tenant_psp_ref_idx on payment(tenant_id, psp_ref) where psp_ref is not null;

create table if not exists payment_dispute (
  id              text primary key,
  tenant_id       text not null,
  payment_id      text not null,
  order_id        text not null,
  psp             text not null,
  psp_dispute_id  text not null,                  -- gateway dispute id (Stripe dp_...)
  amount          numeric(14,2) not null,
  currency        text not null,
  reason          text,                           -- gateway reason code (fraudulent, product_not_received, ...)
  status          text not null,                  -- OPEN | WON | LOST
  fraud           boolean not null default false, -- the reason means the cardholder did not authorise the payment
  agent_id        text,                           -- set when the order came from an AI agent mandate (FD-05)
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (tenant_id, psp, psp_dispute_id)
);
create index if not exists payment_dispute_order_idx on payment_dispute(tenant_id, order_id);
create index if not exists payment_dispute_agent_idx on payment_dispute(tenant_id, agent_id) where agent_id is not null;
