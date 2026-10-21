-- C4-N3: legal erasure (GV-06) propagates to order-domain ledger events too (order.captured, order.shipped,
-- cs.wismo, cs.complaint, ...), not just the learning ledger (V111): inside a transaction that set
-- oms.legal_erasure, the erasure path may now also rewrite the payload (and metadata) of an event whose
-- aggregate_type is 'order', replacing an erased customer's reference with its vault token. Still payload/metadata
-- only, still no other event, still no delete, ever.
create or replace function event_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE'
     and coalesce(current_setting('oms.legal_erasure', true), '') = 'on'
     and old.aggregate_type in ('learning', 'order')
     and (to_jsonb(new) - 'payload'::text - 'metadata'::text) is not distinct from (to_jsonb(old) - 'payload'::text - 'metadata'::text) then
    return new;
  end if;
  raise exception 'table % is append-only', tg_table_name;
end $$;
