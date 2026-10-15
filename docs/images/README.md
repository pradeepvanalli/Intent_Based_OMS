# Screenshots to capture

Images referenced by `README.md`, `docs/site/index.html` and `docs/launch/`. Capture at 1920x1080, light theme, browser
zoom 110%, Chrome with the bookmark bar hidden. Keep each PNG under 400 KB (`oxipng -o 4` or `pngquant --quality
70-90`). Existing captures from the previous release are in `ui/screenshots/`; the console pages they show are
unchanged, so they can be reused where noted.

Log in as tenant `retail`, user `admin`, password `admin` unless the row says otherwise. Set `OPENROUTER_API_KEY`
first so traces show `provider jev` rather than `heuristic`. Run `scripts/reference/retail.sh --load-only` and then
`scripts/reference/retail.sh --only R1` so there is data on every page.

## Required (referenced by README and the site)

| File | Page | State to capture |
|---|---|---|
| `hero-playground.png` | `/playground` | The gift order placed (hoodie, shoes, cap to Seattle, note "birthday gift for my dad, please send everything together in one box"). Trace fully animated: L0 (no rule fired), L1 `fulfilment_intent` = `ship_complete` with confidence, latency and cost visible, `sourcing_node_score` lit, the plan card showing one package from DC-NJ, and the network map with the single line to Seattle. Crop nothing; this is the README hero. |
| `decision-trace.png` | `/orders/<id>` Why drawer | Open the order from the hero capture, open Why on `fulfilment_intent`. The trace line `value ship_complete | layer L1_SYSTEM_ONE | band ACT | provider jev` must be legible, with the questions (`is_gift`, `split_ok`) and their confidences, and the customer-safe toggle visible but not selected. |
| `margin-replay.png` | `/replay` | Bundled sample loaded (no upload). Report header with the three numbers (shipping saved, margin recovered, splits avoided), the policy selector showing which policies were applied, and the first rows of the per-order table. |
| `copilot-changeset.png` | `/copilot` | Prompt: "Never split an order that contains hazmat items. Marketplace orders below a 10 percent margin need finance approval." Change set rendered: summary, two change cards expanded enough to show a diff, risk tier, approvals required and generated golden cases. Stage button visible, not yet clicked. |

## Recommended (used by the site and the posts)

| File | Page | State to capture |
|---|---|---|
| `playground-split.png` | `/playground` | Second order with note "I need the running shoes before Friday's race, the rest can come whenever." Trace shows `earliest_partial`; map shows two lines. Used in the blog post and series post 1. |
| `studio-impact.png` | `/studio/split_acceptability` | The staged draft from the copilot capture with the Impact tab open: flips, band mix, escalations over the last 30 days. `ui/screenshots/12-studio-impact.png` can be reused if the studio has not changed. |
| `track-page.png` | `/track/<token>` | Public tracking page for the hero order, logged out (open in a private window). Status, promise date, and the customer-safe explanation sentence visible. |
| `ai-economics.png` | `/analytics` (AI Economics tab) | Tenant `demo` after `scripts/usecases/run-all.sh`: automation rate, cost per 1k decisions, human decisions avoided, p95 latency by layer. Needs at least one run with a key so the layer chart has L1 data. |
| `showcase.png` | `/showcase` | The guided tour landing with all cards visible and the first step highlighted. |
| `agents-evidence.png` | `/agents` | Tenant `demo` after UC4: the agent list with trust tiers, and one order's evidence pack opened. `ui/screenshots/14-agents.png` shows the list only. |
| `control-tower-dark.png` | `/control-tower` | Dark theme, tenant `retail` after R4 (holiday surge): the capacity exception visible. For the site's dark-mode section; `ui/screenshots/18-control-tower-dark.png` is the previous version. |
| `returns-keep-it.png` | `/returns/<rma>` | Tenant `apparel` after A3: the keep-it decision with the economics on the trace. |

## Reusable from `ui/screenshots/`

`02-control-tower.png`, `05-why-drawer.png`, `10-studio-editor.png`, `11-studio-questions.png`, `13-state-model.png`,
`15-simulation.png`, `copilot-02-changeset.png`, `copilot-07-bootstrap-nodes.png`, `copilot-14-margin-guardian.png`,
`live-03-why-drawer.png`. Copy into this folder under the name a document references rather than linking into
`ui/screenshots/`, so the docs stay self-contained.

## Video stills

`docs/launch/DEMO_VIDEO_SCRIPT.md` lists the shots. The title and end cards are plain text on the site's background
colour (`#0f1419` dark or `#fbfaf7` light); no image needed.
