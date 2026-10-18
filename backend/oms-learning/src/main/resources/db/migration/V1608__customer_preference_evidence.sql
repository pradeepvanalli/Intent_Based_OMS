-- Customer intelligence evidence (SOO CI-01, CI-03, CI-04).

-- Decayed evidence weight per (customer or cohort segment, fact key, option). Binary facts use options 'pos' / 'neg';
-- PREFERRED_CONTACT_CHANNEL uses the channel name. A weight is decayed by the half-life every time it is touched, so the
-- stored value is always "evidence as of updated_at". Cohort segments ('segment' rows, id channel|basket|region) carry
-- the same observations and serve as the cold-start prior for customers with little history.
create table if not exists customer_preference_evidence (
  tenant_id     text not null,
  subject_kind  text not null check (subject_kind in ('customer', 'segment')),
  subject_id    text not null,
  fact_key      text not null,
  option        text not null,
  weight        double precision not null default 0,
  updated_at    timestamptz not null default now(),
  primary key (tenant_id, subject_kind, subject_id, fact_key, option)
);
create index if not exists customer_preference_evidence_stale_idx on customer_preference_evidence(tenant_id, subject_kind, updated_at);

-- Which customer and cohort an order belongs to (from order.captured), so later events that carry only an order id can
-- be attributed, plus what has happened to the order that matters for split tolerance.
create table if not exists customer_order_ref (
  tenant_id       text not null,
  order_id        text not null,
  customer_ref    text not null,
  cohort          text not null,
  shipments       int not null default 0,
  max_packages    int not null default 0,
  complained      boolean not null default false,
  split_evidence  text,                       -- null until split tolerance evidence was taken: 'pos' or 'neg'
  created_at      timestamptz not null default now(),
  primary key (tenant_id, order_id)
);
create index if not exists customer_order_ref_customer_idx on customer_order_ref(tenant_id, customer_ref);

-- One piece of evidence per (ledger event, fact key): a redelivered event changes nothing.
create table if not exists customer_evidence_event (
  tenant_id  text not null,
  event_id   text not null,
  fact_key   text not null,
  primary key (tenant_id, event_id, fact_key)
);
