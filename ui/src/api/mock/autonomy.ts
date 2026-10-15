/* Mock routes for the Agentic Control Tower (oms-orchestration autonomy). Mirrors docs/API.md "Agentic Control Tower". */
import type { Anomaly, AutonomousAction, AutonomyActionType, AutonomyGuardrails } from '../types';
import { hoursAgo, minutesAgo, nextId, orders, rnd, round } from './data';

type Ctx = { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> };
type On = (method: string, path: string, handler: (ctx: Ctx) => unknown) => void;
type Problem = (status: number, code: string, detail: string, extra?: Record<string, unknown>) => never;

const DEFAULT_ALLOWED: AutonomyActionType[] = ['notify_customer', 're_promise', 're_source', 'release_hold', 'retry_payment'];

const guardrails: AutonomyGuardrails = {
  enabled: true, killSwitch: false, maxActionsPerDay: 25, maxUsdImpactPerAction: 150, allowedActions: [...DEFAULT_ALLOWED], minConfidenceOverride: null,
};

const orderId = (i: number) => orders[i % Math.max(1, orders.length)]?.order.id ?? `ord_mock_${i}`;

const actions: AutonomousAction[] = [
  {
    id: 'aut_demo1', tenantId: 'demo', orderId: orderId(0), anomalyType: 'no_progress', decisionRecordId: 'dr_aut1',
    diagnosisValue: 'inventory_reallocation_needed', confidence: 0.95, actionTaken: 're_source',
    guardrailSnapshot: { enabled: true, killSwitch: false, maxActionsPerDay: 25, actionsToday: 1, maxUsdImpactPerAction: 150, estimatedImpactUsd: 86 },
    outcome: 'executed', undoable: true, detail: 're-sourced away from ST-AUS-03: SOURCED plan plan_1 (ship_complete)',
    executedAt: hoursAgo(2), undoneAt: null, undoneBy: null, undoReason: null,
  },
  {
    id: 'aut_demo2', tenantId: 'demo', orderId: orderId(1), anomalyType: 'no_progress', decisionRecordId: 'dr_aut2',
    diagnosisValue: 'address_confirmation_needed', confidence: 0.98, actionTaken: 'notify_customer',
    guardrailSnapshot: { enabled: true, killSwitch: false, maxActionsPerDay: 25, actionsToday: 2, maxUsdImpactPerAction: 150, estimatedImpactUsd: 0 },
    outcome: 'executed', undoable: false, detail: 'sent address_confirm via email (com_demo2)',
    executedAt: hoursAgo(1.4), undoneAt: null, undoneBy: null, undoReason: null,
  },
];

const anomalies: Anomaly[] = [
  { id: 'anom_demo1', orderId: actions[0].orderId, anomalyType: 'no_progress', decisionRecordId: 'dr_aut1', diagnosisValue: 'inventory_reallocation_needed',
    confidence: 0.95, band: 'ACT', reasonCodes: [], outcome: 'executed', actionId: 'aut_demo1', escalationTaskId: null, escalationQueue: null, escalationReason: null, detectedAt: hoursAgo(2) },
  { id: 'anom_demo2', orderId: actions[1].orderId, anomalyType: 'no_progress', decisionRecordId: 'dr_aut2', diagnosisValue: 'address_confirmation_needed',
    confidence: 0.98, band: 'ACT', reasonCodes: [], outcome: 'executed', actionId: 'aut_demo2', escalationTaskId: null, escalationQueue: null, escalationReason: null, detectedAt: hoursAgo(1.4) },
  { id: 'anom_demo3', orderId: orderId(2), anomalyType: 'sla_breach_risk', decisionRecordId: 'dr_aut3', diagnosisValue: 'payment_retry_needed',
    confidence: 0.9, band: 'ACT', reasonCodes: ['declined_payment_retryable'], outcome: 'escalated', actionId: 'aut_demo3', escalationTaskId: 'task_aut3', escalationQueue: 'decision_escalations',
    escalationReason: 'autonomous retry_payment did not complete: no authorisation expiring within P3D', detectedAt: minutesAgo(40) },
  { id: 'anom_demo4', orderId: orderId(3), anomalyType: 'no_progress', decisionRecordId: 'dr_aut4', diagnosisValue: 'unclear',
    confidence: 0.55, band: 'ESCALATE', reasonCodes: ['escape_option'], outcome: 'escalated', actionId: null, escalationTaskId: 'task_aut4', escalationQueue: 'decision_escalations',
    escalationReason: 'decision escalate band (confidence 0.55); escape_option', detectedAt: minutesAgo(12) },
];

const failedAction: AutonomousAction = {
  id: 'aut_demo3', tenantId: 'demo', orderId: orderId(2), anomalyType: 'sla_breach_risk', decisionRecordId: 'dr_aut3', diagnosisValue: 'payment_retry_needed',
  confidence: 0.9, actionTaken: 'retry_payment', guardrailSnapshot: { enabled: true, killSwitch: false, maxActionsPerDay: 25, actionsToday: 2, maxUsdImpactPerAction: 150, estimatedImpactUsd: 220 },
  outcome: 'failed', undoable: false, detail: 'no authorisation expiring within P3D', executedAt: minutesAgo(40), undoneAt: null, undoneBy: null, undoReason: null,
};
actions.push(failedAction);

function automationRate(): number {
  const executedToday = actions.filter((a) => a.outcome === 'executed').length;
  const escalated = anomalies.filter((a) => a.outcome === 'escalated').length;
  const total = executedToday + escalated;
  return total === 0 ? 0 : round(executedToday / total, 4);
}

export function registerAutonomyRoutes({ on, problem }: { on: On; problem: Problem }) {
  on('GET', '/api/v1/autonomy/anomalies', ({ query }) => {
    const offset = Number(query.get('offset') ?? 0); const limit = Number(query.get('limit') ?? 50);
    const sorted = [...anomalies].sort((a, b) => +new Date(b.detectedAt) - +new Date(a.detectedAt));
    return { items: sorted.slice(offset, offset + limit), total: sorted.length, offset, limit };
  });

  on('GET', '/api/v1/autonomy/actions', ({ query }) => {
    const offset = Number(query.get('offset') ?? 0); const limit = Number(query.get('limit') ?? 50);
    const actionType = query.get('actionType'); const outcome = query.get('outcome');
    let rows = [...actions];
    if (actionType) rows = rows.filter((a) => a.actionTaken === actionType);
    if (outcome) rows = rows.filter((a) => a.outcome === outcome);
    rows.sort((a, b) => +new Date(b.executedAt) - +new Date(a.executedAt));
    return { items: rows.slice(offset, offset + limit), total: rows.length, offset, limit };
  });

  on('POST', '/api/v1/autonomy/actions/:id/undo', ({ params, body }) => {
    const a = actions.find((x) => x.id === params.id);
    if (!a) return problem(404, 'not_found', `Autonomous action ${params.id} not found`);
    if (a.undoneAt) return problem(409, 'conflict', `Autonomous action ${a.id} was already undone`);
    if (a.outcome !== 'executed') return problem(409, 'conflict', `Autonomous action ${a.id} was not executed; there is nothing to undo`);
    if (!a.undoable) return problem(422, 'validation_failed', `${a.actionTaken} actions are not meaningfully reversible and cannot be undone`);
    a.undoneAt = new Date().toISOString();
    a.undoneBy = 'user:admin';
    a.undoReason = typeof body.reason === 'string' && body.reason ? body.reason : 'manual undo';
    return a;
  });

  on('GET', '/api/v1/autonomy/guardrails', () => guardrails);

  on('PUT', '/api/v1/autonomy/guardrails', ({ body }) => {
    if (body.enabled !== undefined) guardrails.enabled = Boolean(body.enabled);
    if (body.maxActionsPerDay !== undefined) guardrails.maxActionsPerDay = Number(body.maxActionsPerDay);
    if (body.maxUsdImpactPerAction !== undefined) guardrails.maxUsdImpactPerAction = Number(body.maxUsdImpactPerAction);
    if (Array.isArray(body.allowedActions)) {
      const list = body.allowedActions as string[];
      if (list.includes('refund') || list.includes('cancel')) return problem(422, 'validation_failed', "'refund'/'cancel' are never autonomous and cannot be added to the allow-list");
      guardrails.allowedActions = list as AutonomyActionType[];
    }
    if (body.minConfidenceOverride !== undefined) guardrails.minConfidenceOverride = body.minConfidenceOverride === null ? null : Number(body.minConfidenceOverride);
    return guardrails;
  });

  on('POST', '/api/v1/autonomy/kill-switch', ({ body }) => {
    guardrails.killSwitch = Boolean(body.enabled);
    return guardrails;
  });

  on('GET', '/api/v1/autonomy/stats', () => ({
    actionsToday: actions.filter((a) => a.outcome === 'executed').length,
    maxActionsPerDay: guardrails.maxActionsPerDay,
    usdImpactResolvedToday: actions.filter((a) => a.outcome === 'executed').reduce((sum, a) => sum + (Number(a.guardrailSnapshot.estimatedImpactUsd) || 0), 0),
    maxUsdImpactPerAction: guardrails.maxUsdImpactPerAction,
    humanEscalations: anomalies.filter((a) => a.outcome === 'escalated').length,
    automationRate: automationRate(),
  }));

  on('POST', '/api/v1/autonomy/scan-now', () => {
    const found: Anomaly[] = [];
    const idx = anomalies.length;
    const blocked = guardrails.killSwitch || !guardrails.enabled;
    const id = nextId('anom');
    const actionId = blocked ? null : nextId('aut');
    const order = orderId(idx + 5);
    const anomaly: Anomaly = blocked
      ? { id, orderId: order, anomalyType: 'no_progress', decisionRecordId: nextId('dr'), diagnosisValue: 'node_capacity_backlog', confidence: 0.91, band: 'ACT',
          reasonCodes: [], outcome: 'escalated', actionId: null, escalationTaskId: nextId('task'), escalationQueue: 'decision_escalations',
          escalationReason: guardrails.killSwitch ? 'guardrail blocked autonomous execution: the autonomy kill switch is engaged' : 'guardrail blocked autonomous execution: autonomy is not enabled for this tenant',
          detectedAt: new Date().toISOString() }
      : { id, orderId: order, anomalyType: 'no_progress', decisionRecordId: nextId('dr'), diagnosisValue: 'node_capacity_backlog', confidence: 0.91, band: 'ACT',
          reasonCodes: [], outcome: 'executed', actionId, escalationTaskId: null, escalationQueue: null, escalationReason: null, detectedAt: new Date().toISOString() };
    anomalies.unshift(anomaly);
    found.push(anomaly);
    if (!blocked && actionId) {
      actions.unshift({
        id: actionId, tenantId: 'demo', orderId: order, anomalyType: 'no_progress', decisionRecordId: anomaly.decisionRecordId ?? null, diagnosisValue: 'node_capacity_backlog',
        confidence: 0.91, actionTaken: 're_promise', guardrailSnapshot: { enabled: guardrails.enabled, killSwitch: guardrails.killSwitch, maxActionsPerDay: guardrails.maxActionsPerDay,
          actionsToday: actions.filter((a) => a.outcome === 'executed').length + 1, maxUsdImpactPerAction: guardrails.maxUsdImpactPerAction, estimatedImpactUsd: round(40 + rnd() * 80, 2) },
        outcome: 'executed', undoable: true, detail: 're-promised 1 group(s)', executedAt: new Date().toISOString(), undoneAt: null, undoneBy: null, undoReason: null,
      });
    }
    return { anomaliesFound: found.length, anomalies: found };
  });
}
