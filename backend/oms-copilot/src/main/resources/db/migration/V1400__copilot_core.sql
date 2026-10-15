-- Copilot module (FR-CFG-01/02, FR-MON-08, FR-XAI-03, FR-CS-05, GR-14, GR-17): change sets, staged draft artefacts,
-- LLM usage ledger and stored digests. The copilot never publishes: every artefact here is a draft until a person applies it.

-- Token/cost ledger per LLM call for per-tenant budgets (GR-17).
create table if not exists copilot_usage (
  id             bigserial primary key,
  tenant_id      text not null,
  feature        text not null,              -- config | bootstrap | ops_select | ops_answer | explain | cs_reply | digest | repair | explain_change
  model          text not null,
  input_tokens   int not null default 0,
  output_tokens  int not null default 0,
  cost_usd       double precision not null default 0,
  latency_ms     bigint not null default 0,
  status         text not null default 'ok', -- ok | error
  at             timestamptz not null default now()
);
create index if not exists copilot_usage_tenant_day_idx on copilot_usage(tenant_id, at desc);

-- A plain-language request and the proposed, validated changes (FR-CFG-01).
create table if not exists copilot_changeset (
  id             text primary key,
  tenant_id      text not null,
  status         text not null,              -- proposed | staged | rejected
  source         text not null default 'change',   -- change | bootstrap
  request        jsonb not null,
  body           jsonb not null,             -- the full ChangeSet document
  created_by     text not null,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);
create index if not exists copilot_changeset_tenant_idx on copilot_changeset(tenant_id, created_at desc);

-- Staged artefacts that have no draft state in their owning module (FR-CFG-02, GR-14): applied only by an authorised human.
create table if not exists copilot_draft_artifact (
  id             text primary key,
  tenant_id      text not null,
  changeset_id   text not null,
  change_id      text not null,
  kind           text not null,              -- state_model | order_type | sla | playbook | compliance_rule | tenant_config
  target         text not null,
  content        text not null,              -- YAML or JSON as proposed
  summary        text,
  status         text not null default 'draft',  -- draft | applied | rejected
  created_by     text not null,
  created_at     timestamptz not null default now(),
  applied_by     text,
  applied_at     timestamptz,
  apply_reason   text,
  result         jsonb
);
create index if not exists copilot_draft_artifact_tenant_idx on copilot_draft_artifact(tenant_id, status, created_at desc);

-- Stored Margin Guardian digests so the UI can show the latest without re-running the model.
create table if not exists copilot_digest (
  id             text primary key,
  tenant_id      text not null,
  days           int not null,
  body           jsonb not null,
  generated_by   text not null,
  created_at     timestamptz not null default now()
);
create index if not exists copilot_digest_tenant_idx on copilot_digest(tenant_id, created_at desc);
