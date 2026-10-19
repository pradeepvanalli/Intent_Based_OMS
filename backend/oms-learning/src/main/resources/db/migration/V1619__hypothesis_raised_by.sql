-- GV-04 / §9.2: who raised a hypothesis (a learner key, the analyst, the demo seeder or a person), so the ledger and the
-- change card attribute it to its real author instead of a generic "system:learning".
alter table hypothesis add column if not exists raised_by text;
