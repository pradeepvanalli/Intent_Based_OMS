-- oms-agent: agent registry, activity, mandates and UCP webhook deliveries (FR-AGT-02/03/04, FR-CAP-07, FR-FIN-06, FR-AGT-09)

create table if not exists agent_identity (
  tenant_id      text not null references tenant(tenant_id),
  agent_id       text not null,
  owner          text not null,
  protocol       text not null,                       -- ucp | acp | mcp
  trust_tier     int  not null default 1,             -- 0 unknown/read-only, 1 verified, 2 trusted partner, 3 internal
  secret_hash    text not null,                       -- sha256 of the HMAC secret (lookup / presentation check)
  secret_enc     text not null,                       -- AES-256-GCM encrypted HMAC secret (needed to verify request signatures)
  public_key     text,                                -- optional PEM/base64 Ed25519 or EC public key for signed mandates
  api_key_id     text,                                -- platform api_key issued for the agent
  limits         jsonb not null default '{}'::jsonb,  -- {allowedActions[], maxOrderValueUsd, maxRefundUsd, ratePerMinute, confirmActions[]}
  callback_url   text,                                -- UCP lifecycle webhooks
  status         text not null default 'active',      -- active | suspended
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  last_seen_at   timestamptz,
  primary key (tenant_id, agent_id)
);
create index if not exists agent_identity_agent_idx on agent_identity(agent_id);
create index if not exists agent_identity_api_key_idx on agent_identity(api_key_id);

create table if not exists agent_activity (
  id             bigserial primary key,
  tenant_id      text not null,
  agent_id       text not null,
  at             timestamptz not null default now(),
  tool           text not null,
  outcome        text not null,                       -- ok | error | denied | pending_confirmation | rate_limited
  order_id       text,
  latency_ms     bigint,
  details        jsonb not null default '{}'::jsonb
);
create index if not exists agent_activity_agent_idx on agent_activity(tenant_id, agent_id, at desc);
create index if not exists agent_activity_order_idx on agent_activity(tenant_id, order_id);

create table if not exists mandate (
  tenant_id            text not null references tenant(tenant_id),
  mandate_id           text not null,
  agent_id             text not null,
  buyer_ref            text,
  scope                jsonb not null default '{}'::jsonb,   -- {merchant, categories[], maxItems}
  spend_limit          numeric(18,2) not null,
  currency             text not null default 'USD',
  instruction_text     text,
  signature            text,
  signature_valid      boolean,                             -- null = unsigned
  payment_token        text,                                -- tokenised delegated payment credential (FR-FIN-06)
  injection_risk       numeric(5,4),
  injection_record_id  text,
  issued_at            timestamptz not null default now(),
  expires_at           timestamptz not null,
  status               text not null default 'active',      -- active | consumed | expired | revoked
  orders_used          jsonb not null default '[]'::jsonb,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  primary key (tenant_id, mandate_id)
);
create index if not exists mandate_agent_idx on mandate(tenant_id, agent_id, issued_at desc);
create index if not exists mandate_buyer_idx on mandate(tenant_id, buyer_ref);

create table if not exists agent_webhook_delivery (
  id             bigserial primary key,
  tenant_id      text not null,
  agent_id       text not null,
  order_id       text,
  event_id       text not null,
  type           text not null,                       -- order.shipped | order.delivered | return.created | refund.issued
  url            text not null,
  status         text not null,                       -- delivered | failed
  http_status    int,
  error          text,
  created_at     timestamptz not null default now(),
  delivered_at   timestamptz
);
create index if not exists agent_webhook_delivery_agent_idx on agent_webhook_delivery(tenant_id, agent_id, created_at desc);
create index if not exists agent_webhook_delivery_event_idx on agent_webhook_delivery(tenant_id, event_id, agent_id);
