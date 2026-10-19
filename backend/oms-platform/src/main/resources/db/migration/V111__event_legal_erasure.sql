-- The ledger stays append-only, with legal erasure (GV-06) as the one exception, as for decision_record (V405):
-- inside a transaction that set oms.legal_erasure, the erasure path may rewrite the payload (and metadata) of a
-- learning event (aggregate_type 'learning', e.g. fact.learned) to replace an erased customer's reference with its
-- vault token and drop identifying values. Nothing else about an event can change, no other event can be updated,
-- and no event can ever be deleted. audit_log keeps the plain append-only trigger.
create or replace function event_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE'
     and coalesce(current_setting('oms.legal_erasure', true), '') = 'on'
     and old.aggregate_type = 'learning'
     and (to_jsonb(new) - 'payload'::text - 'metadata'::text) is not distinct from (to_jsonb(old) - 'payload'::text - 'metadata'::text) then
    return new;
  end if;
  raise exception 'table % is append-only', tg_table_name;
end $$;

drop trigger if exists event_append_only on event;
create trigger event_append_only before update or delete on event for each row execute function event_guard();
