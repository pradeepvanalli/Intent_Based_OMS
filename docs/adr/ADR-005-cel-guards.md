# ADR-005: CEL for guards, L0 rules and checks

**Status:** accepted · **Date:** 2026-10-04

Common Expression Language (cel-java) is non-Turing-complete, statically checkable and readable by analysts. All
state-machine guards, L0 rule conditions, decision pre/post checks and compliance rules are CEL over a fixed set of
root variables (`order`, `line`, `plan`, `tenant`, `approval`, `answers`, `input`, …). Publishing validates every
expression (FR-SM-10) and rejects references to undeclared roots. DMN import is deferred to P2 (FR-DEC-03).
