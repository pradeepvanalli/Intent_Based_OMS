/**
 * Minimal typed client for the Intent OMS REST API (fetch-based: Node 18+, Deno, Bun, browsers).
 * Shapes follow docs/API.md. Errors throw `OmsError` with the RFC 9457 problem detail.
 *
 *   const oms = new IntentOms({ base: "http://localhost:8080", tenant: "demo" });
 *   await oms.login("admin", "admin");                 // or new IntentOms({ ..., apiKey: "oms_..." })
 *   const order = await oms.captureOrder(req, { idempotencyKey: "web-1001" });
 */

export interface Money { amount: number; currency: string }
export interface Problem { type?: string; title?: string; status: number; detail?: string; code?: string; issues?: unknown[]; [k: string]: unknown }

export class OmsError extends Error {
  constructor(public readonly status: number, public readonly problem: Problem) {
    super(`HTTP ${status} ${problem.code ?? problem.title ?? ""}: ${problem.detail ?? ""}`);
  }
}

export interface CaptureRequest {
  channel: string; channelOrderId: string; orderType: string; customerRef?: string;
  customer?: { ref?: string; name?: string; email?: string; phone?: string; tier?: string; ageVerified?: boolean; age?: number; history?: Record<string, unknown> };
  lines: Array<{ lineId: string; sku: string; description?: string; qty: number; unitPrice: Money; discount?: Money; taxCode?: string; attributes?: Record<string, unknown> }>;
  addresses?: Array<{ type: "ship_to" | "bill_to" | string; name?: string; line1: string; line2?: string; city: string; state: string; zip: string; country: string; phone?: string; addressType?: string }>;
  charges?: Array<{ type: string; code?: string; amount: Money; description?: string }>;
  tenders?: Array<{ type: "card" | "paypal" | "gift_card" | "net_terms" | "agent_token"; token: string; amount: Money; details?: Record<string, unknown> }>;
  notes?: Array<{ source: string; author?: string; text: string; customerVisible?: boolean }>;
  intentChoices?: Array<"ship_complete" | "ship_as_available" | "earliest_partial" | "pickup_consolidated">;
  shipMethod?: string; placedAt?: string; currency?: string; extensions?: Record<string, unknown>;
  provenance?: { agentId: string; protocol: "ucp" | "acp" | "mcp"; mandateId?: string; spendLimit?: Money; instructionText?: string; signature?: string; buyerRef?: string };
}

export type Modification =
  | { type: "ChangeAddress"; address: NonNullable<CaptureRequest["addresses"]>[number] }
  | { type: "ChangeQty"; lineId: string; qty: number }
  | { type: "CancelLine"; lineId: string }
  | { type: "CancelOrder" }
  | { type: "ChangeShipMethod"; shipMethod: string }
  | { type: "ChangeItem"; lineId: string; newSku: string };

export interface Order { id: string; status: string; channel: string; channelOrderId: string; orderType: string; lines: Array<{ lineId: string; sku: string; qty: number }>; intent?: { policy: string; confidence: number; source: string }; provenance?: { agentId: string; protocol: string; mandateId?: string }; [k: string]: unknown }
export interface OrderView { order: Order; lineStatuses: Array<{ entityId: string; state: string; quantities: Array<{ state: string; qty: number }> }>; fulfilmentGroups: Array<{ id: string; nodeId: string; status: string }>; holds: Array<{ id: string; type: string; reasonCode?: string }>; decisions: Array<{ id: string; decision: string }>; workflow?: { status: string; currentStep: string }; [k: string]: unknown }
export interface Page<T> { items: T[]; total: number; offset: number; limit: number }
export interface Promise_ { date: string; onTimeProbability: number; fromNodeId: string; shipMethod: string; fromCache: boolean; latencyMs: number; alternatives: unknown[] }
export interface DecisionTrace { decision: string; version: number; tier: string; layer: string; band?: string; action: string; value: unknown; confidence?: number; rulesFired?: string[]; reasonCodes?: string[]; explanation?: string; customerSafeExplanation?: string; [k: string]: unknown }

export interface ClientOptions { base?: string; tenant?: string; token?: string; apiKey?: string; fetch?: typeof fetch }

export class IntentOms {
  readonly base: string; readonly tenant: string; token?: string; apiKey?: string;
  private readonly f: typeof fetch;

  constructor(o: ClientOptions = {}) {
    this.base = (o.base ?? "http://localhost:8080").replace(/\/$/, "");
    this.tenant = o.tenant ?? "demo";
    this.token = o.token; this.apiKey = o.apiKey;
    this.f = o.fetch ?? fetch;
  }

  async request<T>(method: string, path: string, body?: unknown, init: { headers?: Record<string, string>; auth?: boolean; query?: Record<string, string | number | boolean | undefined> } = {}): Promise<T> {
    const url = new URL(this.base + path);
    for (const [k, v] of Object.entries(init.query ?? {})) if (v !== undefined) url.searchParams.set(k, String(v));
    const headers: Record<string, string> = { Accept: "application/json", "X-Tenant-Id": this.tenant, ...(init.headers ?? {}) };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (init.auth !== false) {
      if (this.apiKey) headers["X-Api-Key"] = this.apiKey;
      else if (this.token) headers.Authorization = `Bearer ${this.token}`;
    }
    const res = await this.f(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    const data = text ? JSON.parse(text) : undefined;
    if (!res.ok) throw new OmsError(res.status, (data ?? { status: res.status, detail: text }) as Problem);
    return data as T;
  }

  // auth
  async login(userId: string, password: string): Promise<{ token: string; principal: { id: string; roles: string[]; permissions: string[] } }> {
    const r = await this.request<{ token: string; principal: { id: string; roles: string[]; permissions: string[] } }>("POST", "/api/v1/auth/login", { tenantId: this.tenant, userId, password }, { auth: false });
    this.token = r.token; return r;
  }
  me() { return this.request<{ id: string; tenantId: string; roles: string[]; permissions: string[] }>("GET", "/api/v1/auth/me"); }

  // promise and orders
  promise(lines: Array<{ sku: string; qty: number }>, destinationZip: string, destinationState: string, channel = "web", shipMethod = "ground") {
    return this.request<Promise_>("POST", "/api/v1/promise", { lines, destinationZip, destinationState, channel, shipMethod }, { auth: false });
  }
  captureOrder(req: CaptureRequest, o: { idempotencyKey?: string } = {}) {
    return this.request<Order>("POST", "/api/v1/orders", req, { headers: o.idempotencyKey ? { "Idempotency-Key": o.idempotencyKey } : undefined });
  }
  order(id: string) { return this.request<Order>("GET", `/api/v1/orders/${id}`); }
  orderView(id: string) { return this.request<OrderView>("GET", `/api/v1/orders/${id}/view`); }
  searchOrders(query: Record<string, string | number | boolean | undefined>) { return this.request<Page<Order>>("GET", "/api/v1/orders", undefined, { query }); }
  checkModification(id: string, m: Modification) { return this.request<{ allowed: boolean; reasons: string[]; costDelta?: Money; requiresApproval?: boolean }>("POST", `/api/v1/orders/${id}/modifications/check`, m); }
  modify(id: string, modification: Modification, reason: string) { return this.request<Order>("POST", `/api/v1/orders/${id}/modifications`, { modification, reason }); }
  cancel(id: string, reason: string) { return this.request<Order>("POST", `/api/v1/orders/${id}/cancel`, { reason }); }
  hold(id: string, h: { type: string; blocks: string[]; reasonCode?: string; note?: string; ttl?: string }) { return this.request<{ id: string }>("POST", `/api/v1/orders/${id}/holds`, h); }
  releaseHold(holdId: string, resolution: string) { return this.request<unknown>("POST", `/api/v1/holds/${holdId}/release`, { resolution }); }

  // decisions
  decisionRecords(subjectType: string, subjectId: string) { return this.request<Array<{ id: string; decision: string }>>("GET", "/api/v1/decision-records", undefined, { query: { subjectType, subjectId } }); }
  why(recordId: string, audience?: "internal" | "customer") { return this.request<DecisionTrace>("GET", `/api/v1/decision-records/${recordId}/why`, undefined, { query: { audience } }); }
  evaluateNow(name: string, input: unknown, mode: "LIVE" | "SHADOW" | "SIMULATION" | "REPLAY" = "SIMULATION", version?: number) {
    return this.request<{ value: unknown; band: string; layer: string; confidence: number }>("POST", `/api/v1/decisions/${name}/evaluate-now`, { input, mode, version });
  }

  // returns and tasks
  createReturn(orderId: string, lines: Array<{ lineId: string; qty: number; reasonCode?: string; customerReasonText?: string }>, channel = "web", receiptPresent = true) {
    return this.request<{ rma: string; status: string }>("POST", "/api/v1/returns", { orderId, lines, channel, receiptPresent });
  }
  decideReturn(rma: string) { return this.request<{ refundTiming?: string; riskScore?: number; status: string }>("POST", `/api/v1/returns/${rma}/decide`); }
  tasks(query: Record<string, string | number | undefined>) { return this.request<Page<{ id: string; type: string; queue: string; status: string; subjectId: string }>>("GET", "/api/v1/tasks", undefined, { query }); }
  completeTask(id: string, outcome: string, reason: string, details: Record<string, unknown> = {}) { return this.request<unknown>("POST", `/api/v1/tasks/${id}/complete`, { outcome, reason, details }); }

  // inbound webhooks (for simulators and tests); signs with HMAC-SHA256 when a secret is given
  async wmsEvent(nodeId: string, event: Record<string, unknown>, secret?: string) { return this.webhook(`/webhooks/wms/${nodeId}`, event, secret); }
  async carrierEvent(event: Record<string, unknown>, secret?: string) { return this.webhook("/webhooks/carrier", event, secret); }
  private async webhook(path: string, event: Record<string, unknown>, secret?: string) {
    const raw = JSON.stringify(event);
    const headers: Record<string, string> = { "Content-Type": "application/json", "X-Tenant-Id": this.tenant };
    if (secret) headers["X-Webhook-Signature"] = "sha256=" + (await hmacHex(secret, raw));
    const res = await this.f(this.base + path, { method: "POST", headers, body: raw });
    const text = await res.text();
    if (!res.ok) throw new OmsError(res.status, (text ? JSON.parse(text) : { status: res.status }) as Problem);
    return text ? JSON.parse(text) : {};
  }
}

/** HMAC-SHA256 hex using WebCrypto (available in Node 18+, Deno, browsers). */
export async function hmacHex(secret: string, data: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
