# Intent OMS — Web UI

React 18 + Vite + TypeScript (strict) operator console for Intent OMS. Talks to the Spring backend on `:8080` using the REST contract in `docs/ARCHITECTURE.md` §3.

## Run

```bash
cd ui
npm ci
npm run dev          # http://localhost:5173, proxies /api, /mcp, /actuator → http://localhost:8080
npm run dev:mock     # same, but every API call is served by the in-memory mock (no backend needed)
npm run build        # tsc -b && vite build → dist/
npm run lint         # eslint (typescript-eslint + react-hooks)
npm test             # vitest + @testing-library/react smoke tests
```

Environment variables (Vite, prefix `VITE_`):

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_API_BASE` | `''` | Base URL prepended to every API path. Empty = same origin (dev proxy / backend static hosting). |
| `VITE_MOCK` | unset | `1` switches `src/api/client.ts` to the in-memory mock in `src/api/mock/`. |
| `VITE_PROXY_TARGET` | `http://localhost:8080` | Dev-server proxy target. |

Demo users on tenant `demo` (password = user name): `admin`, `ops`, `cs`, `risk`, `finance`, `strategy`.

## Production build & backend hosting

`../scripts/build-ui.sh` runs `npm ci && npm run build` and copies `dist/` into
`backend/oms-app/src/main/resources/static/` (cleaning the old copy). Vite is configured with `base: '/'` and `build.outDir: 'dist'`.

The app uses client-side routing (`react-router` v6, history mode). **The backend must serve `index.html` for any
non-API path** (`/orders/ORD-1`, `/studio/fulfilment_intent`, …) — i.e. a SPA fallback for every route that is not
`/api/**`, `/mcp`, `/ucp/**`, `/webhooks/**`, `/actuator/**`, `/assets/**`. `SecurityProperties.publicPaths` already lists `/`, `/index.html`, `/assets/**`.

## Structure

```
src/
  api/            fetch client (JWT, X-Tenant-Id, RFC 9457 → toasts, 401 → /login), typed endpoint modules, types.ts
  api/mock/       in-memory mock API (seeded data, YAML samples, route handlers) used when VITE_MOCK=1 and by tests
  auth/           session store (localStorage oms.token / oms.principal), permission helper, RequireAuth guard
  components/     design-system components (Button, Badge, Table, Drawer/Dialog, Tabs, StatTile, Toast, Form fields,
                  JsonTree, YamlView, LayerStepper, ProbBar) + charts/ (Sparkline, Bar/StackedBar, Line, CalibrationCurve)
  components/why/ the Why drawer (FR-XAI-01) — decision trace renderer with override and outcome-label forms
  lib/            formatting, hooks, YAML tokenizer, LCS line diff, layered graph layout, YAML↔model conversion
  pages/          one folder per route group (controlTower, orders, tasks, exceptions, returns, studio, stateModels,
                  agents, simulation, analytics, settings, copilot)
  pages/copilot/  Copilot (/copilot: composer → ChangeSet review → stage/reject; Artifacts tab) and the Bootstrap wizard
                  (/copilot/bootstrap); ChangeSetView is shared, DiffViewer (components/) renders side-by-side + unified diffs
  shell/          app shell: grouped left nav, top bar (tenant, user, roles, global search, theme toggle, logout)
  styles/         design.css (tokens, base, components) and shell.css (shell + page layouts)
```

Design tokens live in `src/styles/design.css` (`:root` light palette, dark via `prefers-color-scheme` and the
`data-theme` toggle persisted in `localStorage['oms.theme']`). Status, tier (T0–T3), band (act/confirm/escalate) and
layer (L0/L1/L2/L3) colours are semantic variables.

## API assumptions the backend must honour

See the hand-off notes in the repository root report; in short:

* `POST /api/v1/auth/login {tenantId,userId,password}` → `{token, principal}`; principal has `permissions[]` with `*` / `domain.*` wildcards.
* List endpoints return `Page<T> = {items,total,offset,limit}`; errors are RFC 9457 problem details with `code` and optional `fields`.
* Decision version actions are addressed as `POST /api/v1/decisions/{name}/versions/{v}/{lint|impact|evaluate|submit|approve|publish}`; `rollback` and `kill-switch` are per decision; `evaluate-now` takes `{input, mode, version?}`.
* `GET /api/v1/state-models/{id}/versions` lists versions; `GET /api/v1/orders/{id}/view` returns the aggregate `OrderView` (order, header + line `EntityStatus`, holds, shipments, payments, refunds, returns, decision records, workflow, timeline, ledger, communications, risk, nextExpectedEvent).
* `GET /api/v1/control-tower/pipeline` returns `{states[], ageBuckets[], cells[{state,ageBucket,count}], byChannel, byNode, byOrderType, openOrders, atRisk, held, generatedAt}`; `/at-risk` returns a list with `riskScore` 0–4 and `reasons[]`.
* `GET /api/v1/decision-records/{id}/why?audience=internal|customer` returns the `DecisionTrace` shape in `src/api/types.ts`.

### Copilot (`oms-copilot` module, `src/api/copilot.ts`)

The UI codes against the REST contract in `src/api/types.ts` (`CopilotStatus`, `ChangeSet`, `ProposedChange`, `DraftArtifact`,
`OpsAnswer`, `CopilotExplanation`, `CsDraft`). Assumptions the backend should honour:

* `ProposedChange.diff` is a unified diff; when absent the UI computes one from `before`/`after` (`src/lib/diff.ts: unifiedDiff`).
  `before: null` means a new file and the viewer shows the content instead of a split diff.
* `POST /copilot/changes` accepts `context: { answers: { [question]: answer }, previousChangeSetId }` when a user answers the
  clarifying questions and resubmits; the response is a new `ChangeSet`.
* `POST /copilot/changes/{id}/stage` must reject (422) a change whose `validation.ok` is false; staged decision changes return
  `staged.ref = "name@version"` and the UI links to `/studio/{name}`; all other kinds return an artefact id and the UI links to
  `/copilot?tab=artifacts&artifact={id}`.
* `GET /copilot/changes` returns items newest first. `GET /copilot/artifacts?status=` filters by `draft|applied|rejected`.
* Permissions: the Copilot and Bootstrap pages need any of `decision.edit`, `statemodel.edit`, `config.admin`; applying an
  artefact needs `statemodel.edit` for state models / order types / SLAs and `config.admin` for config, playbooks and
  compliance rules; "Ask the OMS" on the Control Tower needs `analytics.read`; Explain and CS draft follow the order/decision
  read permissions of the page they live on.
* `GET /copilot/status.available=false` disables the change composer (a banner points at Bootstrap, which keeps working with
  deterministic fallbacks). In the mock, `ai.kill_switch` or `provider.kill_switch.llm.anthropic` flips it.
* Margin Guardian digest (`GET /copilot/digest?days=`, `POST /copilot/digest/run?days=`, `GET /copilot/digest/latest`,
  `GET /copilot/digest/history?limit=`) is shown on Analytics; a finding's `suggestedPrompt` opens `/copilot?prompt=…`, which
  pre-fills the composer. `validation.warnings` (plain strings) render as warning rows next to structured `issues`.
* Ops answers: any column named `order_id`/`orderId`/`order` or any string value matching `ORD-…` is linked to the order page;
  rows are paginated client-side (10 per page).
