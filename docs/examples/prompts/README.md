# Copilot prompts

Ready-to-paste prompts for the copilot (`POST /api/v1/copilot/changes {text, scope?}` for policy changes,
`POST /api/v1/copilot/bootstrap` for a tenant, `POST /api/v1/copilot/ops/ask {question}`, `GET /api/v1/copilot/explain/{recordId}`,
`POST /api/v1/copilot/cs/draft {orderId, message}`; or the copilot panel in the studio) with the outcome each should
produce. A change set's `changes[]` carry `kind`, `target`, `rationale`, `diff`, `validation` and `riskTier`;
`POST .../changes/{id}/stage {changeIds}` turns decision changes into studio drafts and everything else into draft
artefacts (`docs/API.md` "Copilot"). The copilot writes **drafts**: a new decision version, a tenant configuration proposal, an
explanation or a reply text. It never publishes, never changes a band threshold on its own and never moves money
(GR-14); a person reviews the draft in the studio and runs the approval path. The text you type is stored as data and
screened for injected instructions before any model sees it (GR-08).

How to read "expected outcome": the draft the copilot should produce, which decision it touches, and what the
reviewer should check before submitting. Exact artefacts depend on your tenant configuration; the shapes below assume
the starter or demo tenant.

| File | Theme |
|---|---|
| `bootstrap_tenant.md` | Describe your business, get a starter configuration |
| `returns_policies.md` | Fast lanes, store credit, final sale, keep-it, high-value review |
| `sourcing_policies.md` | Ship complete for gifts, margin floor, node preferences, same-day radius |
| `exceptions_and_sla.md` | Playbooks, SLAs, carrier delays, stuck orders |
| `agents_and_fraud.md` | Agent limits, mandate rules, address risk |
| `ops_questions.md` | Operations Q&A, explanations, customer-service replies |

Conventions the copilot follows when drafting a decision change: it prefers an L0 rule when the request is a hard
policy ("always", "never", a numeric threshold), a criteria wording change when the request is about judgement, and it
adds a lint, impact (last 30 days) and evaluation report to the draft. If the request would weaken a guardrail (remove
an escape option, raise a tier's autonomy, include a protected attribute) the copilot refuses and says why.
