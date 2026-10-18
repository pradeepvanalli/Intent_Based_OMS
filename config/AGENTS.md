# AGENTS.md — config (tenant configuration as data)

Everything a tenant can change without a deploy lives here as YAML: state models, decisions, compliance rules,
playbooks, SLAs, order types, nodes, rate cards, agents. Read the root `AGENTS.md` first. Full key-by-key reference:
`docs/CONFIGURATION.md`. Authoring guides: `docs/DECISIONS_GUIDE.md`, `docs/STATE_MODELS_GUIDE.md`.

## Layout

`config/tenants/<tenant>/` — `demo` (seeded by default, see `oms.demo.tenants`), `starter` (blank template, copy
this for a new tenant, never copy `demo` or a reference tenant), `retail`/`electronics`/`apparel` (reference
implementations, each a complete fictional business with five scripted scenarios — see
`docs/REFERENCE_IMPLEMENTATIONS.md`). Each tenant folder is loaded idempotently at startup by a matching `*Loader`
in the owning module (`DecisionDefinitionLoader`, `StateModelLoader`, `NodeLoader`, and so on) — same shape, same
keys, across every tenant; a tenant's `README.md` documents what's distinctive about it, not a different schema.

## Rules that matter more than usual here, because this directory is the product

* **No secrets, ever.** Agent secrets in `agents/agents.yaml` are placeholders the loader replaces at seed time,
  never real values. `.env` (gitignored) is the only place a real key belongs.
* **No real company or person.** Every reference tenant's business, name, and data are fictional, invented
  specifically to not resemble anything real — this is checked in review, not just convention.
* **Every decision a scenario touches needs a golden set** with a case for every option and every rule boundary,
  evaluated at the production gates (0.90 accuracy, 0.05 calibration) unless the tenant's `README.md` states and
  justifies a different bar.
* **Policy that must never be a judgement call is a compliance rule or an L0 CEL rule in the decision YAML, not a
  prompt.** If you're tempted to word something into a decision's instructions ("never approve this for flagged
  customers"), it almost certainly belongs as an L0 rule instead — see any existing decision YAML's `rules:` block.

## Adding a decision to an existing tenant

Write the YAML (`decisions/<name>.yaml`, follow `docs/DECISIONS_GUIDE.md` and an existing decision of similar
shape — payment_risk.yaml and rto_risk.yaml are recent, well-commented examples of a T0/T1 fraud-style decision if
that's the closest match to what you're adding), add its golden set, and if a `DemoCatalogueIntegrationTest`-style
test in `oms-decision` hardcodes the file count for that tenant's `decisions/` directory, update that count in the
same change — this is the single most common thing a new contributor misses (see `backend/oms-decision/AGENTS.md`).

## Adding a whole new tenant

Follow `CONTRIBUTING.md`'s "Reference implementation contribution guide" in full if it's a reference
implementation (it's a four-part process: the folder, the `README.md`, the scenario script, and the documentation
updates — two maintainer reviews required). For a one-off or private tenant, `scripts/new-tenant.sh <id> "<Name>"`
copies `starter/` and wires it through the real tenant-creation API, which is the right starting point either way.
