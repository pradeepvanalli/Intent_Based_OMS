/**
 * Capture an order and follow it (TypeScript). Run with Node 18+:
 *   npx tsx docs/examples/typescript/place-order.ts
 * Environment: BASE, TENANT, OMS_USER, OMS_PASSWORD, WEBHOOK_SECRET (when webhooks.inbound_secret is set).
 */
import { IntentOms, OmsError } from "./intentoms.ts";

const oms = new IntentOms({ base: process.env.BASE ?? "http://localhost:8080", tenant: process.env.TENANT ?? "demo" });
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const { principal } = await oms.login(process.env.OMS_USER ?? "admin", process.env.OMS_PASSWORD ?? "admin");
  console.log("logged in as", principal.id);

  const ts = Date.now();
  const promise = await oms.promise([{ sku: "HOOD-GRY-M", qty: 1 }], "98101", "WA");
  console.log("promise:", promise.date, "from", promise.fromNodeId, `${Math.round(promise.onTimeProbability * 100)}% on time`);

  let order;
  try {
    order = await oms.captureOrder({
      channel: "web", channelOrderId: `TS-${ts}`, orderType: "standard_ship", customerRef: "cust-ts-1",
      customer: { ref: "cust-ts-1", name: "TS Customer", email: "ts@example.com", tier: "silver", history: { split_acceptance_rate: 0.5 } },
      lines: [
        { lineId: "1", sku: "HOOD-GRY-M", qty: 1, unitPrice: { amount: 68, currency: "USD" } },
        { lineId: "2", sku: "CAP-NVY", qty: 1, unitPrice: { amount: 32, currency: "USD" } },
      ],
      addresses: [{ type: "ship_to", name: "TS Customer", line1: "400 Pine St", city: "Seattle", state: "WA", zip: "98101", country: "US" }],
      tenders: [{ type: "card", token: "tok_visa_ok", amount: { amount: 110, currency: "USD" } }],
      notes: [{ source: "customer", text: "No rush, one box is fine." }],
      intentChoices: ["ship_complete"],
      shipMethod: "ground",
    }, { idempotencyKey: `ts-${ts}` });
  } catch (e) {
    if (e instanceof OmsError) { console.error("capture failed", e.status, e.problem.code, e.problem.detail, e.problem.issues); process.exit(1); }
    throw e;
  }
  console.log("order", order.id, order.status);

  for (let i = 0; i < 20; i++) {
    const v = await oms.orderView(order.id);
    if (v.workflow?.currentStep === "await_fulfilment") break;
    await sleep(1000);
  }
  const view = await oms.orderView(order.id);
  console.log("status", view.order.status, "| intent", view.order.intent?.policy, "| groups", view.fulfilmentGroups.map((g) => [g.nodeId, g.status]));

  for (const rec of await oms.decisionRecords("order", order.id)) {
    const t = await oms.why(rec.id);
    console.log(`  ${t.decision.padEnd(22)} layer=${t.layer} band=${t.band} value=${JSON.stringify(t.value)} confidence=${(t.confidence ?? 0).toFixed(2)}`);
  }

  const node = view.fulfilmentGroups[0]?.nodeId ?? "DC-NJ";
  const secret = process.env.WEBHOOK_SECRET;
  await oms.wmsEvent(node, { eventId: `ts-ship-${ts}`, type: "shipped", orderId: order.id, carrier: "UPS", service: "ground", tracking: `1Z${ts}` }, secret);
  await sleep(2000);
  await oms.carrierEvent({ eventId: `ts-deliver-${ts}`, type: "delivered", orderId: order.id, tracking: `1Z${ts}` }, secret);
  await sleep(2000);
  const done = await oms.orderView(order.id);
  console.log("final status", done.order.status, "| lines", done.lineStatuses.map((l) => [l.entityId, l.quantities.map((q) => `${q.state}:${q.qty}`)]));
}

main().catch((e) => { console.error(e); process.exit(1); });
