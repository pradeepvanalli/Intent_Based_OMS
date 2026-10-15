# ADR-004: PostgreSQL event ledger with transactional outbox (CloudEvents)

**Status:** accepted · **Date:** 2026-10-04

Every state change appends an immutable event (`event` table, append-only trigger, per-aggregate gap-free sequence
under a row lock) in the same transaction as the row change. The `outbox` table is drained by a relay that delivers to
in-process subscribers, tenant webhooks (HMAC-signed) and optionally Kafka (`oms.kafka.enabled`). Any order can be
rebuilt and any decision re-run against the exact inputs it saw (design principle 4, FR-SIM-01). Retention: 7 years by
default (NFR-14); PII in payloads is tokenised per tenant so deletion is crypto-shredding (NFR-08).
