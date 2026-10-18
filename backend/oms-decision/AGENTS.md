# AGENTS.md — oms-decision

The decision fabric: every judgement call in the system runs through this module. Read the root `AGENTS.md` first.
Full signatures: `docs/MODULE_NOTES.md` § oms-decision. This file is the mental model and the sharp edges.

## The mental model

A decision is a YAML definition (`config/tenants/<tenant>/decisions/<name>.yaml`), not Java code, loaded by
`DecisionDefinitionLoader`. It has a risk tier (T0 lowest stakes, T3 never autonomous), one or more typed questions
each with an enumerated set of options plus a mandatory escape option, L0 CEL rules that win outright on a match,
and a `default`. Calling it: `DecisionService.decide(name, input, DecisionOptions.live(subjectType, subjectId,
actor))` → `DecisionResult`. It never throws on an AI failure — a down provider degrades to `Layer.DEFAULT` with
`details.defaultReason`, so **always check `result.acts()`**; `requiresHuman == true` means the value is a
recommendation only, not something to act on.

Pipeline, in order: L0 rules (first match wins, no model touches a rule) → kill switches (global, per-decision,
per-provider) + daily budget → L1 (the primary provider, confidence-scored) → bands (`act`/`confirm`/`escalate` by
the decision's own thresholds) → L2 on `confirm` for T1/T2 (a second model, same enumerated options, strict JSON,
accepted only at or above the act threshold) → L3 escalation for anything still unresolved at T2+/T3. A T1 decision
in the confirm band with no L2 verdict silently applies the definition's `default` rather than escalating — this is
intentional (keeps degraded mode from flooding the escalation queue), not a bug if you see it in a trace.

## Before you add or change a decision

1. Every `Choice` question needs an escape option; every definition needs a `default`. The YAML loader and the
   lint pass both reject a definition missing either.
2. Add a golden set (`golden-sets/<name>_gold.json`) with a case for every option and every rule boundary.
   `DecisionStudioService.evaluate()` gates publish on accuracy-in-act-band and calibration error against it.
3. **Never publish by calling anything that mutates `decision_definition` outside `DecisionStudioService`'s own
   `createDraft → edit → lint → evaluate → publish` path** (or its `emergency=true` variant, which still forces a
   48-hour retro-review deadline). This is the one rule every other module that proposes a change to a decision —
   `oms-copilot`'s config copilot, `oms-learning`'s promotion gate — is built around respecting.
4. Document the input shape in `docs/MODULE_NOTES.md` § oms-decision (there's a running list of every demo
   decision's top-level input keys there — follow that format).

## Providers

`ai.intentoms.decision.provider.DecisionProvider` SPI, registered Spring beans, selected by `name()` referenced in
a definition's `provider:` key: `jev` (TypeSafe System One via OpenRouter), `llm.anthropic`, `llm.openai`
(OpenAI-compatible, including any `sk-or-` OpenRouter key), `heuristic` (deterministic, no AI key needed — this is
what the demo tenants run on by default and what every test must work on; **never write a test that requires a
real AI key**). Add a new provider as a `@Component` implementing the SPI, not by branching inside the runtime.

## Testing

`HeuristicDecisionProvider` exists specifically so tests don't need network access or an API key; WireMock is the
house pattern for an HTTP provider test (see `JevDecisionProviderTest`/`OpenAiCompatibleDecisionProviderTest`).
`DemoCatalogueIntegrationTest` hardcodes the count of decision YAML files in the demo tenant's `decisions/`
directory — adding a new demo-tenant decision means bumping that number, not just adding the file (this has broken
more than one otherwise-correct change).
