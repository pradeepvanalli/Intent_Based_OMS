-- oms-integrations (section 11 "Integrations and open standards", FR-CAP-01): outbound/inbound integration logs.

-- Every customer message attempt made through CompositeCustomerNotifier (SendGrid / Twilio). Append-only.
create table if not exists notification_log (
  id            text primary key,
  tenant_id     text not null,
  order_id      text,
  channel       text not null,                 -- email | sms
  template      text not null,
  recipient     text,                          -- tokenised (platform Tokenizer), never the raw address
  status        text not null,                 -- sent | failed | skipped_no_provider | skipped_no_recipient | skipped_unknown_template
  provider      text,                          -- sendgrid | twilio
  provider_ref  text,                          -- X-Message-Id / Message SID
  communication_id text,                       -- customer_communication row written by the logging notifier
  error         text,
  at            timestamptz not null default now()
);
create index if not exists notification_log_order_idx on notification_log(tenant_id, order_id, at);
create index if not exists notification_log_status_idx on notification_log(tenant_id, status, at);
create trigger notification_log_append_only before update or delete on notification_log for each row execute function reject_mutation();

-- Marketplace orders received through POST /webhooks/marketplace/{marketplace} or the SP-API poller (FR-CAP-01).
create table if not exists marketplace_import (
  id                text primary key,
  tenant_id         text not null,
  marketplace       text not null,
  external_order_id text not null,
  order_id          text,
  status            text not null,             -- captured | replayed | failed
  error             text,
  payload_hash      text,
  received_at       timestamptz not null default now(),
  unique (tenant_id, marketplace, external_order_id, received_at)
);
create index if not exists marketplace_import_ext_idx on marketplace_import(tenant_id, marketplace, external_order_id);
create index if not exists marketplace_import_order_idx on marketplace_import(tenant_id, order_id);

-- Labels bought through the carrier aggregator (EasyPost); the SPI only returns the tracking code so the label URL lives here.
create table if not exists carrier_label (
  id            text primary key,
  tenant_id     text not null,
  shipment_id   text not null,
  order_id      text,
  carrier       text,
  service       text,
  tracking      text,
  label_url     text,
  rate_usd      numeric(12,2),
  provider      text not null,                 -- easypost
  provider_shipment_id text,
  provider_rate_id     text,
  created_at    timestamptz not null default now(),
  unique (tenant_id, shipment_id)
);
create index if not exists carrier_label_tracking_idx on carrier_label(tenant_id, tracking);

-- Release / cancel documents posted to a WMS over HTTP (HttpWmsConnector), one row per attempt. Append-only.
create table if not exists wms_outbound_log (
  id                  text primary key,
  tenant_id           text not null,
  order_id            text not null,
  fulfilment_group_id text not null,
  node_id             text,
  document_type       text not null,           -- release | cancel
  document_id         text not null,           -- idempotency key the WMS sees (X-Oms-Document-Id)
  url                 text not null,
  attempt             int not null,
  status              text not null,           -- sent | failed | gave_up
  http_status         int,
  response            text,
  error               text,
  at                  timestamptz not null default now()
);
create index if not exists wms_outbound_log_order_idx on wms_outbound_log(tenant_id, order_id, at);
create index if not exists wms_outbound_log_group_idx on wms_outbound_log(tenant_id, fulfilment_group_id, document_type);
create trigger wms_outbound_log_append_only before update or delete on wms_outbound_log for each row execute function reject_mutation();
