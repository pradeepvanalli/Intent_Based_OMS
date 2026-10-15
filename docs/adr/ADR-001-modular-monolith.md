# ADR-001: Modular monolith with enforced module boundaries

**Status:** accepted · **Date:** 2026-10-04

## Context
The PRD asks for 1,000 orders/s sustained, 99.99% capture/promise availability, a 12-week go-live and a product that
one mid-market team can operate. Fourteen bounded contexts (capture, state engine, workflow, decision fabric, sourcing,
inventory, monitoring, returns, finance, agents, simulation, analytics, orchestration, platform) need to evolve independently.

## Decision
One deployable (`oms-app`) assembled from Maven modules with one-way dependencies (see ARCHITECTURE.md §1). Each
module owns its tables (Flyway version ranges), its REST surface and its public Java API; cross-module calls go
through those interfaces or through ledger events. Horizontal scale = more instances of the same jar behind a load
balancer; Postgres row locks and `FOR UPDATE SKIP LOCKED` workers make every instance stateless.

## Consequences
+ Single build, single transaction boundary for "write rows + append event" (exactly-once per order, NFR-06).
+ Modules can be split into services later along the existing seams (Kafka outbox already exists).
− One JVM failure domain per instance; mitigated by multi-instance deployment and graceful shutdown.
