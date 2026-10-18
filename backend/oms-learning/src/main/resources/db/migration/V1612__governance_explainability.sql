-- XP-07: an objective profile drafted from plain English keeps the text it came from, how it was drafted (llm or
-- template) and the per-term rationale shown to the approver.
alter table objective_profile add column if not exists source_text text;
alter table objective_profile add column if not exists draft_method text;
alter table objective_profile add column if not exists rationale jsonb not null default '{}'::jsonb;

-- XP-04: one weekly learning digest per tenant: what changed, measured impact, what is pending.
create table if not exists learning_digest (
  digest_id     text primary key,
  tenant_id     text not null,
  period_start  timestamptz not null,
  period_end    timestamptz not null,
  summary       jsonb not null,
  delivered_to  jsonb not null default '[]'::jsonb,
  delivery      text not null,                                -- sent | stored_only | failed
  created_by    text not null,
  created_at    timestamptz not null default now()
);
create index if not exists learning_digest_tenant_idx on learning_digest(tenant_id, created_at desc);

-- NF-10: retention sweeps select by age.
create index if not exists outcome_tenant_created_idx on outcome(tenant_id, created_at);
create index if not exists learned_fact_tenant_valid_to_idx on learned_fact(tenant_id, valid_to) where valid_to is not null;
