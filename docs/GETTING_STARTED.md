# Getting started: an adopter's guide

This guide takes you from a fresh clone to a running system, through one order end to end, through a policy change
made two ways (studio and copilot), and through the three integrations most retailers connect first: the storefront,
the warehouse and carriers, and shopping agents. Every command below runs against the `demo` tenant that
`scripts/bootstrap.sh` seeds. Request and response shapes are documented in `docs/API.md`; configuration keys in
`docs/CONFIGURATION.md`.

## 1. Run it

Just want to look at it? `docs/QUICKSTART.md` has the Docker one-liner (published image, nothing to install), the
Codespaces button and `scripts/try.sh`, which builds and runs everything in containers without a local JDK or Node:

```bash
scripts/try.sh                 # Docker only: image build (UI + backend), Postgres, Redis, demo seed, login table, browser
scripts/doctor.sh              # not sure what is installed? checks docker, compose, ports, JDK 21, Node 22, memory
```

For development on a local JVM:

```bash
scripts/bootstrap.sh
```

What it does: checks Docker, JDK 21, Maven, Node, curl and python3; copies `.env.example` to `.env` if missing; starts
PostgreSQL (localhost:5440) and Redis with Docker Compose; builds the UI into the backend and the backend jar; starts the
service on :8080; waits for `/actuator/health/readiness` and for the demo tenant to be seeded; prints logins and next
steps. Logs go to `.oms/oms.log`; `scripts/bootstrap.sh --stop` stops the service.

Alternatives: `make infra build run` (service in the foreground under Maven), `make try` / `make down` / `make reset`
for the containerised run, or GitHub Codespaces (`.devcontainer/`, which runs `scripts/bootstrap.sh` on start).
Developer loop details are in `CONTRIBUTING.md`; `make help` lists every target.

## 2. Log in

UI: open http://localhost:8080, tenant `demo`, user `admin`, password `admin`. Other demo users: `ops` (ops analyst),
`cs` (CS rep), `risk` (risk analyst), `finance` (finance controller), `strategy` (strategy lead); the password is the
user name. The seeder loads four sample tenants (`OMS_DEMO_TENANTS=demo,retail,electronics,apparel`) with the same six
users each; this guide uses `demo`, the others are described in `docs/QUICKSTART.md`. Roles and their permissions are
in `docs/CONFIGURATION.md` ("Roles").

API: obtain a JWT and keep it in a variable.

```bash
export TOKEN=$(scripts/token.sh admin admin demo)       # POST /api/v1/auth/login {tenantId,userId,password}
export BASE=http://localhost:8080
alias oms='curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json"'
oms $BASE/api/v1/auth/me | python3 -m json.tool          # who am I, which permissions
```

For integrations, issue an API key instead of using a session token (requires `config.admin`):

```bash
oms -X POST $BASE/api/v1/auth/api-keys -d '{"subjectId":"wms-connector","roles":["ops_analyst"],"label":"WMS"}'
# → {"keyId":"...","apiKey":"oms_<id>_<secret>","note":"Store this key now; it is not retrievable later."}
```

Use it as `X-Api-Key: oms_...` or `Authorization: Bearer oms_...`.

## 3. Place an order through the API

A promise at cart time is public (no token, tenant header only):

```bash
curl -s -X POST $BASE/api/v1/promise -H 'Content-Type: application/json' -H 'X-Tenant-Id: demo' -d '{
  "lines":[{"sku":"HOOD-GRY-M","qty":1},{"sku":"SNK-RUN-10","qty":1}],
  "destinationZip":"60611","destinationState":"IL","channel":"web","shipMethod":"ground"}'
# → {"date":"...","onTimeProbability":0.94,"fromNodeId":"DC-NJ","shipMethod":"ground","fromCache":false,"latencyMs":..,"alternatives":[...]}
```

Capture the order. `Idempotency-Key` makes retries safe; the pair (channel, channelOrderId) is unique too.

```bash
TS=$(date +%s)
ORDER=$(oms -X POST $BASE/api/v1/orders -H "Idempotency-Key: gs-$TS" -d @- <<JSON
{"channel":"web","channelOrderId":"GS-$TS","orderType":"standard_ship","customerRef":"cust-1001",
 "customer":{"ref":"cust-1001","name":"Dana Whitfield","email":"dana@example.com","tier":"gold",
             "history":{"split_acceptance_rate":0.2,"returns_12m":1}},
 "lines":[{"lineId":"1","sku":"HOOD-GRY-M","qty":2,"unitPrice":{"amount":68.00,"currency":"USD"}},
          {"lineId":"2","sku":"SNK-RUN-10","qty":1,"unitPrice":{"amount":145.00,"currency":"USD"}}],
 "addresses":[{"type":"ship_to","name":"Dana Whitfield","line1":"233 S Wacker Dr","city":"Chicago","state":"IL","zip":"60606","country":"US"}],
 "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":320.00,"currency":"USD"}}],
 "notes":[{"source":"customer","text":"Birthday gift, please send everything together in one box."}],
 "shipMethod":"ground"}
JSON
)
OID=$(echo "$ORDER" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'); echo $OID
```

Capture returns `201` with the stored order (or `200` and the existing order on a replay). Validation failures return
`422` with `code: validation_failed` and an `issues[]` list. Capture never calls a model synchronously (GR-07): the
workflow does that in the next step.

## 4. Watch it flow

The workflow `order.standard_ship` runs: validate → infer intent (decision `fulfilment_intent`) → authorise payment →
source (decision `sourcing_node_score`, margin floor) → reserve → release lines to the WMS → wait for fulfilment
events. A few seconds after capture:

```bash
oms "$BASE/api/v1/orders/$OID/view" | python3 -c '
import sys,json; d=json.load(sys.stdin); o=d["order"]
print("status", o["status"], "| intent", (o.get("intent") or {}).get("policy"), "| groups", [(g["nodeId"], g["status"]) for g in d.get("fulfilmentGroups",[])])
print("workflow", (d.get("workflow") or {}).get("status"), "at", (d.get("workflow") or {}).get("currentStep"))
print("line states", [(l["entityId"], [(q["state"], q["qty"]) for q in l["quantities"]]) for l in d["lineStatuses"]])'
```

The order view aggregates order, header and line status (quantity buckets), holds, shipments, payments, refunds,
returns, decision records, workflow, timeline, ledger, communications, risk and the next expected event.

Ask *why* a decision came out the way it did:

```bash
REC=$(oms "$BASE/api/v1/decision-records?subjectType=order&subjectId=$OID" | python3 -c 'import sys,json; print(next(r["id"] for r in json.load(sys.stdin) if r["decision"]=="fulfilment_intent"))')
oms "$BASE/api/v1/decision-records/$REC/why" | python3 -m json.tool | head -60
oms "$BASE/api/v1/decision-records/$REC/why?audience=customer"     # customer-safe wording (GR-15)
```

Ship and deliver it by playing the warehouse and the carrier (the webhook paths are public; the tenant comes from the
header; a signature is required once `webhooks.inbound_secret` is set):

```bash
NODE=$(oms "$BASE/api/v1/orders/$OID/view" | python3 -c 'import sys,json; print(json.load(sys.stdin)["fulfilmentGroups"][0]["nodeId"])')
curl -s -X POST $BASE/webhooks/wms/$NODE -H 'Content-Type: application/json' -H 'X-Tenant-Id: demo' \
  -d "{\"eventId\":\"wms-$TS\",\"type\":\"shipped\",\"orderId\":\"$OID\",\"carrier\":\"UPS\",\"service\":\"ground\",\"tracking\":\"1Z$TS\"}"
curl -s -X POST $BASE/webhooks/carrier -H 'Content-Type: application/json' -H 'X-Tenant-Id: demo' \
  -d "{\"eventId\":\"car-$TS\",\"type\":\"delivered\",\"orderId\":\"$OID\",\"tracking\":\"1Z$TS\"}"
```

`shipped` signals the workflow, moves the line quantities `packed → shipped`, captures payment and notifies the
customer; `delivered` completes the order. The ledger shows every event: `GET /api/v1/admin/ledger/order/$OID`.

Run a return and the control tower while you are here: `scripts/demo.sh` does exactly this sequence with commentary.

## 5. Change a policy in the studio

Policies are decision definitions. Open **Policy Studio → return_risk** in the UI or follow the API path (this is UC5 in
`scripts/usecases/uc5-policy-change.sh`):

1. Read the live version: `GET /api/v1/decisions/return_risk` lists versions; `GET .../versions/{v}` returns the YAML.
2. Create a draft: `POST /api/v1/decisions/return_risk/drafts {"yaml": "..."}` with the edited YAML (the version number
   and `status: draft` are set by the studio). Or edit fields in place:
   `PATCH /api/v1/decisions/return_risk/versions/{v}` with dotted paths such as `{"bands.act.confidence_gte": 0.85}`.
3. Lint: `POST .../versions/{v}/lint` flags overlapping options, a missing escape option, PII or protected attributes in
   prompts, rule values that are not options, unknown state paths.
4. Impact: `POST .../versions/{v}/impact {"days":30,"limit":200}` re-runs the last 30 days of real inputs against the
   draft and reports flips, band mix, escalations, cost and latency deltas.
5. Evaluate: `POST .../versions/{v}/evaluate` runs the golden set; the gate is `evaluation.minAccuracyInActBand` and
   `maxCalibrationError` in the YAML.
6. Submit with a reason and ticket: `POST .../versions/{v}/submit {"reason":"...","ticket":"OMS-412"}`.
7. Approve as a **different** user (`POST .../versions/{v}/approve`); T2 and T3 decisions need two approvers.
8. Publish: `POST .../versions/{v}/publish {"rollout":"canary:25"}` then `{"rollout":"live"}`; `shadow` runs the version
   alongside the live one without acting.
9. If needed: `POST /api/v1/decisions/return_risk/rollback {"reason":"..."}` or
   `POST .../kill-switch {"on":true,"reason":"..."}`.

Every step writes `decision.definition.*` ledger events and audit rows (`GET /api/v1/admin/audit?objectType=decision_definition`).

## 6. Change a policy with the copilot

The copilot turns a sentence into a proposal, and staging it creates the draft of step 2; a person still does steps
3–9. In the UI open the copilot panel in the studio; over the API:

```bash
CS=$(oms -X POST $BASE/api/v1/copilot/changes -d '{"text":"Gold customers returning an item under $250 with a receipt should get an instant refund without a risk score.","scope":"return_risk"}')
echo "$CS" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["id"], d["summary"]); [print(" ", c["id"], c["kind"], c["target"], "valid" if c["validation"]["ok"] else c["validation"]["issues"], "tier", c["riskTier"], "approvals", c["requiresApprovals"]) for c in d["changes"]]; print("questions:", d["questions"])'
CSID=$(echo "$CS" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
oms "$BASE/api/v1/copilot/changes/$CSID/changes/c1/explain" | python3 -m json.tool          # why this change, in plain language
oms -X POST "$BASE/api/v1/copilot/changes/$CSID/changes/c1/tests" | python3 -m json.tool    # golden-set style cases for it
oms -X POST "$BASE/api/v1/copilot/changes/$CSID/stage" -d '{"changeIds":["c1"]}' | python3 -c 'import sys,json; print([c.get("staged") for c in json.load(sys.stdin)["changes"]])'
# → [{"target": "return_risk@3", ...}]  — a studio draft; continue with lint → impact → evaluate → submit → approve → publish
```

Expected result: one `decision_rule` change on `return_risk` adding an L0 rule (something like
`when: input.customer.tier == 'gold' && input.return.receipt == true && input.return.value_usd <= 250` → `then: 0`),
already validated (CEL parsed, value is an allowed level), with `riskTier: T2` and `requiresApprovals: 2`. Staging
creates the draft version that then appears in the studio's version list. Non-decision changes (an SLA, a playbook, a
tenant setting, a state model) become draft artefacts under `GET /api/v1/copilot/artifacts` and are applied with
`POST /api/v1/copilot/artifacts/{id}/apply {"reason": "..."}` by someone with `config.admin` or `statemodel.publish`.
More prompts, with what each should produce, are in `docs/examples/prompts/`; the full endpoint list is in
`docs/API.md` "Copilot".

The copilot never publishes and never moves money; a band-threshold change is only ever a proposal a human stages and
publishes (GR-14). Everything it writes carries the text you gave it as data, which passes through the injection
screen first (GR-08), and its spend counts against the tenant's daily AI budget (GR-17). Without an LLM key the
endpoints still answer, with `generated: false` and a `fallbackReason`.

## 7. Connect Shopify

1. Set the shared secret so the webhook is verified:
   `PUT /api/v1/admin/config/shopify.webhook_secret {"value":"<Shopify webhook signing secret>","reason":"connect store"}`.
2. In Shopify, create a webhook for `orders/create` pointing at `https://<your host>/webhooks/shopify?tenant=<tenantId>`
   (or send `X-Tenant-Id`). Shopify signs the raw body with HMAC-SHA256 and sends it base64 in `X-Shopify-Hmac-Sha256`;
   the controller compares in constant time and replays of the same webhook id return the existing order.
3. Map SKUs: lines whose SKU is not in the catalogue are rejected unless `capture.allow_unknown_sku` is true.

## 8. Connect WMS and carrier webhooks

Set `webhooks.inbound_secret` (`PUT /api/v1/admin/config/webhooks.inbound_secret {"value":"...","reason":"..."}`).
From then on every call to `/webhooks/wms/{nodeId}`, `/webhooks/carrier` and `/webhooks/inventory/{nodeId}` must carry
`X-Webhook-Signature: sha256=<hex HMAC-SHA256(secret, raw body)>` (base64 is also accepted) and `X-Tenant-Id`.

| Webhook | Body | Effect |
|---|---|---|
| `POST /webhooks/wms/{nodeId}` | `{eventId, type, orderId, fulfilmentGroupId?, lines?[{lineId, qty}], carrier?, service?, tracking?, rmaId?, reason?, condition?, note?}` with `type` one of `shipped, short_pick, store_rejected, pick_started, packed, picked, staged_for_pickup, picked_up` (orders) or `received, inspected` (returns, `rmaId` required) | Signals the order workflow and fires line transitions; partial `lines` ship part of a line |
| `POST /webhooks/carrier` | `{eventId, type, orderId?, tracking, rmaId?, shipmentId?}` with `type` one of `in_transit, out_for_delivery, delivered, exception, returned_to_sender, return_scan` | Shipment state, order workflow signals (`delivered`, `carrier_exception`, `returned_to_sender`), return carrier scans |
| `POST /webhooks/inventory/{nodeId}` | `{eventId?, source?, asOf?, positions:[{sku, qty, nodeId?, supplyType?, demandType?, confidence?}]}` or a bare list | Upserts positions (default `on_hand` at the path node); backordered lines on fed SKUs receive `inventory_available` |

All three are idempotent on `eventId`. `docs/examples/webhooks/wms_simulator.py` drives a full shipped → delivered
sequence with correct signatures.

Outbound to a real WMS: set `OMS_WMS_PROVIDER=http` and the tenant keys `wms.<nodeId>.release_url` (or
`wms.release_url`) and `wms.webhook_secret`; the connector posts signed release and cancel documents (contract in
`docs/API.md` "Outbound WMS release documents") and the WMS answers through the webhook above. For carriers, set
`OMS_CARRIER_PROVIDER=easypost` with `EASYPOST_API_KEY`; labels are bought at ship time (`GET /api/v1/shipments/{id}/label`)
and EasyPost tracker events arrive at `POST /webhooks/easypost?tenant=<tenantId>` (set
`carrier.easypost.webhook_secret` so `X-Hmac-Signature` is required). Marketplace orders arrive at
`POST /webhooks/marketplace/<name>` (Amazon SP-API or generic payload, `X-Marketplace-Signature` when
`marketplace.<name>.webhook_secret` is set) or through the Amazon SP-API poller (`AMAZON_SPAPI_*`).
`GET /api/v1/integrations/status` shows which adapters are active. The copilot also helps here:
`POST /api/v1/copilot/ops/ask {"question":"Which orders at DC-TX are at risk today?"}` answers from whitelisted SQL
templates (`GET /api/v1/copilot/ops/templates`) and returns the SQL it ran; `GET /api/v1/copilot/digest?days=7`
summarises the week's KPIs with suggested follow-up prompts. To receive events *from* Intent OMS, create a subscription:
`POST /api/v1/admin/webhooks {"url":"https://...","eventFilter":["order.","return."]}` returns a secret; deliveries are
CloudEvents signed in `X-OMS-Signature: sha256=<hex>` (`docs/examples/webhooks/verify_signature.py`).

## 9. Connect an agent over MCP

1. Register the agent (needs `agent.admin`):
   ```bash
   oms -X POST $BASE/api/v1/agents -d '{"id":"my-shopper","owner":"Acme assistant","protocol":"mcp","trustTier":1,
     "limits":{"allowedActions":["order_get","promise","order_create","return_create"],"maxOrderValueUsd":500,"maxRefundUsd":100,"ratePerMinute":120,"confirmActions":["order_create"]},
     "callbackUrl":"https://agent.example.com/oms-events"}'
   # → {"agent":{...},"secret":"<hmac secret, shown once>","apiKey":"oms_...","apiKeyId":"...","note":"..."}
   ```
2. Sign requests: `X-Agent-Id`, `X-Agent-Timestamp` (epoch seconds), `X-Agent-Signature` = lower-case hex
   HMAC-SHA256(secret, `timestamp\nPOST\n/mcp\nsha256hex(body)`). Alternatively send the API key in `X-Api-Key`.
3. Discover tools: `POST /mcp {"jsonrpc":"2.0","id":1,"method":"tools/list"}` returns only the tools the registry
   allows. Call one with `tools/call`. `docs/examples/python/agent_mcp_client.py` implements the signing and a
   mandate → `order_create` → `order_get` flow.
4. Mandates: a buyer's delegation (`POST /api/v1/mandates {agentId, buyerRef, scope, spendLimit, instructionText,
   expiresAt}`) is consumed by `order_create`; a basket above the spend limit is refused before capture
   (`mandate_spend_limit_exceeded`); instruction text is screened for injection and kept as data.
5. UCP: the same agent can use `GET /.well-known/ucp`, `POST /ucp/v1/orders`, `GET /ucp/v1/orders/{id}`,
   `POST /ucp/v1/orders/{id}/returns` with `UCP-Version: 2026-08-25`. Lifecycle webhooks go to `callbackUrl` signed in
   `X-UCP-Signature`.

## 10. Your own tenant

```bash
scripts/new-tenant.sh acme "Acme Outdoors"
```

copies `config/tenants/starter` to `config/tenants/acme` and loads it through the API (state models, decisions,
golden sets, compliance rules, order types, catalogue, nodes, rate card, playbooks, queues, SLAs, agents, tenant
config). Read `config/tenants/starter/README.md` for what to change first, and `docs/CONFIGURATION.md` for every key.
Then point your storefront, WMS and carrier at the webhooks above with `X-Tenant-Id: acme`.
