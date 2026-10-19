-- The stuck-order detector re-diagnoses a still-stuck order about once an hour, and each ESCALATE used to open
-- another task for the same order. stuck_order_diagnosis joins the default de-duplicated decisions; convert its
-- existing duplicates the same way V402 did for sla_breach_risk (oldest OPEN escalation per order stays the anchor,
-- the rest become LINKED to its task; EscalationDuplicateCleanup cancels the orphaned tasks at startup).
--
-- Order matters: V402's unique index covers OPEN rows with dedupe = true, so the duplicates are folded first and the
-- flag set afterwards. Flagging first fails on a database that still holds the duplicates (the upgrade path).
with ranked as (
  select id, tenant_id,
         row_number() over (partition by tenant_id, decision, subject_type, subject_id order by created_at, id) as rn,
         first_value(task_id) over (partition by tenant_id, decision, subject_type, subject_id order by created_at, id) as anchor_task
  from decision_escalation
  where status = 'OPEN' and subject_id is not null and decision = 'stuck_order_diagnosis'
)
update decision_escalation e
set status = 'LINKED', task_id = ranked.anchor_task
from ranked
where e.id = ranked.id and e.tenant_id = ranked.tenant_id and ranked.rn > 1;

update decision_escalation set dedupe = true where decision = 'stuck_order_diagnosis';
