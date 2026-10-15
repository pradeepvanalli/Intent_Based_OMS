# ADR-003: Jev (TypeSafe AI System One) as the default typed-decision provider with a mandatory fallback chain

**Status:** accepted · **Date:** 2026-10-04

## Context
Jev returns only typed values (Choice ≤255 options, Score 2–10 levels, Noul probability) with calibrated confidence in
70–500 ms at $0.042 per million input tokens (PRD §9). It entered early access on 15 Sep 2026 and is hosted-only.

## Decision
`JevDecisionProvider` is the default `provider` for every L1 decision. Every decision definition also declares
`fallbackProvider` (LLM structured output or `heuristic`) and an L0 `default`. The runtime enforces GR-07: a call past
`timeoutMs` (800 ms) or a 429/529 after one retry falls through the chain and ends at the deterministic default; order
capture never calls a model synchronously. Confidence for Noul answers is `|2p − 1|` so all primitives share one gate.

## Consequences
+ Model-agnostic (design principle 6); kill switches per decision/provider/tenant; shadow mode measures accuracy before autonomy.
− Jev writes no rationale: explanations come from decomposed reason-code Nouls and the stored trace (FR-RET-03, FR-XAI-01).
