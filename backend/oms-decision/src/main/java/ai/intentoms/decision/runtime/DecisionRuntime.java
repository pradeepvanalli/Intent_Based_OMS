package ai.intentoms.decision.runtime;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.DefinitionCache;
import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.Bands;
import ai.intentoms.decision.model.CheckResult;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.DecisionTrace;
import ai.intentoms.decision.model.Invocation;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.provider.DecisionProvider;
import ai.intentoms.decision.provider.ProviderException;
import ai.intentoms.decision.provider.ProviderRegistry;
import ai.intentoms.decision.provider.ProviderRequest;
import ai.intentoms.decision.provider.ProviderResponse;
import ai.intentoms.decision.record.AiSpendRepository;
import ai.intentoms.decision.record.DecisionCacheRepository;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.record.TraceBuilder;
import ai.intentoms.decision.rules.CelVars;
import ai.intentoms.decision.rules.CheckEvaluator;
import ai.intentoms.decision.rules.RuleEngine;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The decision fabric runtime (FR-DEC-02..11). Algorithm per ARCHITECTURE section 3.3: resolve version (live, canary by
 * stable hash of subjectId, shadow versions run afterwards on a virtual thread with mode SHADOW and never act) → L0
 * rules first-match (FR-DEC-03) → kill switches (GR-06) → daily AI budget (GR-17) → preChecks (GR-01) → minimal
 * tokenised state (GR-09) → topK two-stage for large candidate sets (FR-DEC-05) → provider with budget and fallback
 * chain provider → fallbackProvider → L0 DEFAULT (GR-07, section 9 rule 9) → composite (FR-DEC-04) → escape option ⇒
 * ESCALATE (GR-03) → bands by tier (GR-02) → postChecks (GR-01; failure discards the answer) → CONFIRM on T1/T2 ⇒ L2
 * LLM proposing one enumerated option, else L3 via {@link EscalationHandler}; T3 never acts → persist record +
 * {@code decision.evaluated} event (FR-DEC-06) → result cache (FR-DEC-11).
 */
@Service
public class DecisionRuntime implements DecisionService {
    private static final Logger log = LoggerFactory.getLogger(DecisionRuntime.class);
    private static final ExecutorService SHADOW = Executors.newVirtualThreadPerTaskExecutor();
    private static final int MIN_BUDGET_MS = 20;

    private final DecisionDefinitionRepository definitions;
    private final DefinitionCache definitionCache;
    private final ProviderRegistry providers;
    private final RuleEngine rules;
    private final CheckEvaluator checks;
    private final StateBuilder stateBuilder;
    private final DecisionRecordRepository records;
    private final DecisionCacheRepository cache;
    private final AiSpendRepository spend;
    private final TenantConfigStore config;
    private final EventStore events;
    private final Metrics metrics;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final EscalationHandler escalations;
    private final TransactionTemplate tx;
    private final TraceBuilder traces;
    private final org.springframework.beans.factory.ObjectProvider<DecisionLearningHooks> hookProvider;
    private volatile DecisionLearningHooks hooks;

    public DecisionRuntime(DecisionDefinitionRepository definitions, DefinitionCache definitionCache, ProviderRegistry providers, RuleEngine rules, CheckEvaluator checks,
                           StateBuilder stateBuilder, DecisionRecordRepository records, DecisionCacheRepository cache, AiSpendRepository spend, TenantConfigStore config,
                           EventStore events, Metrics metrics, TimeSource time, AuthorizationService authz, AuditLog audit, EscalationHandler escalations,
                           TransactionTemplate tx, TraceBuilder traces, org.springframework.beans.factory.ObjectProvider<DecisionLearningHooks> hooks) {
        this.definitions = definitions;
        this.definitionCache = definitionCache;
        this.providers = providers;
        this.rules = rules;
        this.checks = checks;
        this.stateBuilder = stateBuilder;
        this.records = records;
        this.cache = cache;
        this.spend = spend;
        this.config = config;
        this.events = events;
        this.metrics = metrics;
        this.time = time;
        this.authz = authz;
        this.audit = audit;
        this.escalations = escalations;
        this.tx = tx;
        this.traces = traces;
        this.hookProvider = hooks;
    }

    // ------------------------------------------------------------------ DecisionService

    @Override
    public DecisionResult decide(String decisionName, Map<String, Object> input, DecisionOptions opts) {
        DecisionOptions o = opts == null ? DecisionOptions.live(null, null, Actor.system("decision-runtime")) : opts;
        Resolution r = resolve(decisionName, o);
        DecisionDefinition selected = r.selected();
        Map<String, Object> routing = null;
        if (o.mode() == Mode.LIVE && o.versionOverride() == null) {
            Optional<DecisionLearningHooks.Route> route = safely(() -> hooks().route(decisionName, routingKey(o), r.selected().version()), Optional.empty());
            if (route.isPresent()) {
                DecisionLearningHooks.Route rt = route.get();
                if (rt.version() != selected.version()) {
                    selected = definitions.find(decisionName, rt.version()).map(DecisionDefinitionRepository.Row::definition).orElse(selected);
                }
                routing = Map.of("experimentId", rt.experimentId(), "arm", rt.arm(), "version", selected.version());
            }
        }
        DecisionResult result = evaluate(selected, input, o.withVersion(selected.version()), routing);
        if (o.mode() == Mode.LIVE && !r.shadows().isEmpty()) runShadows(r.shadows(), input, o);
        return result;
    }

    @Override
    public List<DecisionResult> decideAll(List<Invocation> batch) {
        List<DecisionResult> out = new ArrayList<>(batch.size());
        for (Invocation inv : batch) out.add(decide(inv.decisionName(), inv.input(), inv.options()));
        return out;
    }

    @Override
    public DecisionRecord record(String recordId) {
        return records.find(recordId).orElseThrow(() -> new NotFoundException("Decision record " + recordId + " not found"));
    }

    @Override
    public List<DecisionRecord> forSubject(String subjectType, String subjectId) { return records.forSubject(subjectType, subjectId); }

    @Override
    public DecisionTrace why(String recordId) { return traces.build(record(recordId)); }

    @Override
    public void label(String recordId, String label, Map<String, Object> details) {
        if (label == null || label.isBlank()) throw new ValidationException("label is required");
        DecisionRecord r = record(recordId);
        Map<String, Object> d = details == null ? Map.of() : details;
        tx.executeWithoutResult(s -> {
            records.label(recordId, label, d);
            events.append("decision", recordId, new NewEvent("decision.labelled", Map.of("decision", r.decision(), "version", r.version(), "label", label, "details", d),
                Actor.parse(authz.actorRef()), null, r.correlationId(), subjectMeta(r.subjectType(), r.subjectId())));
        });
    }

    @Override
    public DecisionRecord override(String recordId, Object newValue, String reason) {
        authz.require(Permissions.DECISION_OVERRIDE);
        if (reason == null || reason.isBlank()) throw new ValidationException("override reason is required (GR-13)");
        DecisionRecord r = record(recordId);
        String by = authz.actorRef();
        Instant now = time.now();
        tx.executeWithoutResult(s -> {
            records.override(recordId, newValue, reason, by, now);
            events.append("decision", recordId, new NewEvent("decision.overridden", Map.of("decision", r.decision(), "version", r.version(), "previousValue", nullSafe(r.value()),
                "newValue", nullSafe(newValue), "reason", reason), Actor.parse(by), null, r.correlationId(), subjectMeta(r.subjectType(), r.subjectId())));
            audit.record("decision.override", "decision_record", recordId, reason, Map.of("decision", r.decision(), "version", r.version(), "newValue", nullSafe(newValue)));
        });
        metrics.count("oms.decision.overridden", "decision", r.decision());
        return record(recordId);
    }

    // ------------------------------------------------------------------ version resolution

    /** Selected version plus the shadow versions to run alongside (FR-DEC-09, FR-PRM-04). */
    public record Resolution(DecisionDefinition selected, List<DecisionDefinition> shadows) {}

    public Resolution resolve(String name, DecisionOptions opts) {
        if (opts.versionOverride() != null) {
            DecisionDefinition def = definitions.find(name, opts.versionOverride()).map(DecisionDefinitionRepository.Row::definition)
                .orElseThrow(() -> new NotFoundException("Decision " + name + " version " + opts.versionOverride() + " not found"));
            return new Resolution(def, List.of());
        }
        List<DecisionDefinitionRepository.Row> active = definitionCache.active(name, n -> definitions.byStatus(n, "live", "canary", "shadow"));
        DecisionDefinition live = null, canary = null;
        List<DecisionDefinition> shadows = new ArrayList<>();
        for (var row : active) {
            DecisionDefinition d = row.definition();
            switch (d.status()) {
                case "live" -> { if (live == null) live = d; }
                case "canary" -> { if (canary == null) canary = d; }
                case "shadow" -> shadows.add(d);
                default -> { }
            }
        }
        if (live == null && canary == null) throw new NotFoundException("No live version of decision " + name);
        DecisionDefinition selected = live != null ? live : canary;
        if (live != null && canary != null && inCanary(opts.subjectId(), canary.canaryPercent())) selected = canary;
        return new Resolution(selected, shadows);
    }

    /**
     * Experiments split by customer: {@code customer:<ref>} when the caller passes {@code customerRef}, else
     * {@code <subjectType>:<subjectId>}, which the learning layer resolves to the subject's customer (EX-03 / EX-04).
     */
    private static String routingKey(DecisionOptions o) {
        Object c = o.extraVars().get("customerRef");
        if (c != null && !String.valueOf(c).isBlank()) return "customer:" + c;
        if (o.subjectId() == null || o.subjectId().isBlank()) return null;
        return o.subjectType() == null || o.subjectType().isBlank() ? o.subjectId() : o.subjectType() + ":" + o.subjectId();
    }

    /** Resolved on first use, not in the constructor: the learning implementation may itself depend on decisions. */
    private DecisionLearningHooks hooks() {
        DecisionLearningHooks h = hooks;
        if (h == null) hooks = h = hookProvider.getIfAvailable(() -> DecisionLearningHooks.NOOP);
        return h;
    }

    /** A learning hook must never break a decision (GR-07): any failure falls back to behaving as if it were absent. */
    private <T> T safely(java.util.function.Supplier<T> call, T fallback) {
        try { return call.get(); }
        catch (RuntimeException e) {
            metrics.count("oms.decision.learning_hook.failure");
            log.warn("Learning hook failed, continuing without it: {}", e.toString());
            return fallback;
        }
    }

    /** Stable per-subject canary assignment: the same subject always sees the same version (FR-PRM-04). */
    public static boolean inCanary(String subjectId, int percent) {
        if (percent <= 0) return false;
        if (percent >= 100) return true;
        String key = subjectId == null ? Ids.id() : subjectId;
        int bucket = Math.floorMod(key.hashCode(), 100);
        return bucket < percent;
    }

    private void runShadows(List<DecisionDefinition> shadows, Map<String, Object> input, DecisionOptions opts) {
        String tenant = TenantContext.require();
        for (DecisionDefinition shadow : shadows) {
            DecisionOptions so = opts.withMode(Mode.SHADOW).withVersion(shadow.version());
            CompletableFuture.runAsync(() -> TenantContext.runAs(tenant, () -> {
                try { evaluate(shadow, input, so); }
                catch (RuntimeException e) { log.warn("Shadow evaluation of {} failed: {}", shadow.key(), e.getMessage()); }
            }), SHADOW);
        }
    }

    // ------------------------------------------------------------------ evaluation pipeline

    /** Mutable evaluation context; one per evaluate() call. */
    private final class Eval {
        final DecisionDefinition def;
        final Map<String, Object> input;
        final DecisionOptions opts;
        final String recordId = Ids.id();
        final String inputHash;
        final long startNanos = System.nanoTime();
        /** Time spent in the learning hooks before this evaluation started; counted in the recorded latency. */
        long hookNanos;
        /** Set when this evaluation reproduces a recorded decision (NF-04): its calibration and provider route are re-applied. */
        DecisionRecord recorded;
        final long deadlineNanos;
        final Instant startedAt;
        Map<String, Object> state = Map.of();
        Map<String, Answer> answers = new LinkedHashMap<>();
        List<String> rulesFired = new ArrayList<>();
        List<String> reasonCodes = new ArrayList<>();
        List<CheckResult> preChecks = List.of();
        List<CheckResult> postChecks = List.of();
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        Layer layer = Layer.DEFAULT;
        Band band = Band.ESCALATE;
        String action = DecisionResult.DEFAULT;
        Object value;
        double confidence;
        boolean requiresHuman;
        String escalationId;
        String provider;
        String modelVersion;
        int calls;
        int inputTokens;
        double costUsd;
        Map<String, int[]> spendCalls = new LinkedHashMap<>();
        Map<String, double[]> spendCost = new LinkedHashMap<>();
        boolean postCheckFailed;

        Eval(DecisionDefinition def, Map<String, Object> input, DecisionOptions opts) {
            this.def = def;
            this.input = input == null ? Map.of() : input;
            this.opts = opts;
            this.inputHash = Json.sha256(this.input);
            this.deadlineNanos = startNanos + def.timeoutMs() * 1_000_000L;
            this.startedAt = time.now();
            this.value = def.dflt();
            details.put("tier", def.tier().name());
            details.put("bands", Map.of("act", def.effectiveBands().actGte(), "confirm", def.effectiveBands().confirmGte()));
        }

        int remainingMs() { return (int) Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000); }
        long latencyMs() { return (System.nanoTime() - startNanos + hookNanos) / 1_000_000; }

        /** Provider chain: provider → fallbackProvider (GR-07, section 9 rule 9). Returns null when every option failed. */
        ProviderResponse ask(Object askState, Map<String, QuestionDef> questions) {
            List<String> chain = new ArrayList<>();
            if (opts.mode() == Mode.LIVE || opts.mode() == Mode.SHADOW) {
                safely(() -> hooks().preferredProvider(def.name(), def.version(), routingKey(opts)), Optional.<String>empty()).ifPresent(pref -> {
                    chain.add(pref);
                    details.put("providerRouted", pref);
                });
            } else if (recorded != null && recorded.details() != null && recorded.details().get("providerRouted") != null) {
                String pref = String.valueOf(recorded.details().get("providerRouted"));
                chain.add(pref);
                details.put("providerRouted", pref);
            }
            if (def.provider() != null && !chain.contains(def.provider())) chain.add(def.provider());
            if (def.fallbackProvider() != null && !chain.contains(def.fallbackProvider())) chain.add(def.fallbackProvider());
            for (String name : chain) {
                Optional<DecisionProvider> p = providers.usable(name);
                if (p.isEmpty()) { failures.add(name + ": unavailable or killed"); continue; }
                int remaining = remainingMs();
                if (remaining < MIN_BUDGET_MS) { failures.add(name + ": time budget exhausted"); break; }
                try {
                    ProviderResponse res = p.get().ask(new ProviderRequest(askState, questions, remaining, null));
                    account(p.get().name(), res);
                    return res;
                } catch (ProviderException e) {
                    failures.add(e.getMessage());
                    metrics.count("oms.decision.provider.failure", "provider", p.get().name(), "timeout", String.valueOf(e.isTimeout()));
                    log.warn("Provider {} failed for {}: {}", p.get().name(), def.key(), e.getMessage());
                } catch (RuntimeException e) {
                    failures.add(name + ": " + e.getMessage());
                    metrics.count("oms.decision.provider.failure", "provider", p.get().name(), "timeout", "false");
                    log.warn("Provider {} threw for {}", p.get().name(), def.key(), e);
                }
            }
            return null;
        }

        void account(String providerName, ProviderResponse res) {
            provider = providerName;
            modelVersion = res.modelVersion();
            calls++;
            inputTokens += res.inputTokens();
            costUsd += res.costUsd();
            spendCalls.computeIfAbsent(providerName, k -> new int[]{0, 0});
            spendCalls.get(providerName)[0]++;
            spendCalls.get(providerName)[1] += res.inputTokens();
            spendCost.computeIfAbsent(providerName, k -> new double[]{0})[0] += res.costUsd();
        }

        void toDefault(String reason) {
            layer = Layer.DEFAULT;
            band = Band.ESCALATE;
            action = DecisionResult.DEFAULT;
            value = def.dflt();
            confidence = 0;
            requiresHuman = false;
            details.put("defaultReason", reason);
            if (!failures.isEmpty()) details.put("failures", List.copyOf(failures));
        }
    }

    /**
     * NF-04: re-runs a recorded decision exactly as it was made: on {@code def} (normally the recorded version), from its
     * recorded input (which already carries the learned facts it used), re-applying the calibration, provider route and
     * experiment arm it recorded, so the result matches even after live versions, facts and promotions have moved on.
     * Never acts, escalates or caches; persists a record in {@code mode} that points at the original.
     */
    public DecisionResult reproduce(DecisionDefinition def, DecisionRecord recorded, Mode mode, Actor actor) {
        if (mode == Mode.LIVE || mode == Mode.SHADOW) throw new IllegalArgumentException("reproduce runs in REPLAY or SIMULATION mode only");
        DecisionOptions opts = new DecisionOptions(recorded.subjectType(), recorded.subjectId(),
            recorded.correlationId() == null ? recorded.subjectId() : recorded.correlationId(), mode, def.version(), actor, Map.of());
        Eval ev = new Eval(def, recorded.input(), opts);
        ev.recorded = recorded;
        ev.details.put("replayOf", recorded.id());
        if (recorded.details() != null && recorded.details().get("experiment") != null) ev.details.put("experiment", recorded.details().get("experiment"));
        if (recorded.details() != null && recorded.details().get("learnedFacts") != null) ev.details.put("learnedFacts", recorded.details().get("learnedFacts"));
        try {
            pipeline(ev);
        } catch (RuntimeException e) {
            log.error("Reproducing decision {} failed, returning default", recorded.id(), e);
            ev.failures.add("runtime: " + e.getMessage());
            ev.toDefault("runtime_error");
        }
        return persist(ev);
    }

    /** The calibrated confidence the original evaluation used, re-applied when the raw confidence matches. */
    private static void reapplyRecordedCalibration(Eval ev) {
        Map<String, Object> d = ev.recorded.details();
        if (d == null || !(d.get("rawConfidence") instanceof Number recordedRaw) || d.get("calibration") == null) return;
        if (Math.abs(recordedRaw.doubleValue() - ev.confidence) > 1e-9) return;
        double raw = ev.confidence;
        ev.confidence = ev.recorded.confidence();
        ev.details.put("rawConfidence", raw);
        Map<String, Object> source = new LinkedHashMap<>();
        if (d.get("calibration") instanceof Map<?, ?> m) m.forEach((k, v) -> source.put(String.valueOf(k), v));
        source.put("reappliedFrom", ev.recorded.id());
        ev.details.put("calibration", source);
    }

    /** Evaluates one specific definition version (also used by shadow, simulation, impact diffs and golden evaluation). */
    public DecisionResult evaluate(DecisionDefinition def, Map<String, Object> input, DecisionOptions opts) {
        return evaluate(def, input, opts, null);
    }

    private DecisionResult evaluate(DecisionDefinition def, Map<String, Object> input, DecisionOptions opts, Map<String, Object> routing) {
        long hookStart = System.nanoTime();
        List<Map<String, Object>> factsUsed = List.of();
        if (opts.mode() == Mode.LIVE || opts.mode() == Mode.SHADOW) {
            Map<String, Object> raw = input == null ? Map.of() : input;
            DecisionLearningHooks.Contribution c = safely(() -> hooks().contribute(def.name(), raw, opts), DecisionLearningHooks.Contribution.none(raw));
            input = c.input() == null ? raw : c.input();
            factsUsed = c.factsUsed() == null ? List.of() : c.factsUsed();
        }
        Eval ev = new Eval(def, input, opts);
        ev.hookNanos = ev.startNanos - hookStart;
        if (!factsUsed.isEmpty()) ev.details.put("learnedFacts", factsUsed);
        if (routing != null) ev.details.put("experiment", routing);
        if (opts.mode() == Mode.LIVE && def.cache() != null) {
            Optional<DecisionResult> hit = cache.get(def.key(), ev.inputHash, ev.startedAt);
            if (hit.isPresent()) {
                metrics.count("oms.decision.cache", "decision", def.name(), "hit", "true");
                return persistCacheHit(ev, hit.get());
            }
            metrics.count("oms.decision.cache", "decision", def.name(), "hit", "false");
        }
        try {
            pipeline(ev);
        } catch (RuntimeException e) {
            // GR-07: the fabric never throws on an internal failure; the L0 default is returned and the failure recorded.
            log.error("Decision {} failed unexpectedly, returning default", def.key(), e);
            ev.failures.add("runtime: " + e.getMessage());
            ev.toDefault("runtime_error");
        }
        return persist(ev);
    }

    private void pipeline(Eval ev) {
        DecisionDefinition def = ev.def;
        Map<String, Object> tenantMap = (def.rules().isEmpty() && def.preChecks().isEmpty() && def.postChecks().isEmpty()) ? Map.of() : config.celTenantMap();
        Map<String, Object> vars = CelVars.base(ev.input, tenantMap, ev.opts.extraVars());

        // L0 rules always win (FR-DEC-02, FR-DEC-03).
        Optional<RuleEngine.Match> match = rules.firstMatch(def.rules(), vars);
        match.ifPresent(m -> { if (!m.errored().isEmpty()) ev.details.put("rulesErrored", m.errored()); });
        if (match.isPresent()) {
            RuleDef rule = match.get().rule();
            ev.rulesFired.add(rule.id());
            ev.layer = Layer.L0_RULE;
            ev.confidence = 1.0;
            if (rule.reason() != null) ev.reasonCodes.add(rule.reason());
            if (rule.blocks()) {
                ev.band = Band.ESCALATE;
                ev.action = DecisionResult.BLOCKED;
                ev.value = def.dflt();
            } else if (rule.escalates()) {
                ev.band = Band.ESCALATE;
                ev.action = DecisionResult.ESCALATE;
                ev.requiresHuman = true;
                ev.value = def.dflt();
                escalate(ev, "l0_rule:" + rule.id());
            } else {
                ev.band = Band.ACT;
                ev.action = DecisionResult.ACT;
                ev.value = coerce(rule.then());
            }
            return;
        }

        // Kill switches (GR-06) and daily budget (GR-17) degrade to the deterministic default.
        if (config.getBoolean(ConfigKeys.AI_KILL_SWITCH, false) || config.getBoolean(ConfigKeys.DECISION_KILL_SWITCH_PREFIX + def.name(), false)) {
            metrics.count("oms.decision.kill_switch", "decision", def.name());
            ev.toDefault("kill_switch");
            return;
        }
        double budget = config.getDouble(ConfigKeys.AI_BUDGET_USD_PER_DAY, -1);
        if (budget >= 0 && ev.opts.mode() != Mode.SIMULATION && spend.totalForDay(ev.startedAt.atZone(ZoneOffset.UTC).toLocalDate()) >= budget) {
            metrics.count("oms.decision.budget_exceeded", "decision", def.name());
            log.warn("Tenant {} AI budget of ${} exceeded; decision {} degraded to default", TenantContext.require(), budget, def.name());
            ev.toDefault("budget_exceeded");
            return;
        }

        // Pre-checks (GR-01).
        ev.preChecks = checks.evaluate(def.preChecks(), vars);
        if (!CheckEvaluator.allPassed(ev.preChecks)) {
            ev.toDefault("precheck_failed");
            ev.action = DecisionResult.BLOCKED;
            ev.reasonCodes.add("precheck_failed");
            return;
        }

        // Minimal tokenised state (GR-09).
        ev.state = stateBuilder.build(def, ev.input);

        // Provider stage: two-stage topK or one speculative fan-out call (section 9 rules 3 and 5).
        String mainKey = null;
        if (def.topK() != null) {
            TopKStage.Outcome tk;
            try { tk = TopKStage.run(def, ev.state, ev.input, req -> { ProviderResponse r = ev.ask(req.state(), req.questions()); if (r == null) throw new ProviderException("all providers failed", false, 0, false, null); return r; }); }
            catch (ProviderException e) { ev.failures.add(e.getMessage()); tk = null; ev.details.put("topK", Map.of("failed", true)); }
            if (tk != null) {
                ev.answers.putAll(tk.answers());
                ev.details.put("topK", Map.of("candidates", tk.candidates(), "calls", tk.calls(), "ranking", tk.ranking()));
                mainKey = tk.mainKey();
            } else if (!ev.failures.isEmpty()) { ev.toDefault("provider_unavailable"); return; }
        }
        if (mainKey == null) {
            ProviderResponse res = ev.ask(ev.state, def.questions());
            if (res == null) { ev.toDefault("provider_unavailable"); return; }
            ev.answers.putAll(res.answers());
            if (def.composite() != null) {
                ev.answers.put(def.composite().outputKey(), CompositeScorer.combine(def.composite(), def.questions(), ev.answers));
                mainKey = def.main().filter(QuestionDef::isChoice).map(QuestionDef::key).orElse(def.composite().outputKey());
            } else mainKey = def.main().map(QuestionDef::key).orElse(null);
            if (def.mainQuestion() != null) mainKey = def.mainQuestion();
        }
        ev.layer = Layer.L1_SYSTEM_ONE;
        Answer main = mainKey == null ? null : ev.answers.get(mainKey);
        if (main == null) { ev.failures.add("no answer for main question " + mainKey); ev.toDefault("missing_main_answer"); return; }
        ev.details.put("mainQuestion", mainKey);
        QuestionDef mainQ = def.questions().get(mainKey);
        ev.value = mainValue(main, mainQ);
        ev.confidence = main.confidence();
        if (ev.opts.mode() == Mode.LIVE || ev.opts.mode() == Mode.SHADOW) {
            double raw = ev.confidence;
            DecisionLearningHooks.Calibrated cal = safely(() -> hooks().calibrate(def.name(), def.version(), raw, routingKey(ev.opts)), new DecisionLearningHooks.Calibrated(raw, null));
            if (cal.source() != null && cal.confidence() != raw) {
                ev.confidence = Math.max(0.0, Math.min(1.0, cal.confidence()));
                ev.details.put("rawConfidence", raw);
                ev.details.put("calibration", cal.source());
            }
        } else if (ev.recorded != null) {
            reapplyRecordedCalibration(ev);
        }

        // Reason codes by decomposition (section 9 rule 6).
        for (QuestionDef q : def.questions().values()) {
            Answer a = ev.answers.get(q.key());
            if (q.reasonCode() && a != null && a.asBoolean() && q.isNoul()) ev.reasonCodes.add(q.key());
        }

        // Bands (GR-02) and escape option (GR-03).
        Bands bands = def.effectiveBands();
        boolean escape = mainQ != null && mainQ.isEscape(ev.value);
        if (escape) { ev.band = Band.ESCALATE; ev.reasonCodes.add("escape_option"); }
        else ev.band = bands.of(ev.confidence);

        if (def.tier() == Tier.T0 && ev.band == Band.ESCALATE) {
            // T0: low confidence yields the escape/"unclassified" value and still acts (PRD tier table).
            ev.value = def.escapeValue();
            ev.action = DecisionResult.ACT;
            ev.reasonCodes.add("low_confidence");
        } else ev.action = switch (ev.band) { case ACT -> DecisionResult.ACT; case CONFIRM -> DecisionResult.CONFIRM; case ESCALATE -> DecisionResult.ESCALATE; };

        // EX-01 exploration floor: on a governed share of T0 decisions another allowed arm is applied, with its true
        // propensity logged (1 - share for the normal choice). Only on the live path and only for a model answer.
        if (ev.opts.mode() == Mode.LIVE && def.tier() == Tier.T0 && DecisionResult.ACT.equals(ev.action) && ev.layer == Layer.L1_SYSTEM_ONE) explore(ev, mainQ);
        else if (ev.recorded != null) reapplyRecordedExploration(ev, mainQ);

        // Post-checks (GR-01): a violating answer is discarded and logged.
        Map<String, Object> postVars = CelVars.withAnswers(vars, ev.answers, ev.value, ev.confidence, ev.band.name());
        ev.postChecks = checks.evaluate(def.postChecks(), postVars);
        if (!CheckEvaluator.allPassed(ev.postChecks)) {
            ev.postCheckFailed = true;
            metrics.count("oms.decision.postcheck_failed", "decision", def.name());
            log.warn("decision.postcheck_failed decision={} version={} record={} checks={}", def.name(), def.version(), ev.recordId,
                ev.postChecks.stream().filter(c -> !c.passed()).map(CheckResult::expression).toList());
            ev.details.put("discardedValue", nullSafe(ev.value));
            ev.toDefault("postcheck_failed");
            ev.reasonCodes.add("postcheck_failed");
            return;
        }

        // Escalation path: CONFIRM on T1/T2 tries L2 first; T3 and ESCALATE go to L3.
        if (ev.band == Band.CONFIRM && (def.tier() == Tier.T1 || def.tier() == Tier.T2)) {
            if (tryL2(ev, mainQ, bands)) return;
            if (def.tier() == Tier.T1) {
                // T1 (reversible operational) confirm band with no L2 verdict: no human hand-off. The model's answer stays
                // the recorded value (so golden sets and calibration measure the model) but action is CONFIRM, so callers
                // apply their deterministic default (PRD Appendix B "confirm: apply channel default and notify"; tier table:
                // T1 acts within bounds). Only T2+ confirm-band cases go to a person.
                ev.details.put("confirmFallback", Map.of("defaultValue", nullSafe(def.dflt()),
                    "reason", "confirm band on T1 with no L2 verdict: caller applies the deterministic default"));
                ev.requiresHuman = false;
                ev.reasonCodes.add("confirm_default_applied");
                metrics.count("oms.decision.confirm_default", "decision", def.name());
                return;
            }
            escalate(ev, "confirm_band");
            return;
        }
        if (ev.band == Band.CONFIRM || (ev.band == Band.ESCALATE && def.tier() != Tier.T0)) {
            escalate(ev, escape ? "escape_option" : def.tier().neverActs() ? "t3_recommendation" : "low_confidence");
        }
    }

    /** Asks the learning layer whether this decision explores, and applies the arm it draws (see {@link DecisionLearningHooks#explore}). */
    private void explore(Eval ev, QuestionDef mainQ) {
        if (ev.value == null) return;
        String chosen = String.valueOf(ev.value);
        List<String> arms = armsOf(ev.def, ev.input, ev.details, ev.value, chosen);
        Optional<DecisionLearningHooks.Exploration> x = safely(() -> hooks().explore(ev.def.name(), ev.def.tier(), List.copyOf(arms), chosen, routingKey(ev.opts)), Optional.empty());
        if (x.isEmpty() || x.get().applied() == null || x.get().propensity() <= 0 || x.get().propensity() > 1) return;
        applyExploration(ev, chosen, x.get().applied(), x.get().propensity(), x.get().share(), x.get().seed(), x.get().reason());
    }

    /** NF-04: a replay re-applies the arm the recorded decision explored, so it reproduces what was applied. */
    private void reapplyRecordedExploration(Eval ev, QuestionDef mainQ) {
        Map<String, Object> d = ev.recorded.details();
        if (d == null || !(d.get("exploration") instanceof Map<?, ?> x) || !Boolean.TRUE.equals(x.get("explored")) || x.get("applied") == null) return;
        if (ev.value == null || !String.valueOf(ev.value).equals(String.valueOf(x.get("normalArm")))) return;
        double p = x.get("propensity") instanceof Number n ? n.doubleValue() : 1.0;
        double share = x.get("share") instanceof Number n ? n.doubleValue() : 0.0;
        long seed = x.get("seed") instanceof Number n ? n.longValue() : 0L;
        applyExploration(ev, String.valueOf(ev.value), String.valueOf(x.get("applied")), p, share, seed, null);
    }

    private void applyExploration(Eval ev, String chosen, String applied, double propensity, double share, long seed, String reason) {
        boolean explored = !applied.equals(chosen);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("share", share);
        info.put("explored", explored);
        if (reason != null) info.put("reason", reason);
        info.put("normalArm", chosen);
        info.put("applied", applied);
        info.put("propensity", propensity);
        info.put("seed", seed);
        ev.details.put("exploration", info);
        if (!explored) return;
        ev.details.put("modelAnswer", nullSafe(ev.value));
        ev.value = typedLike(applied, ev.value);
        ev.reasonCodes.add("explored");
    }

    /** An arm (always a string) in the type of the value it replaces: a score level stays a number, a yes/no a boolean. */
    static Object typedLike(String arm, Object like) {
        if (like instanceof Boolean) return Boolean.parseBoolean(arm);
        if (like instanceof Number) {
            try { return arm.contains(".") ? (Object) Double.parseDouble(arm) : (Object) Long.parseLong(arm); } catch (NumberFormatException e) { return arm; }
        }
        return arm;
    }

    /** L2: the LLM may propose one of the same enumerated options; accepted only when valid, non-escape and above the act threshold (GR-04). */
    private boolean tryL2(Eval ev, QuestionDef mainQ, Bands bands) {
        if (mainQ == null || !mainQ.isChoice() || ev.def.tier().neverActs()) return false;
        Optional<DecisionProvider> llm = providers.llm();
        if (llm.isEmpty()) { ev.details.put("l2", Map.of("attempted", false, "reason", "no LLM provider available")); return false; }
        int remaining = ev.remainingMs();
        Map<String, Object> l2State = new LinkedHashMap<>();
        l2State.put("state", ev.state);
        Map<String, Object> trace = new LinkedHashMap<>();
        ev.answers.forEach((k, a) -> trace.put(k, Map.of("value", nullSafe(a.value()), "confidence", a.confidence(), "probabilities", a.probabilities())));
        l2State.put("system_one_trace", trace);
        QuestionDef l2q = new QuestionDef(mainQ.key(), QuestionDef.CHOICE, mainQ.effectiveInstructions() + " The System One model was not confident; decide among the same options using the full state.",
            mainQ.choiceCriteria(), List.of(), null, false, mainQ.feeds(), mainQ.escapeOption());
        Map<String, Object> l2 = new LinkedHashMap<>();
        l2.put("attempted", true);
        l2.put("provider", llm.get().name());
        try {
            ProviderResponse res = llm.get().ask(new ProviderRequest(l2State, Map.of(l2q.key(), l2q), Math.max(remaining, MIN_BUDGET_MS), null));
            ev.account(llm.get().name(), res);
            Answer a = res.answers().get(l2q.key());
            l2.put("value", a == null ? null : a.value());
            l2.put("confidence", a == null ? 0 : a.confidence());
            boolean accepted = a != null && mainQ.choiceCriteria().containsKey(String.valueOf(a.value())) && !mainQ.isEscape(a.value()) && a.confidence() >= bands.actGte();
            l2.put("accepted", accepted);
            ev.details.put("l2", l2);
            if (accepted) {
                ev.answers.put(mainQ.key() + "__l2", a);
                ev.layer = Layer.L2_LLM;
                ev.band = Band.ACT;
                ev.action = DecisionResult.ACT;
                ev.value = a.value();
                ev.confidence = a.confidence();
                return true;
            }
        } catch (RuntimeException e) {
            l2.put("error", e.getMessage());
            ev.details.put("l2", l2);
            ev.failures.add(llm.get().name() + ": " + e.getMessage());
        }
        return false;
    }

    /** L3: hand to a human (FR-DEC-02). The value stays as the model's recommendation; only LIVE mode opens an escalation. */
    private void escalate(Eval ev, String reason) {
        ev.layer = ev.layer == Layer.L0_RULE ? Layer.L0_RULE : Layer.L3_HUMAN;
        ev.action = DecisionResult.ESCALATE;
        ev.requiresHuman = true;
        ev.details.put("escalationReason", reason);
        if (!ev.opts.mode().acts()) return;
        ev.escalationId = escalations.escalate(new EscalationRequest(ev.recordId, ev.def.name(), ev.def.version(), ev.def.tier(), ev.opts.subjectType(), ev.opts.subjectId(),
            ev.opts.correlationId(), ev.input, ev.state, ev.value, ev.answers, ev.confidence, ev.band, ev.reasonCodes, reason, ev.opts.actor()));
    }

    // ------------------------------------------------------------------ persistence

    private DecisionResult persist(Eval ev) {
        long latency = ev.latencyMs();
        DecisionResult result = new DecisionResult(ev.recordId, ev.def.name(), ev.def.version(), ev.layer, ev.band, ev.value, ev.answers, ev.confidence, ev.action, ev.requiresHuman,
            ev.escalationId, ev.rulesFired, ev.reasonCodes, latency, ev.costUsd, ev.provider, ev.modelVersion, ev.details);
        Map<String, Object> details = new LinkedHashMap<>(ev.details);
        details.put("preChecks", ev.preChecks);
        details.put("postChecks", ev.postChecks);
        details.put("mode", ev.opts.mode().name());
        // SOO propensity logging (OC-01): arms are every candidate the decision could have chosen (choice options,
        // ranked candidates, both answers of a yes/no, every score level), chosenArm the value actually applied (the
        // default when the decision handed off to a person or fell back, with the model's answer kept in details), and
        // propensity 1.0 because this path chooses deterministically. A learner that samples among arms (the FS-01
        // sourcing-policy bandit) logs its own record with the real draw probability instead.
        String chosenArm = appliedArm(ev.def, ev.value, ev.action, details);
        List<String> arms = armsOf(ev.def, ev.input, details, ev.value, chosenArm);
        // EX-01: under the exploration floor the applied arm's true probability (1 - share for the normal choice).
        double propensity = details.get("exploration") instanceof Map<?, ?> x && x.get("propensity") instanceof Number p ? p.doubleValue() : 1.0;
        boolean explored = details.get("exploration") instanceof Map<?, ?> x2 && Boolean.TRUE.equals(x2.get("explored"));
        DecisionRecord record = new DecisionRecord(ev.recordId, ev.def.name(), ev.def.version(), ev.opts.mode(), ev.opts.subjectType(), ev.opts.subjectId(), ev.opts.correlationId(),
            ev.layer, ev.band, ev.action, ev.value, ev.answers, ev.confidence, ev.provider, ev.modelVersion, ev.inputHash, ev.state, ev.input, ev.rulesFired, ev.reasonCodes, latency,
            ev.costUsd, ev.inputTokens, ev.requiresHuman, ev.escalationId, ev.opts.actor().ref(), details, null, null, null, null, Map.of(), ev.startedAt,
            contextFeatures(ev), arms, chosenArm, propensity, ev.def.version());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", ev.def.name());
        payload.put("version", ev.def.version());
        payload.put("mode", ev.opts.mode().name());
        payload.put("layer", ev.layer.name());
        payload.put("band", ev.band.name());
        payload.put("action", ev.action);
        payload.put("value", nullSafe(ev.value));
        payload.put("confidence", ev.confidence);
        payload.put("requiresHuman", ev.requiresHuman);
        payload.put("latencyMs", latency);
        payload.put("costUsd", ev.costUsd);
        payload.put("provider", ev.provider == null ? "" : ev.provider);
        payload.put("inputHash", ev.inputHash);
        if (ev.escalationId != null) payload.put("escalationId", ev.escalationId);
        if (!ev.rulesFired.isEmpty()) payload.put("rulesFired", ev.rulesFired);
        if (!ev.reasonCodes.isEmpty()) payload.put("reasonCodes", ev.reasonCodes);
        Map<String, Object> meta = subjectMeta(ev.opts.subjectType(), ev.opts.subjectId());
        tx.executeWithoutResult(s -> {
            records.insert(record);
            List<NewEvent> evs = new ArrayList<>();
            evs.add(new NewEvent("decision.evaluated", payload, ev.opts.actor(), null, ev.opts.correlationId(), meta));
            if (ev.postCheckFailed) evs.add(new NewEvent("decision.postcheck_failed", Map.of("decision", ev.def.name(), "version", ev.def.version(),
                "failed", ev.postChecks.stream().filter(c -> !c.passed()).map(CheckResult::expression).toList(), "discardedValue", nullSafe(ev.details.get("discardedValue"))),
                ev.opts.actor(), null, ev.opts.correlationId(), meta));
            events.append("decision", ev.recordId, 0, evs);
            var day = ev.startedAt.atZone(ZoneOffset.UTC).toLocalDate();
            ev.spendCalls.forEach((p, c) -> spend.add(day, ev.def.name(), p, c[0], ev.spendCost.get(p)[0], c[1]));
            if (ev.opts.mode() == Mode.LIVE && ev.def.cache() != null && ev.layer != Layer.DEFAULT && !ev.requiresHuman && !explored) {
                cache.put(ev.def.key(), ev.inputHash, result, ev.startedAt.plus(ev.def.cache().ttl()));
            }
        });
        metrics.count("oms.decision.evaluated", "decision", ev.def.name(), "layer", ev.layer.name(), "band", ev.band.name(), "mode", ev.opts.mode().name());
        metrics.record("oms.decision.latency", Duration.ofMillis(latency), "decision", ev.def.name());
        if (ev.costUsd > 0) metrics.count("oms.decision.cost", ev.costUsd, "decision", ev.def.name());
        return result;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * OC-01: every record logs the same typed context features, the tokenised state of its input: the state sent to the
     * provider when one was asked, else (an L0 rule, a cache hit, a kill switch or a failed pre-check) the state built
     * the same way from the same input, so a rule-decided record is as replayable and learnable as a model-decided one.
     */
    private Map<String, Object> contextFeatures(Eval ev) {
        if (ev.state != null && !ev.state.isEmpty()) return ev.state;
        try {
            Map<String, Object> built = stateBuilder.build(ev.def, ev.input == null ? Map.of() : ev.input);
            return built == null ? Map.of() : built;
        } catch (RuntimeException e) {
            log.debug("Context features for {} could not be built: {}", ev.def.key(), e.toString());
            return Map.of();
        }
    }

    /**
     * Every candidate a decision could have chosen (OC-01): ranked candidates (top-K decisions, by candidate id), the
     * main question's choice options, both answers of a yes/no question, or every rubric level of a score question.
     * The applied arm is always included.
     */
    static List<String> armsOf(DecisionDefinition def, Map<String, Object> input, Map<String, Object> details, Object value, String chosenArm) {
        List<String> arms = new ArrayList<>();
        if (def.topK() != null && input != null) {
            Object raw = Json.path(input, def.topK().candidatesField());
            if (raw instanceof List<?> l) for (int i = 0; i < l.size(); i++) arms.add(TopKStage.candidateId(l.get(i), i));
        }
        if (arms.isEmpty()) {
            Object mainKey = details.get("mainQuestion");
            QuestionDef q = mainKey == null ? def.main().orElse(null) : def.questions().get(String.valueOf(mainKey));
            if (q != null && q.isChoice()) arms.addAll(q.choiceCriteria().keySet());
            else if (q != null && q.isNoul()) arms.addAll(List.of("true", "false"));
            else if (q != null && q.isScore()) for (int i = 0; i < q.levels(); i++) arms.add(value instanceof Double ? String.valueOf((double) i) : String.valueOf(i));
        }
        if (arms.isEmpty() && value instanceof Boolean) arms.addAll(List.of("true", "false"));
        if (chosenArm != null && !arms.contains(chosenArm)) arms.add(chosenArm);
        return arms;
    }

    /**
     * The arm the system actually applied: the model's (or rule's) value when it acted, else the definition's default —
     * a T1 confirm-band fallback applies the default, and an escalation applies the default until a person answers.
     * When that differs from the model's answer, the answer is kept in {@code details.modelAnswer}.
     */
    static String appliedArm(DecisionDefinition def, Object value, String action, Map<String, Object> details) {
        boolean confirmFallback = details.containsKey("confirmFallback");
        boolean handedOff = DecisionResult.ESCALATE.equals(action);
        if ((confirmFallback || handedOff) && def.dflt() != null && !String.valueOf(def.dflt()).equals(String.valueOf(value))) {
            details.put("modelAnswer", nullSafe(value));
            details.put("chosenArmSource", handedOff ? "default_pending_human" : "confirm_default");
            return String.valueOf(def.dflt());
        }
        return value == null ? null : String.valueOf(value);
    }

    /**
     * A cache hit is a decision too (OC-01: every decision is logged): it gets its own LIVE record and
     * {@code decision.evaluated} event, pointing at the record whose answer it reused, at no model cost.
     */
    private DecisionResult persistCacheHit(Eval ev, DecisionResult c) {
        long latency = ev.latencyMs();
        Map<String, Object> details = new LinkedHashMap<>(c.details());
        details.putAll(ev.details);
        details.put("cached", true);
        details.put("cachedFrom", c.recordId());
        details.put("mode", ev.opts.mode().name());
        String chosenArm = appliedArm(ev.def, c.value(), c.action(), details);
        List<String> arms = armsOf(ev.def, ev.input, details, c.value(), chosenArm);
        DecisionRecord record = new DecisionRecord(ev.recordId, ev.def.name(), ev.def.version(), ev.opts.mode(), ev.opts.subjectType(), ev.opts.subjectId(), ev.opts.correlationId(),
            c.layer(), c.band(), c.action(), c.value(), c.answers(), c.confidence(), c.provider(), c.modelVersion(), ev.inputHash, Map.of(), ev.input, c.rulesFired(), c.reasonCodes(),
            latency, 0.0, 0, c.requiresHuman(), null, ev.opts.actor().ref(), details, null, null, null, null, Map.of(), ev.startedAt,
            contextFeatures(ev), arms, chosenArm, 1.0, ev.def.version());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", ev.def.name());
        payload.put("version", ev.def.version());
        payload.put("mode", ev.opts.mode().name());
        payload.put("layer", c.layer().name());
        payload.put("band", c.band().name());
        payload.put("action", c.action());
        payload.put("value", nullSafe(c.value()));
        payload.put("confidence", c.confidence());
        payload.put("requiresHuman", c.requiresHuman());
        payload.put("latencyMs", latency);
        payload.put("costUsd", 0.0);
        payload.put("provider", c.provider() == null ? "" : c.provider());
        payload.put("inputHash", ev.inputHash);
        payload.put("cachedFrom", c.recordId());
        Map<String, Object> meta = subjectMeta(ev.opts.subjectType(), ev.opts.subjectId());
        tx.executeWithoutResult(s -> {
            records.insert(record);
            events.append("decision", ev.recordId, 0, List.of(new NewEvent("decision.evaluated", payload, ev.opts.actor(), null, ev.opts.correlationId(), meta)));
        });
        metrics.count("oms.decision.evaluated", "decision", ev.def.name(), "layer", c.layer().name(), "band", c.band().name(), "mode", ev.opts.mode().name());
        return new DecisionResult(ev.recordId, c.decision(), c.version(), c.layer(), c.band(), c.value(), c.answers(), c.confidence(), c.action(), c.requiresHuman(),
            null, c.rulesFired(), c.reasonCodes(), latency, 0.0, c.provider(), c.modelVersion(), details);
    }

    private static Object mainValue(Answer main, QuestionDef q) {
        if (q != null && q.isNoul()) return main.asBoolean();
        return main.value();
    }

    /** Rule 'then' literals: booleans, numbers and JSON stay typed; everything else is a string value. */
    static Object coerce(String then) {
        if (then == null) return null;
        String t = then.trim();
        if (t.equals("true") || t.equals("false")) return Boolean.parseBoolean(t);
        if (t.matches("-?\\d+")) return Long.parseLong(t);
        if (t.matches("-?\\d*\\.\\d+")) return Double.parseDouble(t);
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) { try { return Json.read(t, Object.class); } catch (IllegalArgumentException e) { return then; } }
        return then;
    }

    private static Map<String, Object> subjectMeta(String subjectType, String subjectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (subjectType != null) m.put("subjectType", subjectType);
        if (subjectId != null) m.put("subjectId", subjectId);
        return m;
    }

    private static Object nullSafe(Object o) { return o == null ? "" : o; }
}
