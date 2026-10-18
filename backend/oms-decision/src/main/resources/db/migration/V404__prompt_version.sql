-- DF-06: prompt artifacts are versioned on their own, but reach production only through the decision studio's gate:
-- each prompt version is carried by a decision-definition draft (studio_version) that is linted, evaluated against the
-- golden set, approved by someone other than its author and published like any other decision change.
create table if not exists prompt_version (
  tenant_id          text not null,
  decision           text not null,
  question           text not null,
  version            int  not null,
  instructions       text not null,
  status             text not null default 'draft',      -- draft | evaluated | pending_approval | approved | live | retired | rejected
  change_reason      text,
  ticket             text,
  lint_report        jsonb,
  evaluation_report  jsonb,
  studio_version     int,
  created_by         text not null,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  published_at       timestamptz,
  primary key (tenant_id, decision, question, version)
);
