# Intent OMS — Architecture & Module Contracts

Source of truth for requirements: `Intent OMS — Product Requirements.pdf` (text copy: `docs/requirements.txt`).
Requirement IDs (FR-*, GR-*, NFR-*) are referenced throughout code in Javadoc so traceability is greppable.

## 1. Shape

A **modular monolith** (one deployable, `oms-app`) built from Maven modules with enforced one-way dependencies.
Java 21 (virtual threads), Spring Boot 3.5, PostgreSQL 16 (JdbcClient + Flyway, jsonb), React/Vite UI.
Everything a tenant can change is **data**: state models, decision definitions, rules, playbooks, SLAs, order types.

```
oms-platform      shared kernel: tenancy, security, ids, json, jdbc, errors, CEL, event ledger + outbox, audit, config, rate limit, PII tokens
oms-testing       Testcontainers Postgres base test class, TestApplication
oms-statemachine  configurable statechart engine, quantity-level status, holds, roll-ups, SLA timers, versioning, static checks   (FR-SM-*)
oms-workflow      durable Postgres-backed workflow engine: steps, activities, retries, timers, signals, saga, human tasks, bulk ops (FR-WF-*)
oms-decision      decision fabric: definitions, L0 rules, Jev/LLM providers, runtime, records, shadow, studio, golden sets      (FR-DEC-*, FR-PRM-*, GR-*)
oms-inventory     ATP, positions, reservations (row-locked), feeds, reconciliation                                                (FR-INV-*)
oms-finance       payments (PSP-agnostic), tax engine SPI, compliance L0, order P&L, landed cost                                  (FR-FIN-*)
oms-order         canonical order model, capture, validation pipeline, intent, provenance, order types, CS view & modifications  (FR-CAP-*, FR-CS-*)
oms-sourcing      nodes, candidate generation, economics, Jev composite ranking, fulfilment policy, margin floor, promise          (FR-SRC-*)
oms-monitoring    pipeline view, SLA definitions, exceptions taxonomy + classification, playbooks, stuck sweeper, alerts           (FR-MON-*)
oms-returns       RMA, eligibility + risk, refund timing, refund orchestration, disposition, keep-it                               (FR-RET-*)
oms-agent         agent registry, mandates, limits, MCP server, UCP order capability, injection screen, evidence pack              (FR-AGT-*)
oms-simulation    replay from ledger, batch simulation, what-if                                                                   (FR-SIM-*)
oms-analytics     KPI + decision analytics                                                                                        (FR-ANL-*)
oms-orchestration order workflows wiring every module together, bulk action handlers, escalation handler, demo seed, WMS/carrier mocks
oms-learning      Self-Optimizing Order Orchestration: episodes, outcomes, learned facts/parameters, typed hypotheses/
                  changesets, experiments, promotion gate — the closed loop from outcome back to policy
oms-app           Spring Boot main, application.yml, Dockerfile, static UI
```

Dependency direction (a module may only depend on modules above it in this list, as declared in its pom).
`platform ← statemachine | workflow | decision | inventory ← finance(decision) ← order ← sourcing ← monitoring/returns ← agent/simulation/analytics ← orchestration ← app`.
`oms-learning` depends only on `platform`/`decision`/`workflow`; `sourcing`, `inventory`, `copilot`, `simulation`,
`orchestration` and `agent` each depend on it in turn, to read learned facts/parameters and drive proposals —
`oms-learning` itself never depends back on any of them.

## 2. Conventions every module follows

* **Package root**: `ai.intentoms.<module>`; sub-packages `model`, `repo` (or `store`), `service`, `api` (REST controllers), `config`.
  Spring component scanning covers all of `ai.intentoms`.
* **Tenancy**: every table has `tenant_id text not null`; every query filters by `TenantContext.require()`. Never trust a tenant id from the request body.
* **Persistence**: `org.springframework.jdbc.core.simple.JdbcClient` with hand-written SQL. jsonb params via `Jsonb.of(obj)`; read jsonb as `::text` and `Json.readMap`.
  Ids: `Ids.id()` (UUIDv7 string) or `Ids.prefixed("rma")`.
* **Migrations**: Flyway, file `src/main/resources/db/migration/V<range>__<module>_<topic>.sql`. Version ranges:
  platform 100–199, statemachine 200–299, workflow 300–399, decision 400–499, inventory 500–599, finance 600–699, order 700–799,
  sourcing 800–899, monitoring 900–999, returns 1000–1099, agent 1100–1199, simulation 1200–1249, analytics 1250–1299, orchestration 1300–1399.
  Every table gets an index on `(tenant_id, …)` for its hot lookups. Append-only tables get the `reject_mutation()` trigger (see V100).
* **Events**: all state changes append to the ledger through `EventStore.append(aggregateType, aggregateId, expectedVersionOr-1, events)` *inside the same transaction*
  as the row changes. Aggregate types: `order`, `return`, `shipment`, `payment`, `refund`, `decision`, `reservation`, `node`, `agent`, `task`, `workflow`, `config`.
  Event type grammar: `<aggregate>.<past_tense>` e.g. `order.captured`, `line.transitioned`, `hold.placed`, `decision.evaluated`, `sla.breached`.
  React to events by implementing `EventSubscriber` (delivered after commit by the outbox relay, per-aggregate ordered, must be idempotent).
* **Actors**: `Actor.parse(authorizationService.actorRef())` or `Actor.system("sweeper")`, `Actor.decision(recordId)`.
* **Security**: controllers use `@RequiresPermission(Permissions.X)`. Constants live in `ai.intentoms.platform.security.Permissions`; add new ones there only via the grammar `<domain>.<action>`.
* **Errors**: throw `NotFoundException`, `ConflictException`, `ValidationException`, `ForbiddenException`, `GuardrailViolationException`, `InvalidTransitionException`. They map to RFC 9457 problem details.
* **Config**: runtime tenant settings via `TenantConfigStore` (`ConfigKeys`). Deploy-time settings via `@ConfigurationProperties(prefix="oms.<module>")`.
* **Time**: inject `TimeSource` rather than `Instant.now()` in business logic (simulation freezes time).
* **CEL**: `CelEngine.evalBool(expr, vars)` where vars keys are the standard roots (`order`, `line`, `plan`, `tenant`, `approval`, `customer`, `node`, `event`, `decision`, `answers`, `ctx`, `return`, `payment`, `agent`, `mandate`, `input`, `state`, `now`). `CelEngine.validate(expr)` for static checks.
* **Metrics**: `Metrics.time("oms.<module>.<op>", …)`; counters `oms.<module>.<thing>`.
* **REST**: base path `/api/v1/<resource>`; JSON; list endpoints accept `offset`,`limit` and return `Page<T>`. Mutations accept optional header `Idempotency-Key`.
* **Tests**: unit tests for pure logic; integration tests extend `ai.intentoms.testing.PostgresTestBase` with
  `@SpringBootTest(classes = TestApplication.class) @ActiveProfiles("test")`. Docker is available. Tests must not require network or API keys
  (the decision module ships a deterministic `HeuristicDecisionProvider` used when Jev is disabled).
* **Build**: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; cd backend; mvn -q -pl <module> -am install -DskipTests` then `mvn -q -pl <module> test`.
* **Code style**: records for values, final classes, constructor injection, no Lombok, no field injection, Javadoc that cites requirement IDs. Keep hot paths allocation-light.

## 3. Module contracts (public Java API other modules call)

### 3.1 oms-statemachine (`ai.intentoms.statemachine`)
YAML format = PRD Appendix A (plus `rollup` and `regions`). Example files live in `config/tenants/demo/state-models/`.

```java
public enum MetaState { CREATED, ACTIVE, TERMINAL }
public record SlaDef(Duration maxAge, String onBreach) {}
public record StateDef(String id, MetaState meta, SlaDef sla, String parent, List<String> blocksIfHeld /*hold types*/) {}
public record TransitionDef(String id /*from->to#trigger*/, List<String> from, String to, String trigger, List<String> guards, List<String> actions, String permission, Map<String,Object> extras) {}
public record RollupRule(String parentState, String expression /*CEL over `state` = map stateId->qty, `lines` list*/ , int priority) {}
public record StateModel(String id, int version, String entity, boolean quantityLevel, List<StateDef> states, List<TransitionDef> transitions, List<RollupRule> rollup, String yaml) {
  StateDef initial(); Optional<StateDef> state(String id); List<TransitionDef> transitionsFrom(String stateId);
}
public record ValidationIssue(String severity /*error|warning*/, String code, String message, String ref) {}
public record ValidationReport(boolean ok, List<ValidationIssue> issues) {}

public interface StateModelRegistry {                 // FR-SM-06, FR-SM-09, FR-SM-10
  ValidationReport validate(String yaml);
  StateModel publish(String yaml, String reason);     // static checks must pass; stores version; audit
  StateModel get(String modelId, int version);
  StateModel latest(String modelId);                  // highest published version
  List<StateModel> versions(String modelId);
  List<String> modelIds();
  MigrationReport migrate(String modelId, int fromVersion, int toVersion, boolean dryRun); // moves in-flight entities, per-entity report
}

public record QuantityBucket(String state, int qty, Instant since) {}
public record EntityStatus(String entityType, String entityId, String modelId, int modelVersion, String state /*dominant*/, List<QuantityBucket> quantities, Map<String,Object> attributes) {}
public record TransitionRequest(String entityType, String entityId, String trigger, Integer qty /*null = all*/, String fromState /*null = any matching*/, Map<String,Object> vars, Actor actor, String correlationId, String causationId) {}
public record TransitionResult(boolean applied, String fromState, String toState, int qty, List<String> actions, String eventId, List<String> blockedBy /*guard texts or hold ids*/) {}

public interface StateMachine {                       // FR-SM-01..03, 05, 07, 08, 11
  EntityStatus initialize(String entityType, String entityId, String modelId, Integer version /*null = latest*/, int qty, Map<String,Object> attributes, Actor actor);
  TransitionResult fire(TransitionRequest req);       // evaluates guards (CEL) + holds + permission (via AuthorizationService unless actor is system/decision); writes `<entity>.transitioned` event; schedules/cancels SLA timers; triggers roll-up
  EntityStatus status(String entityType, String entityId);
  List<EntityStatus> statuses(String entityType, Collection<String> entityIds);
  List<TransitionDef> available(String entityType, String entityId, Map<String,Object> vars);
  String rollup(String modelId, int version, Map<String,Integer> childStateQty); // header status from line quantities (FR-SM-05)
}

public record Hold(String id, String type, String scopeType, String scopeId, Set<String> blocksTransitions /*trigger names or state ids*/, String reasonCode, String note, Instant expiresAt, String placedBy, Instant placedAt, String resolvedBy, Instant resolvedAt, String resolution) {}
public interface HoldService {                        // FR-SM-07
  Hold place(String type, String scopeType, String scopeId, Set<String> blocks, String reasonCode, String note, Duration ttl, Actor actor);
  Hold release(String holdId, String resolution, Actor actor);
  List<Hold> active(String scopeType, String scopeId);
  boolean blocks(String scopeType, String scopeId, String transitionTriggerOrState);
}
```
SLA timers: table `sla_timer`; a `@Scheduled` sweep (every 5 s) fires `sla.breached` ledger events on the entity's aggregate (aggregateType derived from entityType: `order_line`→`order`) and publishes Spring `SlaBreachedEvent(tenant, entityType, entityId, state, onBreach)`. Entity ids for lines are `"<orderId>:<lineId>"`.
REST: `/api/v1/state-models` (GET list, GET `/{id}`, GET `/{id}/versions/{v}`, POST validate, POST publish {yaml, reason}, POST `/{id}/migrate`), `/api/v1/holds` (POST, GET by scope, POST `/{id}/release`), `/api/v1/entities/{type}/{id}/status`, `/…/transitions` (GET available, POST fire).

### 3.2 oms-workflow (`ai.intentoms.workflow`)
Durable "step machine" engine on Postgres (ADR-002: Temporal-compatible semantics without the extra cluster; `WorkflowEngine` is the seam for a Temporal adapter).

```java
public interface WorkflowDefinition {
  String type(); int version();
  String initialStep();
  Map<String, Step> steps();                          // step name -> handler
  default Duration defaultActivityTimeout() { return Duration.ofSeconds(30); }
}
@FunctionalInterface public interface Step { Next run(WorkflowContext ctx) throws Exception; }
public interface WorkflowContext {
  String workflowId(); String businessKey(); String tenantId(); int attempt();
  Map<String,Object> vars();                          // persisted after each step (jsonb)
  <T> T activity(String name, RetryPolicy policy, Callable<T> body);   // records attempt, result cached by (step, name) so replays are idempotent
  void compensation(String name, Runnable undo);      // registered undo for saga (FR-WF-02); run in reverse on fail()
  Optional<Signal> signal();                          // signal that woke this step (if any)
  Instant now();
  void log(String message, Map<String,Object> details);   // timeline entry (FR-WF-06)
}
public record Signal(String name, Map<String,Object> payload, String idempotencyKey, Instant receivedAt) {}
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) { static RetryPolicy standard(); static RetryPolicy none(); }
public sealed interface Next {
  record GoTo(String step) implements Next {}
  record WaitSignal(Set<String> names, Duration timeout, String onTimeoutStep, String onSignalStep) implements Next {}
  record Sleep(Duration d, String thenStep) implements Next {}
  record Complete(Map<String,Object> result) implements Next {}
  record Fail(String reason, boolean compensate) implements Next {}
  record WaitTask(String taskId, String onCompleteStep, Duration timeout, String onTimeoutStep) implements Next {}  // human task
  static Next goTo(String s); static Next waitSignal(...); static Next sleep(...); static Next complete(Map); static Next fail(String, boolean);
}
public interface WorkflowEngine {                     // FR-WF-01, 05, 06, 08
  String start(String type, String businessKey, Map<String,Object> input);    // idempotent per (tenant,type,businessKey) → returns existing id
  void signal(String type, String businessKey, String name, Map<String,Object> payload, String idempotencyKey);  // per-order ordering, dedup on idempotencyKey
  void signalById(String workflowId, String name, Map<String,Object> payload, String idempotencyKey);
  Optional<WorkflowView> find(String type, String businessKey);
  WorkflowView get(String workflowId);
  List<TimelineEntry> timeline(String workflowId);
  void cancel(String workflowId, String reason);
  void retryNow(String workflowId);                  // ops: run pending step immediately
}
public record WorkflowView(String id, String type, int version, String businessKey, String status /*RUNNING,WAITING_SIGNAL,WAITING_TIMER,WAITING_TASK,COMPLETED,FAILED,CANCELLED,COMPENSATING*/, String currentStep, Map<String,Object> vars, Instant startedAt, Instant updatedAt, Instant nextWakeAt, String lastError) {}
public record TimelineEntry(Instant at, String kind /*step,activity,signal,timer,task,log,error,compensation*/, String name, String status, Map<String,Object> details) {}
```
Engine internals: tables `workflow_instance` (one row per instance; row lock while a step runs), `workflow_activity` (attempt log + cached result), `workflow_signal` (queue with sequence + idempotency unique), `workflow_timer`, `workflow_timeline`. Workers = N virtual threads polling `workflow_instance where next_wake_at <= now() and status in (...) for update skip locked`. Definitions are Spring beans implementing `WorkflowDefinition`; instance pins `version` (FR-WF-08) and the engine keeps older definition beans registered (`@Component` with distinct versions). Property `oms.workflow.workers` (default 8), `oms.workflow.poll-ms` (200).

Human tasks (FR-WF-04):
```java
public record HumanTask(String id, String type, String queue, String subjectType, String subjectId, String title, Map<String,Object> payload, String status /*OPEN,CLAIMED,COMPLETED,CANCELLED,EXPIRED*/, String assignee, Instant slaDueAt, Instant createdAt, String outcome, String outcomeReason, String completedBy, Instant completedAt, String workflowId, Set<String> requiredSkills, int priority) {}
public interface HumanTaskService {
  HumanTask create(TaskRequest r);      // TaskRequest(type, queue, subjectType, subjectId, title, payload, Duration sla, requiredSkills, priority, workflowId)
  HumanTask claim(String taskId, String assignee);
  HumanTask complete(String taskId, String outcome, String reason, Map<String,Object> details);   // signals workflow "task:<taskId>" if attached; writes task.completed event
  HumanTask cancel(String taskId, String reason);
  Page<HumanTask> list(TaskQuery q);    // by queue/status/assignee/subject
  HumanTask get(String id);
}
```
Queues table `task_queue` (name, sla default, escalation queue, skills). Sweeper escalates overdue tasks (`task.escalated` event).

Bulk operations (FR-WF-07):
```java
public interface BulkActionHandler { String action(); Map<String,Object> apply(String subjectId, Map<String,Object> params, boolean dryRun); Optional<Map<String,Object>> rollback(String subjectId, Map<String,Object> applied); }
public interface BulkOperationService { BulkOperation submit(String action, List<String> subjectIds, Map<String,Object> params, boolean dryRun, int perSecond); BulkOperation get(String id); BulkOperation rollback(String id); Page<BulkOperation> list(int offset,int limit); }
```
REST: `/api/v1/workflows/{id}`, `/api/v1/workflows/{id}/timeline`, `/api/v1/workflows/by-key/{type}/{key}`, `POST /api/v1/workflows/{id}/signal`, `/api/v1/tasks` (list/get/claim/complete/cancel), `/api/v1/queues`, `/api/v1/bulk-operations`.

### 3.3 oms-decision (`ai.intentoms.decision`)
YAML format = PRD Appendix B, extended with `rules:` (L0), `composite:`, `topK:`, `status`, `canaryPercent`. Examples in `config/tenants/demo/decisions/`.

```java
public enum Tier { T0, T1, T2, T3 }
public enum Band { ACT, CONFIRM, ESCALATE }
public enum Layer { L0_RULE, L1_SYSTEM_ONE, L2_LLM, L3_HUMAN, DEFAULT }
public record QuestionDef(String key, String type /*choice|score|noul*/, String instructions, Map<String,String> choiceCriteria /*option->description; must include escape*/, List<String> scoreLevels, String statement /*noul*/, boolean reasonCode, String feeds) {}
public record Bands(double actGte, double confirmGte) {}
public record RuleDef(String id, String when /*CEL over input*/, String then /*value or "escalate" or "block"*/, String reason, int priority) {}
public record CompositeDef(Map<String,Double> weights /*question key -> weight*/, String outputKey) {}
public record TopKDef(String scoreQuestion, int k, String candidatesField) {}
public record EvaluationSpec(String goldenSet, double minAccuracyInActBand, double maxCalibrationError) {}
public record DecisionDefinition(String name, int version, String owner, Tier tier, String provider, String fallbackProvider, int timeoutMs, Object dflt,
    List<String> stateInclude, Map<String,QuestionDef> questions, Bands bands, List<String> preChecks, List<String> postChecks,
    List<RuleDef> rules, CompositeDef composite, TopKDef topK, EvaluationSpec evaluation, String status /*draft|shadow|canary|live|retired*/, int canaryPercent, String changeReason, String ticket, String yaml, Instant createdAt) {}

public record Answer(String type, Object value /*choice key | score double | noul prob*/, Map<String,Double> probabilities, double confidence /*noul: |2p-1|*/, Map<String,String> legend) {}
public record DecisionOptions(String subjectType, String subjectId, String correlationId, Mode mode /*LIVE,SHADOW,SIMULATION,REPLAY*/, Integer versionOverride, Actor actor, Map<String,Object> extraVars) { static DecisionOptions live(String subjectType, String subjectId, Actor actor); }
public record DecisionResult(String recordId, String decision, int version, Layer layer, Band band, Object value, Map<String,Answer> answers, double confidence,
    String action /*act|confirm|escalate|blocked|default*/, boolean requiresHuman, String escalationId, List<String> rulesFired, List<String> reasonCodes, long latencyMs, double costUsd, String provider, String modelVersion, Map<String,Object> details) {}

public interface DecisionService {                    // FR-DEC-01..11, GR-01..08
  DecisionResult decide(String decisionName, Map<String,Object> input, DecisionOptions opts);
  List<DecisionResult> decideAll(List<Invocation> batch);     // batch mode
  DecisionRecord record(String recordId);
  List<DecisionRecord> forSubject(String subjectType, String subjectId);
  DecisionTrace why(String recordId);                 // FR-XAI-01: inputs passed (tokenised), questions, answers+probabilities, rules fired, band, action, actor, timings, provider
  void label(String recordId, String label, Map<String,Object> details);      // FR-DEC-10 outcome labels
  DecisionRecord override(String recordId, Object newValue, String reason);   // GR-13; writes decision.overridden
}
public interface DecisionProvider {                   // FR-DEC-08
  String name();                                      // "jev", "llm.anthropic", "llm.openai", "heuristic"
  boolean available();
  ProviderResponse ask(ProviderRequest req);          // ProviderRequest(Object state, Map<String,QuestionDef> questions, int timeoutMs, String model); ProviderResponse(Map<String,Answer> answers, String modelVersion, int inputTokens, int outputTokens, double costUsd, long latencyMs, Map raw)
}
```
Runtime algorithm (`DecisionRuntime.decide`): resolve definition (live version; canary by hash of subjectId % 100 < canaryPercent chooses canary version; shadow versions also run asynchronously and store records with mode SHADOW) → kill switches (tenant AI, decision, provider) → L0 `rules` (first match by priority; `then: block|escalate|<value>`; a matching rule with a value short-circuits → Layer L0_RULE, band ACT) → preChecks (CEL; a failure = blocked/default) → build state from `stateInclude` paths (Json.path; PII tokenised via Tokenizer for keys named name/email/phone/address) → if candidates > 255 and topK configured run Score per candidate (chunked) then Choice over top K → provider call with timeoutMs budget (GR-07; on timeout/error → fallbackProvider → DEFAULT) → confidence: choice/score from provider, noul `|2p-1|`; main question = first choice question or composite → band by `bands` (tier defaults T0 .5, T1 .8/.5, T2 .9, T3 never acts) → GR-03 escape option chosen ⇒ band ESCALATE → postChecks (CEL with `answers`, `chosen_plan`, `input`; failure ⇒ answer discarded, logged, DEFAULT) → CONFIRM band on T1/T2 ⇒ L2 LLM proposes one of the enumerated options with the trace (read-only), accepted only if it is a valid option and tier allows ⇒ else L3 via `EscalationHandler` SPI (default impl stores `decision_escalation` row; orchestration provides a human-task impl) → persist `decision_record` (FR-DEC-06: value, probabilities, confidence, band, provider, model version, definition version, input hash, latency, cost, action, layer, mode) + `decision.evaluated` ledger event (aggregate `decision`, id = recordId; also metadata subject) → cache by (definition version, input hash) with TTL when `cache: {ttl: PT10M}` (FR-DEC-11).
Providers: `JevDecisionProvider` (RestClient → `${oms.decision.jev.base-url}/v1/systemone`, body `{state, model, questions:{key:{type,instructions,criteria}}}`; choice criteria map, score criteria list of levels, noul criteria optional `{true:…,false:…}`; response `{model, answers:{key:{type, choice|score|noul, probabilities, confidence, legend}}, usage:{input_tokens,output_tokens}}`; retry once on 429/529 with jitter inside budget; cost = input_tokens × 0.042/1e6), `AnthropicDecisionProvider` (Messages API with a forced tool `answer` whose JSON schema enumerates options; model `claude-sonnet-5`), `OpenAiCompatibleDecisionProvider` (chat completions with json_schema response_format), `HeuristicDecisionProvider` (deterministic, keyword/number heuristics, confidence ≤ 0.6; used in tests and degraded mode; always available).
Studio (`DecisionStudioService`, FR-PRM-01..08, GR-10, GR-11, GR-14): `createDraft(name, yaml|edits)`, `lint(def)` (overlapping options, missing escape option, vague rubric, PII in prompts, contradiction with L0 rule text, unknown state paths), `impactDiff(name, draftVersion, days)` (re-run stored inputs of the live version's records over last N days in SIMULATION mode; report flips, band mix delta, escalations delta, cost delta), `evaluate(name, version)` against golden set table `decision_golden_case` (accuracy in act band, calibration error = mean |confidence − accuracy| over 10 bins; blocks publish when below `evaluation`), `submitForApproval(name, version, reason, ticket)`, `approve(name, version, approverActor)` (T0/T1 one approver, T2/T3 two distinct approvers, submitter ≠ approver), `publish(name, version, rollout: shadow|canary:<pct>|live)`, `rollback(name)`, `killSwitch(name, on)`, `setThreshold` only by humans (GR-14).
REST: `/api/v1/decisions` (list defs), `/api/v1/decisions/{name}` (versions), `/api/v1/decisions/{name}/versions/{v}`, `POST /api/v1/decisions/{name}/drafts`, `POST .../lint`, `POST .../impact`, `POST .../evaluate`, `POST .../submit`, `POST .../approve`, `POST .../publish`, `POST .../rollback`, `POST .../kill-switch`, `POST /api/v1/decisions/{name}/evaluate-now` (ad-hoc decide with input; permission decision.read), `/api/v1/decision-records?subjectType&subjectId`, `/api/v1/decision-records/{id}`, `/api/v1/decision-records/{id}/why`, `POST .../label`, `POST .../override`, `/api/v1/golden-sets/{name}` (upload cases). Analytics query helpers in `DecisionAnalyticsRepository` (volume, band mix, override rate, calibration curve, latency, cost per version) for oms-analytics.

### 3.4 oms-inventory (`ai.intentoms.inventory`)
```java
public record InventoryPosition(String sku, String nodeId, String supplyType /*on_hand|po|asn|in_transit*/, String demandType /*null|reserved|allocated|safety*/, int qty, double confidence, Instant asOf) {}
public record Atp(String sku, String nodeId, int onHand, int inbound, int reserved, int allocated, int safety, int available, double confidence) {}
public interface InventoryService {                   // FR-INV-01, 03, 04
  List<Atp> atp(String sku);                          // per node
  Atp atp(String sku, String nodeId);
  Map<String,Integer> networkAtp(Collection<String> skus);  // sku -> total available (buffers applied: tenant config inventory.channel_buffer)
  void upsert(List<InventoryPosition> positions, String source);   // feed from WMS/POS; writes inventory.synced events (batched)
  void adjust(String sku, String nodeId, int delta, String reason, Actor actor);
  ReconciliationReport reconcile(List<InventoryPosition> snapshot); // drift report + inventory.drift_detected
}
public record Reservation(String id, String sku, String nodeId, int qty, String source /*cart|checkout|order*/, String refId, Instant expiresAt, String status /*ACTIVE,CONVERTED,RELEASED,EXPIRED*/) {}
public interface ReservationService {                 // FR-INV-02, NFR-06 (row locks per sku+node)
  Reservation reserve(String sku, String nodeId, int qty, String source, String refId, Duration ttl);   // throws ConflictException("insufficient") when ATP < qty
  List<Reservation> reserveAll(List<ReservationLine> lines, String source, String refId, Duration ttl);   // atomic: all or none
  Reservation convert(String reservationId, String orderId);       // cart→order, clears TTL
  Reservation release(String reservationId, String reason);
  List<Reservation> forRef(String refId);
  int expireDue();                                                 // @Scheduled sweep
}
```
Tables: `inventory_position` (pk tenant, sku, node, supply_type, demand_type), `inventory_reservation`, `inventory_sync_log`. ATP = on_hand + (optionally) inbound − reserved − allocated − safety.
REST: `/api/v1/inventory/{sku}` (ATP by node), `/api/v1/inventory/network?skus=`, `POST /api/v1/inventory/feed` (bulk positions), `POST /api/v1/inventory/reconcile`, `/api/v1/reservations` (POST, GET by ref, POST `/{id}/release`, POST `/{id}/convert`).

### 3.5 oms-finance (`ai.intentoms.finance`)
```java
public record Money(BigDecimal amount, String currency) {}
public interface PaymentGateway { String name(); AuthResult authorize(AuthRequest r); AuthResult reauthorize(String pspRef, Money amount); CaptureResult capture(String pspRef, Money amount, String idempotencyKey); RefundResult refund(String pspRef, Money amount, String idempotencyKey, String reason); void voidAuth(String pspRef); }
// MockPaymentGateway (default; deterministic, supports forced failures via card token suffix "_decline"); StripePaymentGateway skeleton behind oms.finance.psp=stripe.
public interface PaymentService {                     // FR-FIN-01
  Payment authorize(String orderId, Money amount, String tenderToken, String tenderType, Map<String,Object> agentMandate);
  Payment reauthorizeIfExpiring(String orderId, Duration within);
  Payment capture(String orderId, Money amount, String reason);
  Refund refund(String orderId, Money amount, String reason, String returnId /*nullable*/, Actor actor);   // GR-05 velocity cap check → GuardrailViolationException
  void voidAuthorization(String orderId, String reason);
  List<Payment> forOrder(String orderId); List<Refund> refundsForOrder(String orderId);
}
public interface TaxEngine { String name(); TaxResult calculate(TaxRequest r); }   // FlatRateTaxEngine default (per-state rates from tenant_config "tax.rates"); AvalaraTaxEngine skeleton
public record Pnl(BigDecimal revenue, BigDecimal discounts, BigDecimal cogs, BigDecimal shipping, BigDecimal labour, BigDecimal packaging, BigDecimal paymentFees, BigDecimal returnsReserve, BigDecimal landedCost, BigDecimal contributionMargin, double marginRatio) {}
public interface OrderPnlCalculator { Pnl compute(PnlInput in); }   // FR-FIN-04; PnlInput(lines with price/qty/cogs, shipping cost, labour, packaging, payment fee rate, returns reserve rate, landed cost)
public interface ComplianceService { List<ComplianceViolation> check(ComplianceInput in); }  // FR-FIN-03 / FR-CAP-05: restricted by state & ZIP, age-restricted, hazmat, export; rules loaded from config/tenants/<t>/rules/compliance.yaml + tenant_config "compliance.rules"
public interface LandedCostService { LandedCost estimate(String originCountry, String destCountry, String hsCode, Money value, Instant at); } // FR-FIN-05 tariff tables with effective dates (table `tariff_rate`)
```
Tables: `payment`, `refund`, `tariff_rate`, `compliance_rule`. REST: `/api/v1/payments?orderId`, `/api/v1/refunds?orderId`, `/api/v1/tariffs`, `/api/v1/compliance/check`.

### 3.6 oms-order (`ai.intentoms.order`)
Canonical model (FR-CAP-02) as records; stored normalised (`orders`, `order_line`, `order_address`, `order_charge`, `order_tender`, `order_note`, `fulfilment_group`, `agent_provenance`) with `extensions jsonb` validated against the tenant's extension JSON schema (table `extension_schema`).
```java
public record Order(String id, String channel, String channelOrderId, String orderType, String customerRef, Customer customer, List<OrderLine> lines, List<Address> addresses, List<Charge> charges, List<Tender> tenders, List<Note> notes,
   IntentProfile intent, AgentProvenance provenance, Totals totals, MarginSnapshot margin, String stateModelId, int stateModelVersion, String status /*rolled-up header status*/, Map<String,Object> extensions, Instant placedAt, Instant capturedAt, int version) {}
public record OrderLine(String lineId, String sku, String description, int qty, Money unitPrice, Money discount, Money tax, String fulfilmentGroupId, Map<String,Object> attributes /*hazmat, age_restricted, weight_kg, hs_code, cogs*/) {}
public record IntentProfile(String policy /*ship_complete|ship_as_available|earliest_partial|pickup_consolidated|unclear*/, double confidence, String source /*explicit|inferred|channel_default*/, List<String> explicitChoices, String decisionRecordId) {}
public record AgentProvenance(String agentId, String protocol /*ucp|acp|mcp*/, String mandateId, Money spendLimit, String instructionText, String signature, Map<String,Object> raw) {}
public interface OrderService {
  Order capture(CaptureRequest req, String idempotencyKey);   // FR-CAP-01, 04, 05, 06, 07: idempotent on (channel, channelOrderId); runs ValidationPipeline; stores; initialises state machines (order header + each line, model from OrderType); appends order.captured; starts workflow `order.<orderType>` via WorkflowEngine; never blocks on AI (intent decision is run by the workflow)
  Order get(String orderId); Optional<Order> byChannelRef(String channel, String channelOrderId);
  Page<OrderSummary> search(OrderQuery q);  // status, channel, orderType, customerRef, nodeId, placed range, text
  OrderView view(String orderId);           // FR-CS-01: order + line statuses + holds + shipments + returns + payments + decisions (ids) + timeline
  Order update(String orderId, UnaryOperator<Order> fn, NewEvent event);  // optimistic (version), appends event
}
public interface ValidationStep { String name(); List<ValidationIssue> validate(CaptureRequest req, ValidationContext ctx); }  // FR-CAP-05 pipeline ordered by tenant config "capture.validation_steps"; built-ins: schema, address (AddressVerifier SPI w/ mock), item_price_sanity, payment_status, compliance (finance ComplianceService), agent_provenance
public interface OrderTypeRegistry { OrderType get(String type); List<OrderType> all(); }  // FR-CAP-03 from config/tenants/<t>/order-types.yaml + table; OrderType(name, headerStateModel, lineStateModel, workflowType, decisionSet, channelDefaults)
public interface OrderModificationService {           // FR-CS-02, UC-07
  Feasibility check(String orderId, Modification m); // Modification = ChangeAddress | ChangeQty | CancelLine | CancelOrder | ChangeShipMethod | ChangeItem; result: allowed, reasons, cost delta, requires approval
  Order apply(String orderId, Modification m, String reason, Actor actor);   // fires state transitions (api.cancel_line etc.), signals workflow "order.modified"
}
```
REST: `POST /api/v1/orders` (capture), `GET /api/v1/orders`, `GET /api/v1/orders/{id}`, `GET /api/v1/orders/{id}/view`, `GET /api/v1/orders/{id}/timeline`, `POST /api/v1/orders/{id}/modifications/check`, `POST /api/v1/orders/{id}/modifications`, `POST /api/v1/orders/{id}/cancel`, `POST /api/v1/orders/{id}/holds`, `GET /api/v1/order-types`, `PUT /api/v1/extension-schema`.
Also `IntentService.inferIntent(Order)` → runs decision `fulfilment_intent` (choice; unclear = escape) and stores IntentProfile (FR-CAP-06) — called by the orchestration workflow.

### 3.7 oms-sourcing (`ai.intentoms.sourcing`)
```java
public record Node(String id, String name, String type /*dc|store|vendor|3pl*/, double lat, double lon, String country, String state, Set<String> capabilities /*ship,bopis,sfs,hazmat,same_day*/, int dailyCapacity, LocalTime cutoff, String timezone, Map<String,Object> health /*backlog, pick_rate, reject_rate, staffing*/, Map<String,Object> costProfile /*labour_per_unit, packaging, handling*/, boolean active, double penalty /*learned*/) {}
public interface NodeRegistry { Node get(String id); List<Node> active(); Node upsert(Node n); void penalise(String nodeId, String sku, double delta, String reason); double penalty(String nodeId, String sku); }
public record Candidate(Node node, Map<String,Integer> coverage /*sku->qty available*/, boolean complete, double distanceKm, int transitDays) {}
public interface CandidateGenerator { List<Candidate> generate(SourcingRequest req, int limit /*default tenant config sourcing.candidate_limit=100*/); }  // FR-SRC-01 deterministic
public record PlanPackage(String nodeId, List<Allocation> allocations /*lineId, sku, qty*/, LocalDate eta, double onTimeProbability, Money shippingCost, Money labourCost, Money packagingCost, Money landedCost) {}
public record SourcingPlan(String id, String policy, List<PlanPackage> packages, Money totalCost, Pnl pnl, double score, boolean meetsMarginFloor, List<String> unsourcedLineIds, Map<String,Object> explain) {}
public interface SourcingService {                    // FR-SRC-02..04, 07
  List<SourcingPlan> options(String orderId);         // plans for ship_complete, ship_as_available, split≤K, pickup; each with economics + Jev composite score (decision `sourcing_node_score`: Score "node on-time likelihood" per candidate, two-stage topK when >255)
  SourcingResult source(String orderId, Actor actor); // chooses policy from IntentProfile (channel default when unclear), ranks, applies margin floor (GR-01, FR-SRC-04): below floor → requires approval (returns requiresApproval=true + alternative offers) ; persists chosen plan to fulfilment groups; appends order.sourced; returns plan
  SourcingResult resource(String orderId, String reason, Actor actor);  // store reject / timeout: penalise node+sku, re-run (FR-SRC-07)
}
public record PromiseRequest(List<PromiseLine> lines, String destinationZip, String destinationState, String channel, String shipMethod) {}
public record Promise(LocalDate date, double onTimeProbability, String fromNodeId, String shipMethod, boolean fromCache, long latencyMs, List<PromiseOption> alternatives) {}
public interface PromiseService { Promise promise(PromiseRequest r); }   // FR-SRC-05: Caffeine cache (and Redis when configured) keyed by sku-set+zip+method, TTL 30 s; p95 < 300 ms; deterministic transit table + node reliability
```
Tables: `node`, `node_penalty`, `shipping_rate` (zone × weight band × service → cost, transit days), `sourcing_plan`. Config YAML `config/tenants/demo/nodes.yaml`.
REST: `/api/v1/nodes` (CRUD), `/api/v1/orders/{id}/sourcing/options`, `POST /api/v1/orders/{id}/sourcing` (source), `POST /api/v1/orders/{id}/resource`, `POST /api/v1/promise` (public, tenant header), `/api/v1/shipping-rates`.

### 3.8 oms-monitoring (`ai.intentoms.monitoring`)
`PipelineViewService.pipeline(filters)` → counts by state × age bucket × channel × node × orderType (FR-MON-01, served from `order` tables + line statuses; refresh < 5 s). `SlaDefinitionService` (per state & order type, table `sla_definition`, synced into state-model SLA timers). `ExceptionService.raise(ExceptionRequest)` → classifies via decision `exception_classify` (Choice: address, inventory_short, carrier_delay, payment, store_reject, system, other + escape) into type/severity/queue from taxonomy YAML (`config/tenants/demo/exception-taxonomy.yaml`), creates `exception` row + human task if the playbook requires; `PlaybookService.run(exception)` executes configured steps (`re_source`, `contact_customer`, `reship`, `refund`, `cancel`, `hold`, `notify`) gated by decision confidence and spend limits via `PlaybookAction` SPI (orchestration registers handlers) (FR-MON-04). `StuckOrderSweeper` (FR-MON-05): orders whose current state exceeds `expected_next_event_within` without an event → raise exception `stuck`. `BreachRiskScorer`: decision `sla_breach_risk` (Score 0–4) per open order on a sweep, stored in `order_risk`. `AlertService` (FR-MON-06): Slack webhook, Teams webhook, email (log), PagerDuty Events v2, with de-dup key + 15 min suppression (table `alert`). Subscribes to `sla.breached`, `exception.raised`, `store_rejected`.
REST: `/api/v1/control-tower/pipeline`, `/api/v1/control-tower/at-risk`, `/api/v1/exceptions` (list/get/resolve/assign), `/api/v1/playbooks`, `/api/v1/sla-definitions`, `/api/v1/alerts`.

### 3.9 oms-returns (`ai.intentoms.returns`)
`ReturnService.create(ReturnRequest)` from any channel incl. store and agent (FR-RET-01) → RMA `rma_<id>`; `decideEligibility(rmaId)`: L0 policy (return window per order type, final sale flag, receipt) + decision `return_risk` (Score 0–4 abuse risk + reason-code Nouls: `many_not_received_claims`, `serial_returner`, `high_value_mismatch`, `receipt_missing`) ⇒ refund timing choice {instant, on_carrier_scan, on_inspection, store_credit_only, escalate} (FR-RET-02/03, tier T2: act only ≥ 0.9); `recordScan(rmaId)`, `inspect(rmaId, condition)`, `disposition(rmaId)` decision `return_disposition` (Choice restock/refurbish/liquidate/return_to_vendor/donate/destroy), `keepIt` evaluation when return cost > recovery and risk low (FR-RET-05), `refund(rmaId)` via finance PaymentService with original tender split, partial, restocking fee (FR-RET-04). Return state model `return.standard` in statemachine. Events `return.*`.
REST: `/api/v1/returns` (POST, GET list, GET `/{rma}`), `POST /{rma}/decide`, `POST /{rma}/scan`, `POST /{rma}/inspect`, `POST /{rma}/refund`, `POST /{rma}/disposition`.

### 3.10 oms-agent (`ai.intentoms.agent`)
Registry (`agent_identity`: id, owner, protocol, verification {hmac secret | public key | api key}, trust tier, limits {allowed actions, max order value, max refund, rate/min, confirm actions}), `mandate` table (FR-CAP-07 / AP2-style: buyer ref, scope, spend limit, instruction text, signature, expiry). `AgentAuthFilter` verifies `X-Agent-Id` + `X-Agent-Signature` (HMAC-SHA256 over timestamp+method+path+body) or agent API key → `Principal(type=AGENT, trustTier, permissions from limits)`; unknown agents → read-only on orders whose provenance proves ownership (FR-AGT-03). `InjectionScreen.screen(text)` → decision `injection_screen` (Noul) — any text from agents/customers passes through before any LLM layer (GR-08, FR-AGT-05). **MCP server** at `POST /mcp` (JSON-RPC 2.0, Streamable HTTP, methods `initialize`, `tools/list`, `tools/call`, `ping`) exposing tools `order_get`, `order_search`, `promise`, `order_modify`, `order_cancel`, `return_create`, `inventory_check`, `decision_why`, `task_list`, `task_complete` with per-tool scope checks and per-agent limits (FR-AGT-01, 04, 06). **UCP** order capability at `/ucp/v1/orders/{id}` (GET), `/ucp/v1/orders` (POST place order with mandate), `/ucp/v1/orders/{id}/returns` (POST), version negotiated via header `UCP-Version` (default 2026-08-25) (FR-AGT-02); lifecycle webhooks to the agent's callback URL for shipped/delivered/returned/refunded via an `EventSubscriber`. `EvidencePackService.build(orderId)` (FR-AGT-09): mandate, identity, instruction text, decision trail, delivery proof → JSON.
REST: `/api/v1/agents` (CRUD, rotate secret), `/api/v1/mandates`, `/api/v1/orders/{id}/evidence-pack`.

### 3.11 oms-simulation (`ai.intentoms.simulation`)
`ReplayService.replay(orderId, Options{decisionVersionOverrides, stateModelVersion})` rebuilds the order from the ledger and re-runs each recorded decision with its stored input in REPLAY mode → diff per decision (FR-SIM-01). `BatchSimulationService.run(from, to, overrides)` → async job over orders in range: cost, split rate, on-time rate, margin, touchless rate, escalations (FR-SIM-02) using recorded inputs and sourcing economics. What-if (FR-SIM-03): overrides `{closedNodes, carrierOutage, tariffDelta}` applied to the candidate generator via `SimulationContext` (ThreadLocal) that sourcing consults.
REST: `POST /api/v1/simulations/replay/{orderId}`, `POST /api/v1/simulations/batch`, `GET /api/v1/simulations/{id}`.

### 3.12 oms-analytics (`ai.intentoms.analytics`)
`KpiService.dashboard(range)` → touchless rate, promise accuracy, split rate, cost to serve, margin-floor compliance, store reject rate, exception resolution median, WISMO contacts (from events), AI cost per order (FR-ANL-01, section 13). `DecisionAnalyticsService.perVersion(name)` → volume, band mix, override rate, calibration curve, latency p50/p95, cost (FR-ANL-02). REST `/api/v1/analytics/kpis`, `/api/v1/analytics/decisions/{name}`.

### 3.13 oms-orchestration
`StandardShipOrderWorkflow` (type `order.standard_ship`, v1): steps `validate` → `infer_intent` (decision fulfilment_intent) → `payment_authorize` → `source` (SourcingService.source; if requiresApproval → create human task queue `margin_floor_approvals` → WaitTask) → `reserve` (convert cart reservations / reserve) → `release` (line transitions `decision.sourcing_plan` → released; send release to WMS mock; compensation: release reservation, void auth) → `await_fulfilment` (WaitSignal shipped|store_rejected|short_pick|cancel_requested, timeout = SLA) → on `store_rejected`: transition `event.store_rejected`, `resource` → back to `source`; on `shipped`: transition, capture payment → `await_delivery` → `delivered` → Complete. BOPIS workflow `order.bopis` (source=pickup node → `ready_for_pickup` → signal `picked_up`). Return workflow `return.standard`. `HumanTaskEscalationHandler` implements decision `EscalationHandler`. `BulkActionHandler`s: resource, repromise, hold, release, cancel. `PlaybookAction`s. `DemoSeeder` (@Order(100), when `oms.demo.seed=true`): loads `config/tenants/demo/**` (state models, decisions, nodes, order types, taxonomy, playbooks, SLA, golden sets), inventory for ~40 SKUs across 6 nodes, 3 agents, 25 sample orders in various states, a few returns and exceptions. Mock integrations: `POST /webhooks/wms/{nodeId}` (shipped/short_pick/store_rejected), `POST /webhooks/carrier` (scan/delivered), `POST /webhooks/shopify` (order create → capture).

### 3.14 oms-learning (`ai.intentoms.learning`)
See `docs/UC_SELF_OPTIMIZING_ORCHESTRATION.md` for the full mechanism. Nine tables: `episode`, `outcome`,
`learned_fact`, `segment`, `hypothesis`, `proposal`, `experiment`, `objective_profile`, `learner`, plus propensity-
logging columns (`context_features`, `arms`, `chosen_arm`, `propensity`, `policy_version`) added to `oms-decision`'s
existing `decision_record` table. `EpisodeAssembler`/`OutcomeObserver` are `EventSubscriber`s on the existing
ledger (`decision.evaluated`, `order.delivered`, `order.shipped`, `refund.*`, `return.dispositioned`, `cs.wismo`,
`task.completed`) — no order-path module needs to know this module exists. `RewardMaturityService` moves episodes
OPEN → PROVISIONAL → MATURE and computes reward from the live `ObjectiveProfile`; `CoverageMonitor` auto-pauses
learners when outcome coverage drops below threshold. Parameter learners live in `.../learning/learner/`; the
Analyst/Author/Critic LLM roles extend `oms-copilot`; the off-policy evaluator extends `oms-simulation`; the
promotion gate and experiment manager live here and drive real changes through `oms-decision`'s existing
`DecisionStudioService` publish/rollback path — nothing in this module calls `publish()` directly except the
promotion gate running as the system principal, and only after replay + shadow/canary evidence and the tier-based
autonomy check in `PromotionGuard` pass.
REST: `/api/v1/learning/{learners,facts,proposals,objective,kill-switch,experiments}`. MCP: `learning_status`,
`explain_policy_change` (both read-only).

## 4. UI (ui/, React + Vite + TypeScript)
Pages: Login · Control Tower (pipeline heatmap, at-risk, exceptions inbox) · Orders (search, order view with line quantity statuses, holds, timeline, decisions with **Why** drawer) · Tasks (queues, claim/complete) · Returns · Policy Studio (decision list, version diff, YAML editor with structured fields, lint, impact diff, golden-set evaluation, approvals, rollout controls, kill switch) · State Models (list, YAML, validation report, versions) · Agents (registry, limits, mandates, evidence pack) · Analytics (KPI tiles, decision calibration curve) · Simulation (replay diff, batch run) · Settings (tenant config). Auth via `/api/v1/auth/login` JWT. Built assets copied to `backend/oms-app/src/main/resources/static` by `scripts/build-ui.sh`; Vite dev proxy to :8080.

## 5. Guardrail placement (section 8 of the PRD)
GR-01 pre/postChecks in DecisionRuntime + state-machine guards; GR-02/03 Bands + escape option lint; GR-04 enumerated answers only (provider schemas); GR-05 `SpendGuard` in finance (velocity caps per decision/agent/role/tenant, circuit breaker on aggregate refund/appeasement spend); GR-06 kill switches in TenantConfigStore read by DecisionRuntime; GR-07 timeouts/fallback chain in DecisionRuntime, capture never calls AI synchronously; GR-08 InjectionScreen + untrusted text only in `state` data fields; GR-09 Tokenizer in state builder; GR-10/11/14 DecisionStudioService; GR-12 `CalibrationMonitor` weekly job in decision module; GR-13 `override` records; GR-15 CS copilot/customer text fills typed slots only (returns/CS); GR-16 lint forbids protected attributes in state includes; GR-17 AI budget check in DecisionRuntime (tenant daily budget → degrade to L0 when exceeded, alert).
