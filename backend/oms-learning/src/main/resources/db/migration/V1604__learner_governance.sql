-- GV-03 / OC-07: a pause is separate from autonomy. Pausing (kill switch, coverage breach, drift) no longer
-- overwrites the learner's autonomy level, so resuming restores exactly what a human had set.
alter table learner add column if not exists paused_reason text;
alter table learner add column if not exists paused_at timestamptz;
alter table learner add column if not exists paused_by text;

-- Live use of a learned value is itself a promoted change (EX-07). One row per parameter family a live path reads
-- (e.g. "sourcing.node_on_time_rate"): while LIVE, the fast loop's latest value is served within [min_value,
-- max_value] and at most max_step away from the static baseline; otherwise the static value is served.
create table if not exists learned_parameter_live (
  tenant_id     text not null,
  parameter     text not null,
  learner_key   text not null,
  min_value     double precision,
  max_value     double precision,
  max_step      double precision,
  status        text not null default 'LIVE',               -- LIVE | REVOKED
  proposal_id   text,
  promoted_by   text not null,
  promoted_at   timestamptz not null default now(),
  revoked_by    text,
  revoked_at    timestamptz,
  primary key (tenant_id, parameter)
);

-- Seeded reference data and real activity must be distinguishable everywhere (demo badge, guardrail monitors).
alter table experiment add column if not exists created_by text not null default 'system';
