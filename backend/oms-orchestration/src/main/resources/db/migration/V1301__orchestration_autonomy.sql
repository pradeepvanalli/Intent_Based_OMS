-- Agentic Control Tower (oms-orchestration autonomy package): anomaly feed and autonomous-action audit trail.

-- One row per anomaly the scanner found and diagnosed; the live feed the UI renders.
create table if not exists autonomy_anomaly (
  id                   text primary key,
  tenant_id            text not null,
  order_id             text not null,
  anomaly_type         text not null,
  decision_record_id   text,
  diagnosis_value      text,
  confidence           double precision,
  band                 text,
  reason_codes         jsonb not null default '[]'::jsonb,
  outcome              text not null,
  action_id            text,
  escalation_task_id   text,
  escalation_queue     text,
  escalation_reason    text,
  detected_at          timestamptz not null default now()
);
create index if not exists autonomy_anomaly_tenant_idx on autonomy_anomaly(tenant_id, detected_at desc);
create index if not exists autonomy_anomaly_order_idx on autonomy_anomaly(tenant_id, order_id, detected_at desc);

-- Every autonomous (or attempted-autonomous) remediation, fully audited (id, order, diagnosis, guardrails in force,
-- outcome, undo state). Written once per execution attempt so it is traceable end to end from the decision record.
create table if not exists autonomous_action (
  id                   text primary key,
  tenant_id            text not null,
  order_id             text not null,
  anomaly_type         text not null,
  decision_record_id   text,
  diagnosis_value      text not null,
  confidence           double precision not null,
  action_taken         text not null,
  guardrail_snapshot   jsonb not null default '{}'::jsonb,
  outcome              text not null,
  undoable             boolean not null default false,
  detail               text,
  details              jsonb not null default '{}'::jsonb,
  executed_at          timestamptz not null default now(),
  undone_at            timestamptz,
  undone_by            text,
  undo_reason          text
);
create index if not exists autonomous_action_tenant_idx on autonomous_action(tenant_id, executed_at desc);
create index if not exists autonomous_action_order_idx on autonomous_action(tenant_id, order_id);
create index if not exists autonomous_action_outcome_idx on autonomous_action(tenant_id, outcome, executed_at desc);
