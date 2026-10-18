# AGENTS.md — oms-agent (agent commerce)

Registry, mandates, MCP server, UCP order capability, injection screening, evidence packs — the layer that lets an
AI shopping agent place a real order with proof of what it was authorized to do. Read the root `AGENTS.md` first.
Full signatures: `docs/MODULE_NOTES.md` § oms-agent. This file is the trust model and the sharp edges.

## The trust model

Four agent trust tiers (0 read-only through 3 internal ops), each mapped to a fixed permission set in
`RolesByTier`. Tier changes are **always** an explicit admin action (`AgentRegistry.update()`), never automatic —
`AgentReputationLearner` (in this module, built for SOO) computes a reputation score from mandate compliance and
injection-screen history, but it only ever writes a `LearnedFact`, it never calls `update()` itself. If you're
adding anything that influences trust, keep it on the recommendation side of that line.

## Mandates are the enforcement point, not a formality

Every agent-placed order must verify against a `Mandate` (`MandateService.verify`) before capture: agent match, not
expired, active, currency and amount within the spend limit, and a buyer reference match when supplied. A mandate
is single-use (`consume`). If you're adding a new agent-facing write path, route it through mandate verification —
don't assume the caller already did it upstream.

## Injection screening treats text as data

Any free text from an agent or customer (an instruction, a note, a gift message) goes through `InjectionScreen`
before it's anywhere near a model prompt. The screen runs through the normal decision fabric by default (the
`injection_screen` typed decision) and only falls back to a deterministic regex heuristic when the decision fabric
itself is degraded — so screening never silently passes just because a provider is down. **Never concatenate
untrusted agent/customer text directly into an LLM prompt** anywhere in this module or in code that calls into it;
wrap it as explicitly untrusted data the way the existing copilot and `InjectionScreen` callers do.

## MCP tools

Each tool is a `@Component implementing McpTool` in `.../mcp/`. `readOnly()` must be accurate — it drives the
`readOnlyHint` annotation the agent client sees, and ownership restriction (`ctx.ownershipRestricted()`, true for
tier-0 agents) must be checked explicitly inside `call()`; it isn't enforced for you. Before adding a tool, check
whether an existing one already covers it — there are 11 (and 2 more, read-only, added for SOO:
`learning_status`/`explain_policy_change`) and duplicating one with slightly different semantics is a common
mistake. No MCP tool may approve, promote or publish anything — if your tool needs to trigger something that
mutates a gated artifact (a decision, a proposal), it calls the same REST endpoint a human would, under the same
permission check.

## Evidence packs are the actual product here

`EvidencePackService.build(orderId)` assembles agent identity, mandate + signature validity, the raw instruction
text, the injection-screen result, the full decision trail, and a SHA-256 digest over the order's event ledger.
This is what makes "we let an AI agent buy things" defensible instead of a liability — if you touch the mandate or
injection-screen code paths, make sure whatever you change is still fully represented in the evidence pack, not
just in the live behavior.
