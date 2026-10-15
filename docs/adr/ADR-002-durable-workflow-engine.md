# ADR-002: Postgres-backed durable step engine instead of Temporal at MVP

**Status:** accepted · **Date:** 2026-10-04

## Context
FR-WF-01 recommends Temporal and asks us to evaluate Camunda 8 and Step Functions. The MVP must be operable by a
small team, run locally with `docker compose up`, and keep exactly-once per-order semantics with the ledger.

## Decision
Implement `oms-workflow`: a durable step machine persisted in PostgreSQL (instances, cached activity results,
signals with per-instance ordering and idempotency keys, timers, timeline, saga compensation, human tasks, bulk ops).
Workers pull due instances with `FOR UPDATE SKIP LOCKED`. The `WorkflowEngine` interface is the seam: a Temporal
adapter can implement it later without touching order orchestration code.

## Why not Temporal now
Temporal would add a cluster (frontend/history/matching/worker + its own DB), a second source of truth for timers and a
separate versioning model. The decision stays open (PRD §15 open question) and is cheap to revisit because
definitions are step maps with activity semantics that map 1:1 onto Temporal workflows/activities.

## Consequences
+ One database, one backup, one failure model; step execution and ledger append share a transaction.
− Long-running activities hold a row lock; activities are kept short and external calls idempotent.
