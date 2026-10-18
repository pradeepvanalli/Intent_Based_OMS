-- Self-Optimizing Order Orchestration (SOO) core tables (requirements doc §9.1). decision_log is the extended
-- decision_record table in oms-decision (V401); the eight tables below are the new ones oms-learning owns. Every
-- table carries tenant_id and follows the existing tenant-context rules (ARCHITECTURE §2).

-- One row per decision point, joined to everything that followed it (OC-02). Starts 'open', becomes 'provisional'
-- once a reward can be estimated, 'mature' once every outcome term's maturity window has elapsed (OC-04).
create table if not exists episode (
  episode_id        text primary key,
  tenant_id         text not null,
  decision_id       text not null,                  -- decision_record.id this episode was opened from
  decision_name     text not null,
  order_id          text,
  line_id           text,
  fulfillment_group_id text,
  status            text not null default 'open',    -- open | provisional | mature
  reward            double precision,
  reward_components jsonb not null default '{}'::jsonb,
  policy_version    int,
  created_at        timestamptz not null default now(),
  matured_at        timestamptz
);
create index if not exists episode_tenant_decision_idx on episode(tenant_id, decision_name, created_at desc);
create index if not exists episode_tenant_order_idx on episode(tenant_id, order_id);
create index if not exists episode_tenant_status_idx on episode(tenant_id, status);
create unique index if not exists episode_tenant_decision_id_idx on episode(tenant_id, decision_id);

-- A typed, measured result attached to an episode once it's known (OC-03). One episode can have several outcomes
-- (a delivery outcome, then a return outcome weeks later) — see the typed Outcome catalog in ai.intentoms.learning.outcome.
create table if not exists outcome (
  outcome_id      text primary key,
  tenant_id       text not null,
  episode_id      text not null,
  type            text not null,                     -- DeliveryOutcome | FulfillmentOutcome | ReturnOutcome | PaymentOutcome | ContactOutcome | InventoryOutcome
  payload         jsonb not null,
  observed_at     timestamptz not null,
  source_event_id text,                               -- the ledger event this was derived from, when there is one
  created_at      timestamptz not null default now()
);
create index if not exists outcome_tenant_episode_idx on outcome(tenant_id, episode_id);
create index if not exists outcome_tenant_type_idx on outcome(tenant_id, type, observed_at desc);

-- Learned knowledge, stored as typed facts with provenance (CI-01, FS-02/03, IU-01/05). Versioned; never updated in
-- place — a new fact row with a later version supersedes the previous one (valid_to closes it).
create table if not exists learned_fact (
  fact_id      text primary key,
  tenant_id    text not null,
  subject_type text not null,                        -- customer | segment | node | lane | sku
  subject_id   text not null,
  fact_key     text not null,                         -- e.g. SPLIT_TOLERANCE, RTO_RISK, NODE_ON_TIME_RATE
  value        jsonb not null,
  confidence   double precision not null default 0,
  sample_size  int not null default 0,
  learner      text not null,
  version      int not null default 1,
  valid_from   timestamptz not null default now(),
  valid_to     timestamptz,
  created_at   timestamptz not null default now()
);
create index if not exists learned_fact_tenant_subject_idx on learned_fact(tenant_id, subject_type, subject_id, fact_key);
create index if not exists learned_fact_tenant_valid_idx on learned_fact(tenant_id, fact_key, valid_to);

-- Analyst-discovered customer/behavioural segments (CI-02). Statistics-defined, analyst-named.
create table if not exists segment (
  segment_id  text primary key,
  tenant_id   text not null,
  version     int not null default 1,
  label       text not null,
  description text,
  definition  jsonb not null default '{}'::jsonb,
  size        int not null default 0,
  created_by  text not null,
  created_at  timestamptz not null default now()
);
create index if not exists segment_tenant_idx on segment(tenant_id, label);

-- The analyst LLM's only output: a testable claim, never a conclusion (§6.1).
create table if not exists hypothesis (
  hypothesis_id    text primary key,
  tenant_id        text not null,
  scope            jsonb not null default '{}'::jsonb,   -- {tenant, segment, node, lane, skuClass}
  target_metric    text not null,
  direction        text not null,                         -- INCREASE | DECREASE
  lever            jsonb not null,                         -- which parameter or rule it would move
  prior_confidence double precision not null default 0.5,
  evidence_refs    jsonb not null default '[]'::jsonb,
  rationale        text,
  status           text not null default 'open',           -- open | drafted | rejected | superseded
  created_at       timestamptz not null default now()
);
create index if not exists hypothesis_tenant_status_idx on hypothesis(tenant_id, status, created_at desc);

-- The author LLM's output: a diff against a versioned artifact (§6.2), gated by the same lint/golden-set/approval
-- path as any other decision change (EX-07) before promotion.
create table if not exists proposal (
  proposal_id       text primary key,
  tenant_id         text not null,
  target_ref        text not null,                         -- the artifact this changes, e.g. "decision:sourcing_node_score"
  base_version      int not null,
  changeset         jsonb not null,                         -- List<PolicyChange>
  hypothesis_id     text,
  tier              text not null,                          -- T0..T3, from the target decision/parameter
  status            text not null default 'draft',          -- draft -> evaluated -> approved -> canary -> promoted | rejected | rolled_back
  critic_review     jsonb,                                  -- {verdict, risks[]}
  evaluation_report jsonb,
  created_by        text not null,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  evaluated_at      timestamptz,
  approved_by       text,
  approved_at       timestamptz,
  promoted_at       timestamptz,
  rejected_reason   text,
  rejected_at       timestamptz,
  rolled_back_reason text,
  rolled_back_at    timestamptz
);
create index if not exists proposal_tenant_status_idx on proposal(tenant_id, status, created_at desc);
create index if not exists proposal_tenant_target_idx on proposal(tenant_id, target_ref);

-- One active experiment per decision point per traffic slice (EX-06). Shadow runs in parallel without acting;
-- canary takes a sticky traffic share; a persistent holdout (EX-04) is modelled as a canary with no end date.
create table if not exists experiment (
  experiment_id text primary key,
  tenant_id     text not null,
  proposal_id   text not null,
  mode          text not null,                              -- shadow | canary | holdout
  traffic_share double precision not null default 0,
  status        text not null default 'running',             -- running | stopped | completed
  start_at      timestamptz not null default now(),
  end_at        timestamptz,
  stop_rule     jsonb not null default '{}'::jsonb,
  result        jsonb,                                       -- {estimate, ci, guardrails}
  created_at    timestamptz not null default now()
);
create index if not exists experiment_tenant_proposal_idx on experiment(tenant_id, proposal_id);
create index if not exists experiment_tenant_status_idx on experiment(tenant_id, status);

-- Tenant-weighted reward terms (§7), versioned like any policy. Only one version is 'approved' (live) per tenant
-- at a time; prior versions stay for audit/rollback.
create table if not exists objective_profile (
  tenant_id   text not null,
  version     int not null,
  status      text not null default 'draft',                 -- draft | approved | retired
  weights     jsonb not null,                                 -- {fill, otd, cost, split, fraud, cx, inv}
  constraints jsonb not null default '{}'::jsonb,
  approved_by text,
  approved_at timestamptz,
  created_by  text not null,
  created_at  timestamptz not null default now(),
  primary key (tenant_id, version)
);
create index if not exists objective_profile_tenant_status_idx on objective_profile(tenant_id, status);

-- Per-learner autonomy and change-budget governance (GV-01, GV-02, GV-03). One row per (tenant, learner).
create table if not exists learner (
  learner_key     text not null,                              -- e.g. "node_reliability", "sourcing_policy_bandit"
  tenant_id       text not null,
  autonomy_level  text not null default 'L1',                  -- L0 observe | L1 recommend | L2 act-with-approval | L3 autonomous
  enabled         boolean not null default true,
  max_promotions_per_week int not null default 1,
  max_step        double precision,                            -- max movement per promotion, when the lever is numeric
  last_run_at     timestamptz,
  updated_by      text,
  updated_at      timestamptz not null default now(),
  primary key (tenant_id, learner_key)
);
