-- EX-07 / AC4: what a promotion replaced, so it can be rolled back exactly (previous decision version, config value,
-- fact value or live-use state).
alter table proposal add column if not exists rollback_snapshot jsonb;
-- The one-sentence evidence a learner or the author gives for its changeset (shown on the change card).
alter table proposal add column if not exists motivation text;

-- EX-02..EX-06: which artifact an experiment exposes, and the decision versions it compares.
alter table experiment add column if not exists target_ref text;
alter table experiment add column if not exists decision_name text;
alter table experiment add column if not exists candidate_version int;
alter table experiment add column if not exists baseline_version int;

update experiment e set target_ref = p.target_ref
from proposal p
where e.tenant_id = p.tenant_id and e.proposal_id = p.proposal_id and e.target_ref is null;
update experiment set target_ref = 'tenant' where target_ref is null and mode = 'HOLDOUT';

-- EX-06: at most one running experiment per artifact per slice (mode). Older duplicates are stopped first.
update experiment e set status = 'STOPPED', end_at = coalesce(e.end_at, now())
where e.status = 'RUNNING' and e.target_ref is not null and exists (
  select 1 from experiment n
  where n.tenant_id = e.tenant_id and n.target_ref = e.target_ref and n.mode = e.mode and n.status = 'RUNNING'
    and (n.start_at > e.start_at or (n.start_at = e.start_at and n.experiment_id > e.experiment_id)));
create unique index if not exists experiment_one_running_per_target_uq
  on experiment(tenant_id, target_ref, mode) where status = 'RUNNING' and target_ref is not null;

-- EX-04: the decision version that was live before the first promoted self-optimizing change. The persistent
-- holdout keeps seeing it, so cumulative lift (XP-01) compares against what the tenant had before learning.
create table if not exists learning_baseline (
  tenant_id        text not null,
  decision         text not null,
  baseline_version int  not null,
  proposal_id      text,
  recorded_at      timestamptz not null default now(),
  primary key (tenant_id, decision)
);

-- §6.3 / NF-03: every analyst, author and critic call, with the hash of the exact output schema and prompt template
-- it ran under, so a drafted change can always be traced to the contract that produced it.
create table if not exists learning_llm_call (
  call_id      text primary key,
  tenant_id    text not null,
  role         text not null,                 -- analyst | author | critic
  feature      text not null,
  ref_type     text,                          -- decision | hypothesis | proposal
  ref_id       text,
  schema_name  text not null,
  schema_hash  text not null,
  prompt_name  text,
  prompt_hash  text,
  model        text,
  outcome      text not null,                 -- ok | no_output | invalid | error | skipped_budget
  produced_id  text,
  created_at   timestamptz not null default now()
);
create index if not exists learning_llm_call_tenant_role_idx on learning_llm_call(tenant_id, role, created_at desc);
