-- N4: a rollback is either for cause (a guardrail breach, a failed trial, a person rolling the change back) or a
-- precautionary switch-off of live use. Only a rollback for cause keeps people from proposing the same change again
-- during the cooldown; learners respect the cooldown either way.
alter table proposal add column if not exists rolled_back_for_cause boolean not null default true;
