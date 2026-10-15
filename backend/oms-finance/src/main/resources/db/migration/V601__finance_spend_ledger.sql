-- Finance: spend ledger behind the GR-05 velocity caps and circuit breaker

create table if not exists spend_ledger (
  id         bigserial primary key,
  tenant_id  text not null,
  category   text not null,                        -- refund | appeasement
  actor      text not null,                        -- actor ref, e.g. user:alice, agent:shopbot, system:playbook
  agent_id   text,                                 -- agent id when the spend was initiated by an agent
  amount     numeric(14,2) not null,
  currency   text not null,
  ref_id     text,                                 -- refund id / appeasement id for reconciliation
  at         timestamptz not null default now()
);
create index if not exists spend_ledger_tenant_cat_at_idx on spend_ledger(tenant_id, category, at);
create index if not exists spend_ledger_tenant_actor_at_idx on spend_ledger(tenant_id, category, actor, at);
create index if not exists spend_ledger_tenant_agent_at_idx on spend_ledger(tenant_id, category, agent_id, at) where agent_id is not null;
drop trigger if exists spend_ledger_append_only on spend_ledger;
create trigger spend_ledger_append_only before update or delete on spend_ledger for each row execute function reject_mutation();
