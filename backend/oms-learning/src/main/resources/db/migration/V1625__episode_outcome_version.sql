-- The reward sweep recomputes an episode whose outcomes changed since its reward. A timestamp cannot say that: an
-- outcome written inside the outbox relay's batch transaction carries a created_at from before its commit, and a sweep
-- that read the episode before that commit stamps a later reward_computed_at, so the change was never scored until the
-- episode matured. outcome_version counts outcome writes (insert, merge) per episode; reward_outcome_version is the
-- count the last reward was computed from.
alter table episode add column if not exists outcome_version bigint not null default 0;
alter table episode add column if not exists reward_outcome_version bigint not null default 0;
