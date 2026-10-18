-- Escalation de-duplication per (decision, subject), opt-in per decision (tenant config escalation.dedupe_decisions).
-- A scheduled scorer (sla_breach_risk) re-evaluates every open order every minute; each ESCALATE result used to open a
-- new human task, so one order collected hundreds of identical tasks. Re-escalations are now stored as LINKED to the
-- one open task; the partial unique index makes the "one OPEN anchor per subject" rule race-safe.
alter table decision_escalation add column if not exists subject_type text;
alter table decision_escalation add column if not exists subject_id text;
alter table decision_escalation add column if not exists dedupe boolean not null default false;

update decision_escalation e
set subject_type = r.subject_type, subject_id = r.subject_id
from decision_record r
where r.tenant_id = e.tenant_id and r.id = e.record_id and e.subject_type is null;

-- Existing duplicates of the default de-duplicated decision: keep the oldest OPEN escalation per subject as the anchor,
-- mark the rest LINKED and point them at the anchor's task. The duplicate tasks are left with no escalation and are
-- cancelled at startup through HumanTaskService (EscalationDuplicateCleanup) so the ledger records it.
update decision_escalation set dedupe = true where decision = 'sla_breach_risk';

with ranked as (
  select id, tenant_id, decision, subject_type, subject_id, task_id,
         row_number() over (partition by tenant_id, decision, subject_type, subject_id order by created_at, id) as rn,
         first_value(task_id) over (partition by tenant_id, decision, subject_type, subject_id order by created_at, id) as anchor_task
  from decision_escalation
  where status = 'OPEN' and dedupe and subject_id is not null
)
update decision_escalation e
set status = 'LINKED', task_id = ranked.anchor_task
from ranked
where e.id = ranked.id and e.tenant_id = ranked.tenant_id and ranked.rn > 1;

create unique index if not exists decision_escalation_open_subject_uq
  on decision_escalation(tenant_id, decision, subject_type, subject_id)
  where status = 'OPEN' and dedupe and subject_id is not null;
create index if not exists decision_escalation_task_idx on decision_escalation(tenant_id, task_id);
