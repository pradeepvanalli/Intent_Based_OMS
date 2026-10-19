-- EX-07 / GV-02: when a person promotes a change the gate would have held back (no shadow evidence, a confidence
-- interval that crosses zero, an exhausted change budget, a step above the learner's limit), the waived checks and the
-- reason they gave are kept on the proposal for the change card, besides the ledger event.
alter table proposal add column if not exists promotion_note jsonb;

-- GV-03: the global learning kill switch, settable at runtime by a platform administrator and shared by every tenant.
-- One row per setting; the property oms.learning.kill-switch still forces it on at startup.
create table if not exists learning_global_setting (
  setting_key  text primary key,
  value        jsonb not null,
  updated_by   text not null,
  updated_at   timestamptz not null default now()
);

-- Lookups the budget and cooldown checks run on every promotion and every new proposal.
create index if not exists proposal_tenant_target_promoted_idx on proposal(tenant_id, target_ref, promoted_at);
create index if not exists proposal_tenant_target_rolled_back_idx on proposal(tenant_id, target_ref, rolled_back_at) where status = 'ROLLED_BACK';
