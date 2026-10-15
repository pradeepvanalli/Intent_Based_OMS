-- Human tasks (FR-WF-04): queues with SLA, skills and escalation; tasks that optionally resume a workflow.

create table if not exists task_queue (
  tenant_id           text not null,
  name                text not null,
  default_sla_minutes int not null default 240,
  escalation_queue    text,
  skills              jsonb not null default '[]'::jsonb,
  description         text,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  primary key (tenant_id, name)
);

create table if not exists human_task (
  id              text primary key,
  tenant_id       text not null,
  type            text not null,
  queue           text not null,
  subject_type    text,
  subject_id      text,
  title           text not null,
  payload         jsonb not null default '{}'::jsonb,
  status          text not null,                     -- OPEN, CLAIMED, COMPLETED, CANCELLED, EXPIRED
  assignee        text,
  sla_due_at      timestamptz,
  created_at      timestamptz not null,
  updated_at      timestamptz not null,
  outcome         text,
  outcome_reason  text,
  outcome_details jsonb,
  completed_by    text,
  completed_at    timestamptz,
  workflow_id     text,
  required_skills jsonb not null default '[]'::jsonb,
  priority        int not null default 0,
  escalated_at    timestamptz,
  escalated_from  text,
  created_by      text
);
create index if not exists human_task_queue_status_idx on human_task(tenant_id, queue, status, priority desc, sla_due_at);
create index if not exists human_task_assignee_idx on human_task(tenant_id, assignee, status);
create index if not exists human_task_subject_idx on human_task(tenant_id, subject_type, subject_id);
create index if not exists human_task_workflow_idx on human_task(tenant_id, workflow_id);
-- Sweeper lookup: open tasks that are past SLA and not yet escalated.
create index if not exists human_task_overdue_idx on human_task(sla_due_at) where status in ('OPEN','CLAIMED') and escalated_at is null;
