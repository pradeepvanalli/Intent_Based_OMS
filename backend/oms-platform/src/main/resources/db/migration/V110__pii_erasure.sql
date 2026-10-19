-- GV-06 right to erasure: one row per erased customer, so an erasure request is idempotent and auditable. The customer
-- reference itself is not stored: subject_hash is its SHA-256 (with the tenant), pseudonym the token that replaced it.
create table if not exists pii_erasure (
  tenant_id     text not null,
  subject_hash  text not null,
  pseudonym     text not null,
  erased_by     text not null,
  erased_at     timestamptz not null default now(),
  primary key (tenant_id, subject_hash)
);
