-- Monitoring / control tower (FR-MON-01..06): SLA definitions, exception taxonomy, exceptions, playbooks, breach risk, alerts.

-- SLA per (order type, entity, state) (FR-MON-02, FR-SM-08). max_age / expected_next_event_within are ISO-8601 durations.
create table if not exists sla_definition (
  id                          text primary key,
  tenant_id                   text not null,
  order_type                  text not null default '*',
  entity                      text not null default 'order_line',
  state                       text not null,
  max_age                     text,
  on_breach                   text,
  expected_next_event_within  text,
  created_at                  timestamptz not null default now(),
  updated_at                  timestamptz not null default now(),
  unique (tenant_id, order_type, entity, state)
);
create index if not exists sla_definition_state_idx on sla_definition(tenant_id, state);

-- Exception taxonomy (FR-MON-03): what the classifier's type means operationally.
create table if not exists exception_type (
  tenant_id    text not null,
  type         text not null,
  severity     text not null default 'medium',
  queue        text not null,
  playbook     text,
  sla_minutes  int not null default 240,
  description  text,
  updated_at   timestamptz not null default now(),
  primary key (tenant_id, type)
);

-- Playbooks (FR-MON-04): ordered remediation steps with gates.
create table if not exists playbook (
  tenant_id    text not null,
  name         text not null,
  definition   jsonb not null,
  updated_at   timestamptz not null default now(),
  primary key (tenant_id, name)
);

-- Exceptions (FR-MON-03/04). Status: OPEN | IN_PROGRESS | RESOLVED | CANCELLED.
create table if not exists exception (
  id                        text primary key,
  tenant_id                 text not null,
  order_id                  text,
  line_id                   text,
  node_id                   text,
  type                      text not null,
  severity                  text not null,
  queue                     text not null,
  status                    text not null default 'OPEN',
  source                    text not null,
  code                      text not null,
  message                   text,
  details                   jsonb not null default '{}'::jsonb,
  classification_record_id  text,
  classification_confidence double precision,
  playbook                  text,
  playbook_steps            jsonb not null default '[]'::jsonb,
  playbook_run_at           timestamptz,
  decision_record_id        text,
  sla_due_at                timestamptz,
  assignee                  text,
  task_id                   text,
  resolution                text,
  resolution_note           text,
  resolved_at               timestamptz,
  occurrences               int not null default 1,
  last_occurrence_at        timestamptz not null default now(),
  source_event_id           text,
  created_at                timestamptz not null default now(),
  updated_at                timestamptz not null default now()
);
create index if not exists exception_tenant_status_idx on exception(tenant_id, status, created_at desc);
create index if not exists exception_tenant_order_idx on exception(tenant_id, order_id);
create index if not exists exception_tenant_queue_idx on exception(tenant_id, queue, status);
create index if not exists exception_dedupe_idx on exception(tenant_id, type, code, status, last_occurrence_at);
create index if not exists exception_source_event_idx on exception(tenant_id, source_event_id) where source_event_id is not null;

-- Predicted breach risk per open order (FR-MON-02), decision sla_breach_risk (Score 0-4).
create table if not exists order_risk (
  tenant_id           text not null,
  order_id            text not null,
  score               int not null,
  level               text not null,
  reasons             jsonb not null default '[]'::jsonb,
  decision_record_id  text,
  scored_at           timestamptz not null default now(),
  primary key (tenant_id, order_id)
);
create index if not exists order_risk_level_idx on order_risk(tenant_id, score desc, scored_at desc);

-- Alerts with de-duplication (FR-MON-06). One row per alert episode; repeats inside the window bump count/last_seen.
create table if not exists alert (
  id             text primary key,
  tenant_id      text not null,
  key            text not null,
  severity       text not null,
  title          text not null,
  body           text,
  links          jsonb not null default '{}'::jsonb,
  first_seen     timestamptz not null default now(),
  last_seen      timestamptz not null default now(),
  count          int not null default 1,
  channels_sent  jsonb not null default '[]'::jsonb
);
create index if not exists alert_key_idx on alert(tenant_id, key, last_seen desc);
create index if not exists alert_time_idx on alert(tenant_id, last_seen desc);
