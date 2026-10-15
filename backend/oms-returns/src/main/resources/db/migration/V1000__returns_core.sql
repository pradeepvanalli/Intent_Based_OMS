-- Returns: RMA header, lines and store credit (FR-RET-01..07). Status mirrors the dominant state of the
-- return.standard state machine; risk, timing and disposition carry the decision record ids that produced them (FR-XAI-01).

create table if not exists return_rma (
  rma_id                            text primary key,                 -- rma_<shortId>
  tenant_id                         text not null,
  order_id                          text not null,
  channel                           text not null,                    -- web | store | agent | cs | ucp
  customer_ref                      text,
  customer_email_token              text,                             -- tokenised e-mail for cross-channel identity resolution (FR-RET-07, GR-09)
  status                            text not null,                    -- requested | approved | awaiting_receipt | received | inspected | refunded | rejected | cancelled | keep_it_refunded
  reason_code                       text,                             -- normalised taxonomy key (return_reason, T0)
  customer_reason_text              text,
  condition                         text,                             -- new | like_new | damaged | missing_parts | wrong_item
  refund_timing                     text,                             -- instant | on_carrier_scan | on_inspection | store_credit_only | escalate
  risk_score                        double precision,
  risk_level                        text,                             -- low | medium | high | severe
  reason_codes                      jsonb not null default '[]',      -- [{code, probability}] from the reason-code Nouls (FR-RET-03)
  risk_decision_record_id           text,
  refund_timing_decision_record_id  text,
  reason_decision_record_id         text,
  disposition                       text,                             -- restock | refurbish | liquidate | return_to_vendor | donate | destroy
  disposition_decision_record_id    text,
  eligibility                       jsonb,                            -- {eligible, reasons[], windowDays, daysSinceDelivery, finalSale}
  keep_it                           jsonb,                            -- {offered, reason, returnCostUsd, recoveryValueUsd}
  refund                            jsonb,                            -- {amount, currency, restockingFee, legs[{refundId, paymentId, amount, status}], status}
  receipt_present                   boolean not null default true,
  store_node_id                     text,
  agent_id                          text,
  mandate_id                        text,
  review_task_id                    text,                             -- open human task gating this RMA (return_denial | return_review | refund_approval)
  review_task_type                  text,
  scanned_at                        timestamptz,
  received_at                       timestamptz,
  received_node_id                  text,
  inspected_at                      timestamptz,
  inspection_note                   text,
  refunded_at                       timestamptz,
  created_by                        text not null,
  created_at                        timestamptz not null default now(),
  updated_at                        timestamptz not null default now()
);
create index if not exists return_rma_tenant_order_idx    on return_rma(tenant_id, order_id);
create index if not exists return_rma_tenant_status_idx   on return_rma(tenant_id, status, created_at desc);
create index if not exists return_rma_tenant_customer_idx on return_rma(tenant_id, customer_ref, created_at desc);
create index if not exists return_rma_tenant_email_idx    on return_rma(tenant_id, customer_email_token, created_at desc) where customer_email_token is not null;
create index if not exists return_rma_tenant_created_idx  on return_rma(tenant_id, created_at desc);

create table if not exists return_line (
  id            bigserial primary key,
  tenant_id     text not null,
  rma_id        text not null references return_rma(rma_id) on delete cascade,
  line_id       text not null,
  sku           text not null,
  description   text,
  qty           integer not null check (qty > 0),
  unit_price    numeric(14,2) not null,
  tax           numeric(14,2) not null default 0,              -- tax share of the returned quantity (FR-RET-04)
  currency      text not null,
  reason_code   text,
  customer_reason_text text,
  condition     text,
  disposition   text,
  attributes    jsonb not null default '{}',
  unique (tenant_id, rma_id, line_id)
);
create index if not exists return_line_tenant_rma_idx on return_line(tenant_id, rma_id);

-- Store credit issued instead of money back (refund timing store_credit_only, FR-RET-02/04).
create table if not exists store_credit (
  id            text primary key,                              -- sc_<shortId>
  tenant_id     text not null,
  customer_ref  text,
  amount        numeric(14,2) not null,
  currency      text not null,
  rma_id        text,
  order_id      text,
  reason        text,
  issued_by     text not null,
  created_at    timestamptz not null default now()
);
create index if not exists store_credit_tenant_customer_idx on store_credit(tenant_id, customer_ref, created_at desc);
create index if not exists store_credit_tenant_rma_idx on store_credit(tenant_id, rma_id);
