-- Self-Optimizing Order Orchestration (SOO): propensity logging on every decision (OC-01). Nullable/defaulted so the
-- append-only convention on decision_record (see V400) and every existing row are untouched; only new inserts
-- populate these going forward. Without this, past decisions cannot be fairly replayed against a new policy
-- (requirements doc §4, "Propensity logging is the single most important new data point").
alter table decision_record add column if not exists context_features jsonb;
alter table decision_record add column if not exists arms jsonb not null default '[]'::jsonb;
alter table decision_record add column if not exists chosen_arm text;
alter table decision_record add column if not exists propensity double precision not null default 1.0;
alter table decision_record add column if not exists policy_version int;
