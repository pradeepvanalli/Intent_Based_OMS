# Examples

Runnable examples against a fresh `scripts/bootstrap.sh` install (tenant `demo`, `admin`/`admin`, http://localhost:8080).
Every request shape here follows `docs/API.md` and `docs/MODULE_NOTES.md`; if the two ever disagree, the controller
source wins and the example should be fixed.

| Folder | Contents |
|---|---|
| `curl/` | Bash scripts: `_lib.sh` (login, helpers), `capture_order.sh`, `promise.sh`, `modify_order.sh`, `return.sh`, `bulk_resource.sh`, `studio_lifecycle.sh` |
| `python/` | `intentoms_client.py` (small REST client), `place_order.py` (capture → view → why → ship → deliver), `agent_mcp_client.py` (HMAC-signed MCP calls: tools/list, mandate, order_create, order_get) |
| `typescript/` | `intentoms.ts` (typed fetch client, Node 18+ / Deno / browser), `place-order.ts` |
| `webhooks/` | `wms_simulator.py` (drives shipped → delivered with correct `X-Webhook-Signature`), `verify_signature.py` (verifies outbound `X-OMS-Signature` and `X-UCP-Signature`, runs as a tiny receiver) |
| `extensions/` | Java SPI implementations: `CustomPlaybookAction`, `CustomDecisionProvider`, `CustomAddressVerifier`, `CustomPaymentGateway` |
| `decisions/` | Extra annotated decision definitions: `vip_appeasement.yaml`, `carrier_service_choice.yaml` |
| `state-models/` | `order_line.preorder.yaml`, a pre-order line model with a release-date timer and substitute offer |
| `prompts/` | Copilot prompts for common retail policies with the outcome each should produce |

Environment variables used by the scripts: `BASE` (default `http://localhost:8080`), `TENANT` (`demo`), `OMS_USER` /
`OMS_PASSWORD` (`admin`/`admin`), `WEBHOOK_SECRET` (when `webhooks.inbound_secret` is set).
