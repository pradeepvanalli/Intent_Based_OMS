/* Types for the Intent OMS REST contract (docs/ARCHITECTURE.md §3).
   Fields the backend may omit are optional; rendering must stay defensive. */

export interface Page<T> { items: T[]; total: number; offset: number; limit: number; }

export type PrincipalType = 'USER' | 'AGENT' | 'SYSTEM' | 'DECISION';
export interface Principal { type: PrincipalType; id: string; tenantId: string; roles: string[]; permissions: string[]; trustTier: number; }
export interface LoginRequest { tenantId: string; userId: string; password: string; }
export interface LoginResponse { token: string; principal: Principal; }

export interface ProblemDetail { type?: string; title?: string; status: number; detail?: string; instance?: string; code?: string; fields?: Record<string, string>; [k: string]: unknown; }

export interface Money { amount: number; currency: string; }

/* ---- State machine ---- */
export type MetaState = 'CREATED' | 'ACTIVE' | 'TERMINAL';
export interface SlaDef { maxAge: string; onBreach: string; }
export interface StateDef { id: string; meta: MetaState; sla?: SlaDef | null; parent?: string | null; blocksIfHeld?: string[]; }
export interface TransitionDef { id: string; from: string[]; to: string; trigger: string; guards?: string[]; actions?: string[]; permission?: string | null; extras?: Record<string, unknown>; }
export interface RollupRule { parentState: string; expression: string; priority: number; }
export interface StateModel { id: string; version: number; entity: string; quantityLevel: boolean; states: StateDef[]; transitions: TransitionDef[]; rollup?: RollupRule[]; yaml: string; publishedAt?: string; publishedBy?: string; reason?: string; }
export interface StateModelSummary { id: string; version: number; entity: string; quantityLevel: boolean; stateCount?: number; transitionCount?: number; inFlight?: number; publishedAt?: string; }
export interface ValidationIssue { severity: 'error' | 'warning'; code: string; message: string; ref?: string; }
export interface ValidationReport { ok: boolean; issues: ValidationIssue[]; }
export interface MigrationEntityReport { entityType: string; entityId: string; fromState: string; toState: string; status: 'ok' | 'blocked' | 'skipped' | string; message?: string; }
export interface MigrationReport { modelId: string; fromVersion: number; toVersion: number; dryRun: boolean; total: number; migrated: number; blocked: number; entities: MigrationEntityReport[]; }
export interface QuantityBucket { state: string; qty: number; since: string; }
export interface EntityStatus { entityType: string; entityId: string; modelId: string; modelVersion: number; state: string; quantities: QuantityBucket[]; attributes?: Record<string, unknown>; }
export interface Hold { id: string; type: string; scopeType: string; scopeId: string; blocksTransitions: string[]; reasonCode: string; note?: string; expiresAt?: string | null; placedBy: string; placedAt: string; resolvedBy?: string | null; resolvedAt?: string | null; resolution?: string | null; }
export interface PlaceHoldRequest { type: string; scopeType: string; scopeId: string; blocks: string[]; reasonCode: string; note?: string; ttl?: string; }

/* ---- Workflow ---- */
export type WorkflowStatus = 'RUNNING' | 'WAITING_SIGNAL' | 'WAITING_TIMER' | 'WAITING_TASK' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'COMPENSATING';
export interface WorkflowView { id: string; type: string; version: number; businessKey: string; status: WorkflowStatus; currentStep: string; vars?: Record<string, unknown>; startedAt: string; updatedAt: string; nextWakeAt?: string | null; lastError?: string | null; }
export type TimelineKind = 'step' | 'activity' | 'signal' | 'timer' | 'task' | 'log' | 'error' | 'compensation';
export interface TimelineEntry { at: string; kind: TimelineKind | string; name: string; status?: string; details?: Record<string, unknown>; }

export type TaskStatus = 'OPEN' | 'CLAIMED' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED';
export interface HumanTask { id: string; type: string; queue: string; subjectType: string; subjectId: string; title: string; payload?: Record<string, unknown>; status: TaskStatus; assignee?: string | null; slaDueAt?: string | null; createdAt: string; outcome?: string | null; outcomeReason?: string | null; completedBy?: string | null; completedAt?: string | null; workflowId?: string | null; requiredSkills?: string[]; priority: number; }
export interface TaskQueue { name: string; slaDefault?: string; escalationQueue?: string | null; skills?: string[]; openCount?: number; claimedCount?: number; overdueCount?: number; }
export interface TaskQuery { queue?: string; status?: string; assignee?: string; subjectType?: string; subjectId?: string; offset?: number; limit?: number; }

export type BulkAction = 'resource' | 'repromise' | 'hold' | 'release' | 'cancel';
export interface BulkItemResult { subjectId: string; status: 'ok' | 'failed' | 'skipped' | 'planned' | string; message?: string; applied?: Record<string, unknown>; }
export interface BulkOperation { id: string; action: BulkAction | string; status: 'PLANNED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ROLLED_BACK' | string; dryRun: boolean; total: number; succeeded: number; failed: number; perSecond?: number; params?: Record<string, unknown>; results: BulkItemResult[]; submittedBy?: string; submittedAt: string; completedAt?: string | null; }
export interface BulkRequest { action: BulkAction; subjectIds: string[]; params?: Record<string, unknown>; dryRun: boolean; perSecond?: number; }

/* ---- Decisions ---- */
export type Tier = 'T0' | 'T1' | 'T2' | 'T3';
export type Band = 'ACT' | 'CONFIRM' | 'ESCALATE';
export type Layer = 'L0_RULE' | 'L1_SYSTEM_ONE' | 'L2_LLM' | 'L3_HUMAN' | 'DEFAULT';
export type DecisionStatus = 'draft' | 'shadow' | 'canary' | 'live' | 'retired' | 'pending_approval';
export type QuestionType = 'choice' | 'score' | 'noul';
export interface QuestionDef { key: string; type: QuestionType; instructions?: string; choiceCriteria?: Record<string, string>; scoreLevels?: string[]; statement?: string; reasonCode?: boolean; feeds?: string; }
export interface Bands { actGte: number; confirmGte: number; }
export interface RuleDef { id: string; when: string; then: string; reason?: string; priority: number; }
export interface CompositeDef { weights: Record<string, number>; outputKey: string; }
export interface TopKDef { scoreQuestion: string; k: number; candidatesField: string; }
export interface EvaluationSpec { goldenSet: string; minAccuracyInActBand: number; maxCalibrationError: number; }
export interface Approval { approver: string; at: string; comment?: string; }
export interface DecisionDefinition {
  name: string; version: number; owner: string; tier: Tier; provider: string; fallbackProvider?: string; timeoutMs: number; dflt?: unknown;
  stateInclude: string[]; questions: Record<string, QuestionDef>; bands: Bands; preChecks?: string[]; postChecks?: string[];
  rules?: RuleDef[]; composite?: CompositeDef | null; topK?: TopKDef | null; evaluation?: EvaluationSpec | null;
  status: DecisionStatus; canaryPercent: number; changeReason?: string; ticket?: string; yaml: string; createdAt: string;
  purpose?: string; createdBy?: string; submittedBy?: string; approvals?: Approval[]; killSwitch?: boolean;
}
export interface DecisionSummary { name: string; liveVersion?: number | null; latestVersion: number; status: DecisionStatus; tier: Tier; owner: string; provider: string; purpose?: string; volume30d?: number; overrideRate?: number; escalationRate?: number; killSwitch?: boolean; updatedAt?: string; }
export interface Answer { type: QuestionType; value: unknown; probabilities?: Record<string, number>; confidence: number; legend?: Record<string, string>; }
export type DecisionMode = 'LIVE' | 'SHADOW' | 'SIMULATION' | 'REPLAY';
export interface DecisionResult { recordId: string; decision: string; version: number; layer: Layer; band: Band; value: unknown; answers: Record<string, Answer>; confidence: number; action: 'act' | 'confirm' | 'escalate' | 'blocked' | 'default' | string; requiresHuman: boolean; escalationId?: string | null; rulesFired: string[]; reasonCodes?: string[]; latencyMs: number; costUsd: number; provider: string; modelVersion?: string; details?: Record<string, unknown>; }
export interface DecisionRecord { id: string; decision: string; version: number; subjectType: string; subjectId: string; mode: DecisionMode; layer: Layer; band: Band; value: unknown; confidence: number; action: string; provider: string; modelVersion?: string; latencyMs: number; costUsd: number; evaluatedAt: string; actor?: string; overridden?: boolean; overrideValue?: unknown; overrideReason?: string; label?: string | null; reasonCodes?: string[]; correlationId?: string; }
export interface TraceQuestion { key: string; type: QuestionType; instructions?: string; criteria?: Record<string, string>; levels?: string[]; statement?: string; answer: Answer; escape?: boolean; }
export interface TraceCheck { expression: string; passed: boolean; phase: 'pre' | 'post'; message?: string; }
export interface TraceRule { id: string; when: string; then: string; reason?: string; matched: boolean; }
export interface TraceLayerStep { layer: Layer; ran: boolean; outcome?: string; latencyMs?: number; }
export interface DecisionTrace {
  record: DecisionRecord; definition: { name: string; version: number; tier: Tier; owner: string; purpose?: string; bands: Bands; status?: DecisionStatus };
  provider: string; modelVersion?: string; latencyMs: number; costUsd: number; inputTokens?: number; outputTokens?: number;
  layers: TraceLayerStep[]; inputs: Record<string, unknown>; inputHash?: string; tokenisedFields?: string[];
  questions: TraceQuestion[]; rules: TraceRule[]; checks: TraceCheck[]; band: Band; action: string; actor: string;
  escalation?: { id: string; queue?: string; taskId?: string; status?: string } | null;
  override?: { by: string; at: string; value: unknown; reason: string } | null; label?: { label: string; at: string; by: string } | null;
  explanation?: string; customerSafeExplanation?: string; audience?: 'internal' | 'customer';
}
export interface LintResult { severity: 'error' | 'warning' | 'info'; code: string; message: string; path?: string; }
export interface ImpactDiff { days: number; baseVersion: number; draftVersion: number; evaluated: number; flips: number; flipRate: number; bandMixBefore: Record<Band, number>; bandMixAfter: Record<Band, number>; layerMixBefore?: Record<string, number>; layerMixAfter?: Record<string, number>; costDeltaUsd: number; latencyDeltaMs: number; escalationDelta: number; marginDeltaUsd?: number; slaBreachDelta?: number; samples: { recordId: string; subjectType: string; subjectId: string; before: unknown; after: unknown; bandBefore: Band; bandAfter: Band; confidenceBefore: number; confidenceAfter: number }[]; }
export interface CalibrationBin { bin: number; lower: number; upper: number; count: number; meanConfidence: number; accuracy: number; }
export interface EvaluationResult { goldenSet: string; cases: number; accuracyInActBand: number; minAccuracyInActBand: number; calibrationError: number; maxCalibrationError: number; passed: boolean; actBandShare?: number; bins: CalibrationBin[]; failures?: { caseId: string; expected: unknown; got: unknown; confidence: number }[]; evaluatedAt?: string; }
export interface GoldenCase { id: string; input: Record<string, unknown>; expected: unknown; label?: string; }
export interface GoldenSet { name: string; cases: GoldenCase[]; updatedAt?: string; }
export type Rollout = 'shadow' | 'live' | `canary:${number}` | string;

/* ---- Inventory / nodes / sourcing / promise ---- */
export interface Atp { sku: string; nodeId: string; onHand: number; inbound: number; reserved: number; allocated: number; safety: number; available: number; confidence: number; }
export interface Node { id: string; name: string; type: 'dc' | 'store' | 'vendor' | '3pl' | string; lat: number; lon: number; country: string; state: string; capabilities: string[]; dailyCapacity: number; cutoff?: string; timezone?: string; health?: Record<string, unknown>; costProfile?: Record<string, unknown>; active: boolean; penalty?: number; }
export interface Allocation { lineId: string; sku: string; qty: number; }
export interface PlanPackage { nodeId: string; allocations: Allocation[]; eta: string; onTimeProbability: number; shippingCost: Money; labourCost?: Money; packagingCost?: Money; landedCost?: Money; }
export interface Pnl { revenue: number; discounts: number; cogs: number; shipping: number; labour: number; packaging: number; paymentFees: number; returnsReserve: number; landedCost: number; contributionMargin: number; marginRatio: number; }
export interface SourcingPlan { id: string; policy: string; packages: PlanPackage[]; totalCost: Money; pnl?: Pnl; score: number; meetsMarginFloor: boolean; unsourcedLineIds?: string[]; explain?: Record<string, unknown>; }
export interface SourcingResult { plan?: SourcingPlan | null; requiresApproval: boolean; alternatives?: SourcingPlan[]; taskId?: string; decisionRecordId?: string; }
export interface PromiseOption { date: string; onTimeProbability: number; fromNodeId: string; shipMethod: string; cost?: Money; }
export interface PromiseResponse { date: string; onTimeProbability: number; fromNodeId: string; shipMethod: string; fromCache: boolean; latencyMs: number; alternatives: PromiseOption[]; }

/* ---- Orders ---- */
export interface Customer { ref?: string; name?: string; email?: string; phone?: string; tier?: string; history?: Record<string, unknown>; }
export interface Address { type: 'ship_to' | 'bill_to' | string; name?: string; line1?: string; line2?: string; city?: string; state?: string; zip?: string; country?: string; verified?: boolean; }
export interface Charge { type: string; amount: Money; description?: string; }
export interface Tender { type: string; amount: Money; token?: string; status?: string; pspRef?: string; }
export interface Note { at: string; by: string; text: string; source?: string; }
export interface OrderLine { lineId: string; sku: string; description: string; qty: number; unitPrice: Money; discount?: Money; tax?: Money; fulfilmentGroupId?: string | null; attributes?: Record<string, unknown>; }
export interface IntentProfile { policy: 'ship_complete' | 'ship_as_available' | 'earliest_partial' | 'pickup_consolidated' | 'unclear' | string; confidence: number; source: 'explicit' | 'inferred' | 'channel_default' | string; explicitChoices?: string[]; decisionRecordId?: string | null; }
export interface AgentProvenance { agentId: string; protocol: 'ucp' | 'acp' | 'mcp' | string; mandateId?: string; spendLimit?: Money; instructionText?: string; signature?: string; raw?: Record<string, unknown>; }
export interface Totals { merchandise: Money; discounts: Money; shipping: Money; tax: Money; duties?: Money; fees?: Money; grandTotal: Money; }
export interface MarginSnapshot { revenue?: number; contributionMargin: number; marginRatio: number; floor: number; belowFloor: boolean; approvalPresent?: boolean; approvalTaskId?: string | null; computedAt?: string; pnl?: Pnl; }
export interface Order { id: string; channel: string; channelOrderId: string; orderType: string; customerRef?: string; customer?: Customer; lines: OrderLine[]; addresses?: Address[]; charges?: Charge[]; tenders?: Tender[]; notes?: Note[]; intent?: IntentProfile | null; provenance?: AgentProvenance | null; totals: Totals; margin?: MarginSnapshot | null; stateModelId: string; stateModelVersion: number; status: string; extensions?: Record<string, unknown>; placedAt: string; capturedAt: string; version: number; }
export interface OrderSummary { id: string; channel: string; channelOrderId: string; orderType: string; customerRef?: string; status: string; total: Money; lineCount: number; placedAt: string; nodeIds?: string[]; atRisk?: boolean; riskScore?: number; held?: boolean; promiseDate?: string; agentId?: string | null; }
export interface OrderQuery { q?: string; status?: string; channel?: string; orderType?: string; nodeId?: string; customerRef?: string; placedFrom?: string; placedTo?: string; atRisk?: boolean; offset?: number; limit?: number; sort?: string; }
export interface FulfilmentGroup { id: string; nodeId: string; lineIds: string[]; shipMethod?: string; promiseDate?: string; onTimeProbability?: number; planId?: string; }
export interface Shipment { id: string; nodeId: string; carrier: string; service?: string; tracking?: string; status: string; lines: { lineId: string; qty: number }[]; shippedAt?: string; deliveredAt?: string; eta?: string; }
export interface Payment { id: string; orderId: string; type: string; amount: Money; status: string; pspRef?: string; authorizedAt?: string; capturedAt?: string; expiresAt?: string; tenderType?: string; }
export interface Refund { id: string; orderId: string; amount: Money; status: string; reason?: string; returnId?: string | null; issuedAt: string; pspRef?: string; }
export interface Communication { id: string; at: string; channel: string; direction: 'in' | 'out'; subject?: string; summary: string; by?: string; }
export interface LedgerEvent { id: string; type: string; aggregateType: string; aggregateId: string; at: string; actor?: string; data?: Record<string, unknown>; version?: number; correlationId?: string; causationId?: string; }
export interface OrderView { order: Order; header: EntityStatus; lineStatuses: EntityStatus[]; fulfilmentGroups?: FulfilmentGroup[]; holds: Hold[]; shipments: Shipment[]; returns: ReturnSummary[]; payments: Payment[]; refunds: Refund[]; decisionRecordIds: string[]; decisions?: DecisionRecord[]; workflow?: WorkflowView | null; timeline?: TimelineEntry[]; ledger?: LedgerEvent[]; communications?: Communication[]; risk?: { score: number; level: string; reasons: string[]; decisionRecordId?: string } | null; nextExpectedEvent?: { name: string; expectedBy: string } | null; }
export type ModificationType = 'ChangeAddress' | 'ChangeQty' | 'CancelLine' | 'CancelOrder' | 'ChangeShipMethod' | 'ChangeItem';
export interface Modification { type: ModificationType; lineId?: string; qty?: number; address?: Address; shipMethod?: string; newSku?: string; reason?: string; }
export interface Feasibility { allowed: boolean; reasons: string[]; costDelta?: Money; requiresApproval: boolean; carrierIntercept?: boolean; newPromise?: { date: string; onTimeProbability: number } | null; affectedLines?: string[]; }
export interface OrderType { name: string; headerStateModel: string; lineStateModel: string; workflowType: string; decisionSet?: string[]; channelDefaults?: Record<string, unknown>; }

/* ---- Control tower / monitoring ---- */
export interface PipelineCell { state: string; ageBucket: string; count: number; }
export interface PipelineView { states: string[]; ageBuckets: string[]; cells: PipelineCell[]; byChannel?: Record<string, number>; byNode?: Record<string, number>; byOrderType?: Record<string, number>; openOrders: number; atRisk: number; held?: number; generatedAt: string; }
export interface AtRiskOrder { orderId: string; channelOrderId?: string; status: string; channel: string; orderType?: string; nodeId?: string; riskScore: number; riskLevel?: 'low' | 'medium' | 'high' | 'critical' | string; reasons: string[]; promiseDate?: string; slaDueAt?: string; ageMinutes: number; decisionRecordId?: string; }
export type ExceptionSeverity = 'low' | 'medium' | 'high' | 'critical';
export interface PlaybookStep { name: string; action: string; status: 'pending' | 'running' | 'done' | 'skipped' | 'failed' | 'awaiting_confirmation' | string; at?: string; detail?: string; gatedBy?: string; }
export interface ExceptionRecord { id: string; type: string; severity: ExceptionSeverity | string; queue: string; status: 'OPEN' | 'ASSIGNED' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED' | string; subjectType: string; subjectId: string; title: string; description?: string; raisedAt: string; raisedBy?: string; assignee?: string | null; resolvedAt?: string | null; resolution?: string | null; classificationRecordId?: string; playbook?: string | null; playbookSteps?: PlaybookStep[]; taskId?: string | null; slaDueAt?: string | null; }
export interface Playbook { name: string; exceptionType: string; steps: { action: string; minConfidence?: number; maxSpendUsd?: number; params?: Record<string, unknown> }[]; enabled: boolean; }

/* ---- Returns ---- */
export interface ReturnLine { lineId: string; sku: string; description?: string; qty: number; reason: string; condition?: string | null; disposition?: string | null; }
export interface ReturnSummary { rma: string; orderId: string; status: string; createdAt: string; channel?: string; lineCount?: number; refundAmount?: Money; riskScore?: number; refundTiming?: string; }
export interface ReturnRecord { rma: string; orderId: string; channel: string; status: string; lines: ReturnLine[]; reason: string; createdAt: string; createdBy?: string; riskScore?: number | null; riskLevel?: string; reasonCodes?: { code: string; probability: number }[]; riskDecisionRecordId?: string | null; refundTiming?: string | null; refundTimingDecisionRecordId?: string | null; dispositionDecisionRecordId?: string | null; eligibility?: { eligible: boolean; reasons: string[]; windowDays?: number }; refund?: { amount: Money; restockingFee?: Money; status: string; issuedAt?: string } | null; keepIt?: { recommended: boolean; returnCost?: Money; recovery?: Money } | null; scannedAt?: string | null; inspectedAt?: string | null; refundAmount?: Money; timeline?: TimelineEntry[]; }

/* ---- Agents ---- */
export interface AgentLimits { allowedActions: string[]; maxOrderValue?: Money; maxRefund?: Money; ratePerMinute?: number; confirmActions?: string[]; }
export interface AgentIdentity { id: string; owner: string; protocol: 'ucp' | 'acp' | 'mcp' | string; verification: 'hmac' | 'public_key' | 'api_key' | string; trustTier: 0 | 1 | 2 | 3 | number; limits: AgentLimits; active: boolean; createdAt: string; lastSeenAt?: string | null; callbackUrl?: string | null; orders30d?: number; }
export interface Mandate { id: string; agentId: string; buyerRef: string; scope: string[]; spendLimit: Money; instructionText: string; signature?: string; issuedAt: string; expiresAt: string; status: 'active' | 'expired' | 'revoked' | 'consumed' | string; usedOrderIds?: string[]; }
export interface EvidencePack { orderId: string; generatedAt: string; mandate?: Mandate | null; identity?: AgentIdentity | null; instructionText?: string; injectionScreen?: { flagged: boolean; probability: number; recordId?: string }; decisionTrail: DecisionRecord[]; deliveryProof?: Record<string, unknown> | null; signatures?: Record<string, string>; hash?: string; }

/* ---- Simulation ---- */
export interface ReplayDiff { recordId: string; decision: string; version: number; subjectId: string; before: { value: unknown; band: Band; confidence: number; layer: Layer }; after: { value: unknown; band: Band; confidence: number; layer: Layer }; changed: boolean; }
export interface ReplayResult { orderId: string; eventsReplayed: number; decisions: ReplayDiff[]; stateBefore?: string; stateAfter?: string; }
export interface SimulationOverrides { closedNodes?: string[]; carrierOutage?: string[]; tariffDelta?: number; decisionVersionOverrides?: Record<string, number>; }
export interface SimulationJob { id: string; status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string; from: string; to: string; overrides: SimulationOverrides; progress: number; submittedAt: string; completedAt?: string | null; results?: { orders: number; costPerOrder: number; baselineCostPerOrder?: number; splitRate: number; baselineSplitRate?: number; onTimeRate: number; baselineOnTimeRate?: number; marginRatio: number; baselineMarginRatio?: number; touchlessRate: number; baselineTouchlessRate?: number; escalations: number; baselineEscalations?: number } | null; error?: string | null; }

/* ---- Analytics ---- */
export interface Kpi { key: string; label: string; value: number; unit: 'pct' | 'usd' | 'count' | 'minutes' | 'ratio' | string; target?: number | null; targetDirection?: 'gte' | 'lte'; trend?: number[]; delta?: number | null; definition?: string; }
export interface KpiDashboard { range: { from: string; to: string }; kpis: Kpi[]; generatedAt: string; }
export interface DecisionVersionAnalytics { version: number; status?: DecisionStatus; volume: number; bandMix: Record<Band, number>; layerMix: Record<string, number>; overrideRate: number; escalationRate: number; latencyP50Ms: number; latencyP95Ms: number; costUsd: number; costPerDecisionUsd: number; accuracy?: number | null; calibration: CalibrationBin[]; dailyVolume?: number[]; }
export interface DecisionAnalytics { name: string; tier: Tier; versions: DecisionVersionAnalytics[]; }

/* ---- Admin ---- */
export interface ConfigEntry { key: string; value: unknown; description?: string; updatedAt?: string; updatedBy?: string; }
export interface Webhook { id: string; url: string; event_filter?: string[] | string; active: boolean; created_at?: string; secret?: string; }
export interface IssuedKey { keyId: string; apiKey: string; note?: string; }
export interface AuditEntry { id: number | string; at: string; actor: string; action: string; object_type: string; object_id: string; reason?: string; details?: Record<string, unknown>; }
export interface PromiseLine { sku: string; qty: number; }
export interface PromiseRequestBody { lines: PromiseLine[]; destinationZip: string; destinationState?: string; channel?: string; shipMethod?: string; }

/* ---- Copilot (oms-copilot module) ---- */
export interface CopilotStatus { available: boolean; model: string; baseUrl?: string; budgetUsdPerDay?: number | null; spentTodayUsd: number; features: string[]; remainingUsd?: number | null; budgetExceeded?: boolean; copilotUsedTodayUsd?: number; copilotCallsToday?: number; }
export type CopilotScope = 'decisions' | 'state_models' | 'order_types' | 'policies' | 'all';
export type ChangeKind = 'decision_rule' | 'decision_criteria' | 'decision_bands' | 'decision_new' | 'state_model' | 'order_type' | 'sla' | 'playbook' | 'tenant_config' | 'compliance_rule';
export type ChangeSetStatus = 'proposed' | 'staged' | 'rejected' | 'partially_staged';
export interface ChangeValidationIssue { severity: 'error' | 'warning' | 'info'; code: string; message: string; path?: string; }
export interface ChangeTestCase { caseId: string; input: Record<string, unknown>; expected: unknown; note?: string; }
export interface StagedRef { kind: 'decision_draft' | 'artifact'; ref: string; name?: string; version?: number; status?: string; merged?: boolean; mergedChangeIds?: string[]; }
export interface ProposedChange {
  id: string; kind: ChangeKind | string; target: string; title: string; rationale: string; before: string | null; after: string; diff: string;
  validation: { ok: boolean; issues: ChangeValidationIssue[]; warnings?: string[] }; tests: ChangeTestCase[]; riskTier: Tier | null; requiresApprovals: number; staged?: StagedRef | null;
}
export interface ChangeSetUsage { inputTokens: number; outputTokens: number; costUsd: number; model: string; }
export interface ChangeSet {
  id: string; request: { text: string; scope: CopilotScope | string }; summary: string; assumptions: string[]; questions: string[]; changes: ProposedChange[];
  createdBy: string; createdAt: string; status: ChangeSetStatus | string; usage?: ChangeSetUsage;
}
export interface ChangeRequest { text: string; scope?: CopilotScope; context?: Record<string, unknown>; }
export interface DraftArtifact { id: string; kind: ChangeKind | string; target: string; yaml?: string; json?: string; status: 'draft' | 'applied' | 'rejected' | string; changesetId: string; createdBy: string; createdAt: string; appliedBy?: string; appliedAt?: string; }
export interface BootstrapNode { id: string; type: string; city: string; state: string; }
export interface BootstrapRequest { text: string; channels?: string[]; categories?: string[]; marginFloor?: number; returnWindowDays?: number; nodes?: BootstrapNode[]; }
export interface OpsTemplateParam { name: string; type: string; required: boolean; example?: unknown; }
export interface OpsTemplate { name: string; description: string; parameters: OpsTemplateParam[]; }
export interface OpsAnswer { answer: string; queryName: string; parameters: Record<string, unknown>; sql: string; columns: string[]; rows: Record<string, unknown>[]; rowCount: number; generatedAt: string; truncated?: boolean; }
export interface CopilotExplanation { text: string; audience: 'internal' | 'customer' | string; generated: true | boolean; recordId: string; model: string; }
export interface CsDraft { draft: string; intent: { value: string; confidence: number; decisionRecordId?: string | null }; factsUsed: string[]; slots: { promiseDate?: string; refundAmount?: number | string; status?: string; tracking?: string; [k: string]: unknown }; disclaimer: string; }
export interface DigestFinding { title: string; metric: string; value: string | number; delta?: string | number | null; evidence?: string; suggestedPrompt?: string; }
export interface DigestFacts {
  orders?: number;
  splits?: { split_rate_pct: number; split_orders: number; extra_packages: number; estimated_split_cost_usd: number; by_channel: { channel: string; split_orders: number; orders?: number; split_rate_pct?: number }[] };
  belowFloor?: { below_floor_orders: number; approved: number; margin_given_up_usd: number; revenue: number };
  returnsByTiming?: { timing: string; count: number; refund_usd?: number }[];
  topExceptions?: { type: string; count: number; severity?: string }[];
  decisions?: { decision: string; volume: number; escalations: number; overrides: number; escalation_rate_pct: number; override_rate_pct: number; cost_usd: number }[];
  aiCost?: { decisions_usd: number; copilot_usd: number; total_usd: number; budget_usd_per_day?: number | null };
  [k: string]: unknown;
}
export interface MarginDigest { id: string; period: { days: number; from: string; to: string }; headline: string; findings: DigestFinding[]; generated: boolean; model: string; facts: DigestFacts; generatedAt: string; fallbackReason?: string | null; }

/* ---- Agentic Control Tower (oms-orchestration autonomy) ---- */
export type AutonomyActionType = 're_source' | 're_promise' | 'retry_payment' | 'release_hold' | 'notify_customer';
export interface Anomaly {
  id: string; orderId: string; anomalyType: string; decisionRecordId?: string | null; diagnosisValue?: string | null;
  confidence?: number | null; band?: string | null; reasonCodes: string[]; outcome: 'executed' | 'escalated';
  actionId?: string | null; escalationTaskId?: string | null; escalationQueue?: string | null; escalationReason?: string | null;
  detectedAt: string;
}
export interface AutonomousAction {
  id: string; tenantId: string; orderId: string; anomalyType: string; decisionRecordId?: string | null;
  diagnosisValue: string; confidence: number; actionTaken: AutonomyActionType | string;
  guardrailSnapshot: Record<string, unknown>; outcome: 'executed' | 'failed'; undoable: boolean; detail?: string | null;
  executedAt: string; undoneAt?: string | null; undoneBy?: string | null; undoReason?: string | null;
}
export interface AutonomyGuardrails {
  enabled: boolean; killSwitch: boolean; maxActionsPerDay: number; maxUsdImpactPerAction: number;
  allowedActions: AutonomyActionType[]; minConfidenceOverride?: number | null;
}
export interface AutonomyStats {
  actionsToday: number; maxActionsPerDay: number; usdImpactResolvedToday: number; maxUsdImpactPerAction: number;
  humanEscalations: number; automationRate: number;
}
export interface AutonomyScanResult { anomaliesFound: number; anomalies: Anomaly[]; }
