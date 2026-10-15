-- Decision fabric (FR-DEC-01..11, FR-PRM-01..08, GR-10..GR-17)

-- Versioned decision definitions (FR-DEC-01, FR-PRM-02). One row per (tenant, name, version).
create table if not exists decision_definition (
  tenant_id           text not null,
  name                text not null,
  version             int  not null,
  status              text not null,                 -- draft | pending_approval | approved | shadow | canary | live | retired
  tier                text not null,                 -- T0..T3
  yaml                text not null,
  parsed              jsonb not null default '{}'::jsonb,
  canary_percent      int  not null default 0,
  change_reason       text,
  ticket              text,
  created_by          text not null,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  submitted_by        text,
  submitted_at        timestamptz,
  published_at        timestamptz,
  retired_at          timestamptz,
  approvals           jsonb not null default '[]'::jsonb,   -- [{actor, at}]
  rollout             jsonb not null default '{}'::jsonb,   -- {mode, percent, at, by, emergency}
  evaluation_report   jsonb,
  lint_report         jsonb,
  retro_review_due_at timestamptz,                          -- GR-10 emergency publish retro review
  primary key (tenant_id, name, version)
);
create index if not exists decision_definition_status_idx on decision_definition(tenant_id, name, status);

-- Every evaluated decision (FR-DEC-06). Append-only by convention: the application never updates any column except
-- overridden_value/override_reason/overridden_by/overridden_at (GR-13) and labels (FR-DEC-10), so the reject_mutation()
-- trigger is deliberately NOT installed on this table. The ledger events decision.evaluated / decision.overridden /
-- decision.labelled remain the immutable record (FR-XAI-02).
create table if not exists decision_record (
  id               text primary key,
  tenant_id        text not null,
  decision         text not null,
  version          int  not null,
  mode             text not null,                    -- LIVE | SHADOW | SIMULATION | REPLAY
  subject_type     text,
  subject_id       text,
  correlation_id   text,
  layer            text not null,                    -- L0_RULE | L1_SYSTEM_ONE | L2_LLM | L3_HUMAN | DEFAULT
  band             text not null,                    -- ACT | CONFIRM | ESCALATE
  action           text not null,                    -- act | confirm | escalate | blocked | default
  value            jsonb,
  answers          jsonb not null default '{}'::jsonb,
  confidence       double precision not null default 0,
  provider         text,
  model_version    text,
  input_hash       text not null,
  state            jsonb,                            -- tokenised state actually sent to the provider (GR-09)
  input            jsonb,                            -- full input, for replay and impact diffs (FR-SIM-01, FR-PRM-03)
  rules_fired      jsonb not null default '[]'::jsonb,
  reason_codes     jsonb not null default '[]'::jsonb,
  latency_ms       bigint not null default 0,
  cost_usd         double precision not null default 0,
  input_tokens     int not null default 0,
  requires_human   boolean not null default false,
  escalation_id    text,
  actor            text,
  details          jsonb not null default '{}'::jsonb,   -- pre/post check results, L2 attempt, failures, tier, bands
  overridden_value jsonb,
  override_reason  text,
  overridden_by    text,
  overridden_at    timestamptz,
  labels           jsonb not null default '{}'::jsonb,
  created_at       timestamptz not null default now()
);
create index if not exists decision_record_tenant_decision_time_idx on decision_record(tenant_id, decision, created_at desc);
create index if not exists decision_record_tenant_subject_idx on decision_record(tenant_id, subject_type, subject_id);
create index if not exists decision_record_tenant_hash_idx on decision_record(tenant_id, input_hash);
create index if not exists decision_record_correlation_idx on decision_record(tenant_id, correlation_id);

-- L3 escalations raised by the default EscalationHandler (GR-07).
create table if not exists decision_escalation (
  id             text primary key,
  tenant_id      text not null,
  record_id      text not null,
  decision       text not null,
  status         text not null default 'OPEN',       -- OPEN | RESOLVED | CANCELLED
  task_id        text,
  resolved_value jsonb,
  resolved_by    text,
  resolved_at    timestamptz,
  created_at     timestamptz not null default now()
);
create index if not exists decision_escalation_tenant_idx on decision_escalation(tenant_id, decision, status);
create index if not exists decision_escalation_record_idx on decision_escalation(tenant_id, record_id);

-- Labelled golden cases (GR-11).
create table if not exists decision_golden_case (
  tenant_id   text not null,
  golden_set  text not null,
  case_id     text not null,
  input       jsonb not null,
  expected    jsonb not null,                        -- {value, band?}
  notes       text,
  created_at  timestamptz not null default now(),
  primary key (tenant_id, golden_set, case_id)
);

-- Result cache by (definition version, input hash) with TTL (FR-DEC-11).
create table if not exists decision_cache (
  tenant_id      text not null,
  definition_key text not null,                      -- "<name>@<version>"
  input_hash     text not null,
  result         jsonb not null,
  expires_at     timestamptz not null,
  primary key (tenant_id, definition_key, input_hash)
);
create index if not exists decision_cache_expiry_idx on decision_cache(expires_at);

-- Daily AI spend per decision and provider for budgets and the cost KPI (GR-17).
create table if not exists ai_spend (
  tenant_id    text not null,
  day          date not null,
  decision     text not null,
  provider     text not null,
  calls        bigint not null default 0,
  cost_usd     double precision not null default 0,
  input_tokens bigint not null default 0,
  primary key (tenant_id, day, decision, provider)
);
