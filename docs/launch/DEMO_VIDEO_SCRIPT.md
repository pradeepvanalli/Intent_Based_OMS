# Demo video script (3 minutes)

Target length 3:00. Record at 1920x1080, browser zoom 110%, dark theme off (light theme reads better in feeds).
Logged in as **tenant `retail` (Maple & Main General Store), user `admin`** for the whole recording except the Margin
Replay segment, which is tenant-agnostic. Have `OPENROUTER_API_KEY` set so the trace shows `provider jev`. Run
`scripts/reference/retail.sh --load-only` beforehand so the network map has stores and DCs.

Keep the cursor still while narrating. Cut hard between segments; no transitions.

| Time | Shot | On screen | Narration |
|---|---|---|---|
| 0:00 | Title card | "Intent OMS. Every decision typed, explained, owned by the business." Repo URL below. | "This is Intent OMS, an open-source order management system where every decision is a typed object with a confidence score. Three minutes, four things." |
| 0:08 | Playground, empty form | `/playground`. Form with lines, address, note. Network map on the right with Maple & Main's two DCs and four stores. | "First, the Playground. I am placing an order the way a customer would. A hoodie, shoes, and a cap that is only in stock in New Jersey. The customer writes: birthday gift, please send everything together." |
| 0:25 | Playground, place order | Click Place order. The trace animates: L0 rules (none fired), L1 `fulfilment_intent` lights up with `ship_complete`, confidence, latency and cost; `sourcing_node_score` lights; the plan appears; the map draws one line from DC-NJ to Seattle. | "Watch the trace. Rules first, nothing fires. Then the typed decision: ship complete, confidence 1.0, 600 milliseconds, two thousandths of a cent. One box from New Jersey, cross-country, because that is what the buyer asked for. Every plan that was considered is listed with its cost and margin." |
| 0:50 | Playground, second order | Change the note to "shoes before Friday's race, the rest whenever." Place. Trace shows `earliest_partial`; map draws two lines. | "Same basket, different sentence. Two packages. The decision read the intent; nobody wrote a rule for it." |
| 1:02 | Order detail, Why drawer | Open the order, open Why. Show the line `value ship_complete | layer L1_SYSTEM_ONE | band ACT | provider jev`. Toggle to the customer-safe explanation. | "Everything is stored. The operator sees the layer, the band and the provider. The customer sees a safe sentence on the public tracking page." |
| 1:15 | Copilot | `/copilot`. Type: "Never split an order that contains hazmat items. Marketplace orders below a 10 percent margin need finance approval." Submit. Wait for the change set. | "Second, policy from a sentence. Two sentences a merchandising lead would actually say." |
| 1:35 | Copilot change set | Cards appear: a rule on `split_acceptability`, a tenant setting change, each with a diff, generated golden cases, risk tier and required approvals. Click Stage. | "The copilot answers with a change set: a diff per change, generated test cases, the risk tier, and how many approvals it needs. I stage it. The model never publishes." |
| 1:50 | Studio | `/studio/split_acceptability`. Draft visible. Click Impact: flips over history. Click Evaluate: golden set passes. Submit. | "In the studio the draft gets an impact diff over real history, a golden-set gate, and an approval by somebody other than me. Then a canary, then live, with rollback one click away." |
| 2:05 | Margin Replay, upload | `/replay`. Click "Use bundled sample" (or drag a CSV). Report renders: shipping saved, margin recovered, splits avoided, with the policies used. | "Third, your own orders. Export from Shopify, upload, and see what the policies would have saved last quarter. Shipping avoided, margin recovered, splits avoided. Nothing leaves your machine." |
| 2:25 | Margin Replay, detail | Scroll to a per-order row; click one; the trace for that replayed decision opens. | "Every number traces back to an order and a decision." |
| 2:35 | Showcase | `/showcase`. The guided tour card list: Playground, Copilot, Replay, Agents, Analytics. Hover AI Economics: automation rate, cost per 1k decisions, p95 by layer. | "Fourth, the Showcase: a guided tour of all of it, including the agent channel over MCP and UCP and the AI economics page. Automation rate, cost per thousand decisions, human decisions avoided." |
| 2:48 | Terminal | `make try` running, then the browser at localhost:8080. | "Java, Spring Boot, Postgres. One command. Three reference tenants. Runs without an AI key." |
| 2:55 | End card | Repo URL. "Building a new OMS? Let's talk." LinkedIn URL. | "It is Apache-2.0. If your team is building a new OMS, I want to talk." |

## Capture notes

* Record the Playground segment twice; the trace animation timing depends on provider latency. Keep the faster take.
* If the copilot takes longer than 20 seconds, cut to the change set and say "about a minute later".
* Mute system sounds. Record narration separately and align to the cuts.
* Export a 16:9 master and a 1:1 crop of the Playground segment (0:08 to 1:02) for the LinkedIn announcement.
