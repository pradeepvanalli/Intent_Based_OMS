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
export interface TaskQuery { queue?: string; status?: string; assignee?: string; subjectType?: string; subjectId?: string; offset?: number; limit?: number; /** Only queues the signed-in person works, plus tasks assigned to them. */ mine?: boolean; }

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
export interface DecisionRecord { id: string; decision: string; version: number; subjectType: string; subjectId: string; mode: DecisionMode; layer: Layer; band: Band; value: unknown; confidence: number; action: string; provider: string; modelVersion?: string; latencyMs: number; costUsd: number; evaluatedAt: string; actor?: string; overridden?: boolean; overrideValue?: unknown; overrideReason?: string; label?: string | null; reasonCodes?: string[]; correlationId?: string; policyVersion?: number | null; propensity?: number | null; chosenArm?: string | null; arms?: string[]; }
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
  /** XP-03: what the self-optimizing layer contributed to this decision (internal view). */
  learning?: DecisionTraceLearning | null;
  /** XP-05: plain sentences about learned context that are safe to show a customer. */
  learnedContext?: string[];
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

/* ---- Self-Optimizing Order Orchestration (SOO) ---- */
export type AutonomyLevel = 'L0_OBSERVE' | 'L1_RECOMMEND' | 'L2_ACT_WITH_APPROVAL' | 'L3_AUTONOMOUS';
export type SubjectType = 'CUSTOMER' | 'SEGMENT' | 'NODE' | 'LANE' | 'SKU' | 'AGENT' | 'DECISION' | 'SKU_NODE' | 'SUPPLIER' | 'ADDRESS' | 'CARRIER';
export type ProposalStatus = 'DRAFT' | 'EVALUATED' | 'APPROVED' | 'CANARY' | 'PROMOTED' | 'REJECTED' | 'ROLLED_BACK';
export type ExperimentMode = 'SHADOW' | 'CANARY' | 'HOLDOUT';
export type ExperimentStatus = 'RUNNING' | 'STOPPED' | 'COMPLETED';
export type RiskTier = 'T0' | 'T1' | 'T2' | 'T3';

/** GET /learning/learners: what a learner is (its descriptor) plus how it is governed right now. */
export interface LearnerView {
  learnerKey: string; displayName: string; summary: string; loop: string; requirement: string;
  outcomeDecisions: string[]; liveParameters: string[]; autonomyLevel: AutonomyLevel; enabled: boolean; paused: boolean;
  pausedReason?: string | null; running: boolean; gateReason: string; maxPromotionsPerWeek: number; maxStep?: number | null;
  lastRunAt?: string | null; updatedBy?: string | null; updatedAt?: string | null;
  /** True for batch learners that support "Run now"; event-driven learners update as events arrive. */
  runnable?: boolean;
}
/** @deprecated the learner list returns {@link LearnerView}. */
export type LearnerState = LearnerView;
/** GV-03 at every level. globalSource: "property" (forced at startup), "runtime" (set by a platform admin) or null when off. */
export interface KillSwitchState {
  globalKillSwitch: boolean; globalSource?: 'property' | 'runtime' | null; globalSetBy?: string | null; globalSetAt?: string | null;
  tenantPaused: boolean; pausedLearners: string[];
  /** True for a platform administrator, who alone can stop learning for every tenant. */
  canSetGlobal?: boolean;
}
/** GV-02: either field may be omitted to keep its value; clearMaxStep removes the learner's own limit (the 0.05 default for 0..1 values applies). */
export interface BudgetUpdate { maxStep?: number | null; maxPromotionsPerWeek?: number | null; clearMaxStep?: boolean; }
export interface LearnerMetrics {
  learnerKey: string; displayName: string; lastRunAt?: string | null; updateLagSeconds?: number | null; coverage?: number | null;
  proposalsDrafted: number; proposalsPromoted: number; proposalsRolledBack: number; proposalYieldPerWeek: number;
  promotionRate: number; rollbackRate: number; running: boolean; gateReason: string;
}
export interface LiveParameter {
  parameter: string; learnerKey: string; min?: number | null; max?: number | null; maxStep?: number | null; status: string;
  proposalId?: string | null; promotedBy: string; promotedAt: string; revokedBy?: string | null; revokedAt?: string | null;
}
export interface LiveUseRequest { family: string; learnerKey: string; min?: number | null; max?: number | null; maxStep?: number | null; enabled?: boolean; reason?: string; }
export type TypedValue =
  | { type: 'NumberValue'; value: number } | { type: 'BoolValue'; value: boolean }
  | { type: 'TextValue'; value: string } | { type: 'ListValue'; values: string[] };
export interface LearnedFact {
  factId: string; subjectType: SubjectType; subjectId: string; factKey: string; value: TypedValue;
  confidence: number; sampleSize: number; learner: string; version: number; validFrom: string; validTo?: string | null; createdAt: string;
}
export interface Hypothesis {
  hypothesisId: string; scope: Record<string, unknown>; targetMetric: string; expectedDirection: 'INCREASE' | 'DECREASE';
  lever: Record<string, unknown>; priorConfidence: number; evidence: string[]; rationale?: string | null;
  status: 'OPEN' | 'DRAFTED' | 'REJECTED' | 'SUPERSEDED'; createdAt: string;
}
export interface ArtifactRef { type: string; name: string; }
export type PolicyChange =
  | { type: 'ThresholdChange'; decision: string; band: string; from: number; to: number }
  | { type: 'WeightChange'; target: string; weightKey: string; from: number; to: number }
  | { type: 'RuleAdd'; decision: string; expression: string; thenValue: string; reason?: string }
  | { type: 'RuleRetire'; decision: string; ruleId: string; reason?: string }
  | { type: 'ParameterChange'; parameter: string; from: unknown; to: unknown }
  | { type: 'AllowListChange'; listName: string; entry: string; add: boolean };
export interface PolicyChangeset { target: ArtifactRef; baseVersion: number; changes: PolicyChange[]; motivatedBy?: string | null; tier: RiskTier; }
export interface Review { verdict: 'LOOKS_SAFE' | 'NEEDS_CHANGES' | 'RISKY'; risks: string[]; }
export interface ShadowResult { passed?: boolean; reason?: string; [k: string]: unknown; }
export interface EvaluationReport {
  method?: string; sampleSize?: number; baselineReward?: number; proposedRewardEstimate?: number; liftEstimate?: number | null;
  confidenceInterval?: { lower: number; upper: number } | null; clippedRecords?: number; replayed?: number; decisionsChanged?: number;
  changeTypes?: string[]; shadow?: ShadowResult; [k: string]: unknown;
}
export interface Proposal {
  proposalId: string; changeset: PolicyChangeset; hypothesisId?: string | null; tier: RiskTier; status: ProposalStatus;
  criticReview?: Review | null; evaluationReport?: EvaluationReport | null; createdBy: string; createdAt: string; updatedAt: string;
  evaluatedAt?: string | null; approvedBy?: string | null; approvedAt?: string | null; promotedAt?: string | null;
  rejectedReason?: string | null; rejectedAt?: string | null; rolledBackReason?: string | null; rolledBackAt?: string | null;
}
/** XP-02 change card: GET /learning/proposals/{id}/card. */
export interface KeyDiff { key: string; type: string; from: unknown; to: unknown; steps: number; }
export interface CumulativeDiff {
  target: string; baseline: { proposalId?: string | null; approvedBy?: string | null; approvedAt?: string | null; promotedAt?: string | null } | null;
  automaticPromotionsSinceBaseline: number; sinceBaseline: KeyDiff[]; withPending: KeyDiff[]; promotedProposalIds: string[];
}
export interface ChangeCardReplay {
  evaluated: boolean; method?: string; liftEstimate?: number | null; confidenceInterval?: { lower: number; upper: number } | null;
  sampleSize?: number; episodes?: number; guardrails?: unknown; shadow?: ShadowResult; estimator?: string; clipping?: unknown;
  canary?: Record<string, unknown>;
  /** The plain inverse-propensity estimate, reported beside the doubly robust one. */
  ips?: { proposedRewardEstimate?: number; liftEstimate?: number; confidenceInterval?: { lower: number; upper: number } };
  /** How much of the evidence rests on final (mature) rewards versus provisional ones. */
  rewards?: { mature: number; provisional: number; matureShare: number };
  reason?: string; next?: string; caveat?: string;
}
/** What the lifecycle lets this person do next (defect 2), and what going live would waive (EX-07). */
export interface ChangeCardActions {
  canApprove: boolean; canReject: boolean; reason?: string | null;
  canEvaluate?: boolean; canPromote?: boolean; canRollback?: boolean; canExperiment?: boolean;
  /** Promoting now needs a written reason because of these; empty when the evidence and budget checks pass. */
  promotionConcerns?: string[];
  /** Why it cannot go live at all right now (freeze window, kill switch, paused learner…); no reason overrides it. */
  promotionBlocker?: string | null;
}
/** Recorded when a person promoted despite weak evidence or a used-up budget. */
export interface PromotionNote { by: string; reason: string; waived: string[]; at: string; }
export interface ChangeCard {
  proposal: Proposal; hypothesis?: Hypothesis | null; evidence: string[]; replay: ChangeCardReplay; criticReview?: Review | null;
  diff: KeyDiff[]; cumulative?: CumulativeDiff | null; actions: ChangeCardActions; promotionNote?: PromotionNote | null;
}
export interface Experiment {
  experimentId: string; proposalId: string; mode: ExperimentMode; trafficShare: number; status: ExperimentStatus;
  startAt: string; endAt?: string | null; stopRule: Record<string, unknown>; result?: Record<string, unknown> | null; createdAt: string;
  targetRef?: string | null; decisionName?: string | null; candidateVersion?: number | null; baselineVersion?: number | null; createdBy?: string | null;
}
export interface ExperimentDetail {
  experiment: Experiment;
  proposal?: { proposalId: string; target: string; status: ProposalStatus; tier: RiskTier; changeTypes: string[] };
  measurement?: Record<string, unknown> | null;
}
export interface LiftMetric {
  metric: string; betterWhen: 'higher' | 'lower'; nTreated: number; nHoldout: number; treatedMean?: number; holdoutMean?: number;
  lift?: number | null; ciLower?: number; ciUpper?: number; significant: boolean; improvement?: boolean;
  /** False until each side has minPerArm episodes and some spread; reason says what is missing. */
  enoughData?: boolean; reason?: string;
}
export interface LiftReport {
  windowDays: number; since: string; holdout: { experimentId: string; trafficShare: number; startAt: string } | null; note?: string;
  baselines?: unknown; metrics: LiftMetric[]; byDecision?: Record<string, unknown>; minPerArm?: number;
}
export type DigestRow = Record<string, unknown>;
export interface DigestSummary {
  headline?: string; periodStart?: string; periodEnd?: string;
  changed?: { promoted?: DigestRow[]; rolledBack?: DigestRow[]; learnerChanges?: DigestRow[]; factsLearned?: DigestRow[] };
  impact?: { rewardByDecision?: { decision: string; maturedEpisodes: number; meanReward: number; previousMeanReward?: number | null; change?: number | null }[]; coverage?: Record<string, number> };
  pending?: { proposalsByStatus?: DigestRow[]; objectiveDrafts?: number; openHypotheses?: number; pausedLearners?: DigestRow[] };
}
export interface Digest { digestId: string; periodStart: string; periodEnd: string; summary: DigestSummary; deliveredTo: string[]; delivery: string; createdBy: string; createdAt: string; }
export interface ObjectiveWeights { fill: number; otd: number; cost: number; split: number; fraud: number; cx: number; inv: number; }
export interface ObjectiveProfile {
  version: number; status: 'DRAFT' | 'APPROVED' | 'RETIRED'; weights: ObjectiveWeights; constraints: Record<string, unknown>;
  approvedBy?: string | null; approvedAt?: string | null; createdBy: string; createdAt: string;
  sourceText?: string | null; draftMethod?: string | null; rationale?: Record<string, string> | null;
}
export interface GroupParity { group: string; label?: string; customers: number; decisions?: number; actBandRate: number; overrideRate: number; /** Outcome parity (GV-07): rewarded episodes on this group's orders, their mean reward and on-time rate. */ episodes?: number; meanReward?: number | null; onTimeRate?: number | null; }
export interface LearnerFeatureAudit { learner: string; declaredFeatures: string[]; protectedFeatures: string[]; }
export interface ParityReport {
  decision: string; grouping?: string; groups: GroupParity[]; tenantActBandRate: number; tenantOverrideRate: number;
  featureAudit?: LearnerFeatureAudit[]; flags: string[];
}
export interface GoldenCandidate {
  hypothesisId: string; decision: string; expectedValue: unknown; sourceRecordIds?: string[] | null; agreeingResolutions: number; rationale?: string | null; createdAt: string;
}
export interface RetentionResult { retentionDays: number; cutoff: string; dryRun: boolean; outcomes: number; episodes: number; supersededFacts: number; digests: number; }
/** XP-03: one learned fact a decision read, and the value actually served to it. */
export interface LearnedFactUse {
  subjectType: string; subjectId: string; factKey: string; version?: number; learned?: unknown; served?: unknown; source?: string; reason?: string;
}
export interface DecisionTraceLearning {
  learnedFacts: LearnedFactUse[]; learnedFactsServed: number; policyVersion?: number; rawConfidence?: number; calibratedConfidence?: number;
  calibration?: Record<string, unknown>; experiment?: { experimentId?: string; arm?: string; version?: number }; providerRouted?: string;
  shadow?: { arm?: string; propensity?: number }; arms?: string[]; chosenArm?: string | null; propensity?: number | null; usedLearning: boolean;
}
