-- §9.1 decision_log is append-only. decision_record (today's decision log) keeps the decision exactly as it was made:
-- a trigger refuses DELETE and any UPDATE of the decision's own columns (input, state, context_features, arms,
-- chosen_arm, propensity, policy_version, value, band, confidence, created_at, ...).
--
-- What legitimately arrives later (labels, overrides, human resolutions, outcome links) is appended to
-- decision_annotation, itself append-only. decision_record's labels and override columns stay as a read cache of
-- those annotations, so existing readers keep working; the trigger lets them change only inside the annotation
-- statement of DecisionRecordRepository (which sets oms.decision_annotation for its own transaction).
--
-- Legal erasure (GV-06) is the one exception: with oms.legal_erasure set for the transaction, DecisionInputErasure may
-- pseudonymise the personal columns (input, state, context_features, details, labels, subject_id, correlation_id,
-- override_reason) and scrub annotation payloads. It never deletes, and never touches the decision itself.

create table if not exists decision_annotation (
  annotation_id      text primary key,
  tenant_id          text not null,
  decision_record_id text not null,
  kind               text not null check (kind in ('label', 'override')),
  name               text not null,
  payload            jsonb not null default '{}'::jsonb,
  created_by         text,
  created_at         timestamptz not null default now()
);
create index if not exists decision_annotation_record_idx on decision_annotation(tenant_id, decision_record_id, created_at);

create or replace function decision_record_guard() returns trigger language plpgsql as $$
declare
  cache_cols   text[] := array['labels', 'overridden_value', 'override_reason', 'overridden_by', 'overridden_at'];
  erasure_cols text[] := array['input', 'state', 'context_features', 'details', 'labels', 'subject_id', 'correlation_id', 'override_reason'];
begin
  if tg_op = 'DELETE' then
    raise exception 'decision_record is append-only (decision_log): a decision is never deleted';
  end if;
  if coalesce(current_setting('oms.legal_erasure', true), '') = 'on' then
    if (to_jsonb(new) - erasure_cols) is distinct from (to_jsonb(old) - erasure_cols) then
      raise exception 'decision_record is append-only: legal erasure may only pseudonymise personal columns';
    end if;
    return new;
  end if;
  if coalesce(current_setting('oms.decision_annotation', true), '') = 'on' then
    if (to_jsonb(new) - cache_cols) is distinct from (to_jsonb(old) - cache_cols) then
      raise exception 'decision_record is append-only: an annotation may only refresh the labels and override cache';
    end if;
    return new;
  end if;
  raise exception 'decision_record is append-only (decision_log): append a decision_annotation instead';
end $$;

drop trigger if exists decision_record_append_only on decision_record;
create trigger decision_record_append_only before update or delete on decision_record
  for each row execute function decision_record_guard();

create or replace function decision_annotation_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' and coalesce(current_setting('oms.legal_erasure', true), '') = 'on'
     and (to_jsonb(new) - 'payload'::text) is not distinct from (to_jsonb(old) - 'payload'::text) then
    return new;
  end if;
  raise exception 'decision_annotation is append-only';
end $$;

drop trigger if exists decision_annotation_append_only on decision_annotation;
create trigger decision_annotation_append_only before update or delete on decision_annotation
  for each row execute function decision_annotation_guard();

-- Annotations recorded before this migration, so the annotation log is complete.
insert into decision_annotation(annotation_id, tenant_id, decision_record_id, kind, name, payload, created_by, created_at)
select 'ann_bf_' || md5(d.tenant_id || '|' || d.id || '|' || l.key), d.tenant_id, d.id, 'label', l.key,
       case when jsonb_typeof(l.value) = 'object' then l.value else jsonb_build_object('value', l.value) end, null, d.created_at
from decision_record d, jsonb_each(d.labels) l
where d.labels is not null and d.labels <> '{}'::jsonb
on conflict do nothing;

insert into decision_annotation(annotation_id, tenant_id, decision_record_id, kind, name, payload, created_by, created_at)
select 'ann_bf_' || md5(d.tenant_id || '|' || d.id || '|override'), d.tenant_id, d.id, 'override', 'override',
       jsonb_build_object('value', d.overridden_value, 'reason', d.override_reason, 'by', d.overridden_by, 'at', d.overridden_at),
       d.overridden_by, coalesce(d.overridden_at, d.created_at)
from decision_record d
where d.overridden_value is not null
on conflict do nothing;
