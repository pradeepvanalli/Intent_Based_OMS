package ai.intentoms.decision.studio;

import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.DecisionDefinitionRepository.Row;
import ai.intentoms.decision.definition.DefinitionCache;
import ai.intentoms.decision.definition.DefinitionEditor;
import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.EvaluationSpec;
import ai.intentoms.decision.model.GoldenCase;
import ai.intentoms.decision.model.ImpactReport;
import ai.intentoms.decision.model.LintReport;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionRuntime;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.GuardrailViolationException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Policy and prompt studio (FR-PRM-01..08) with change governance (GR-10), the evaluation gate (GR-11) and separation
 * of duties (GR-14). Lifecycle: draft → (lint, evaluate, impact) → pending_approval → approved → shadow | canary | live
 * → retired; one-click rollback. Every mutation writes an audit row and a {@code decision.definition.*} ledger event
 * on aggregate {@code config / decision:<name>}.
 */
@Service
public class DecisionStudioService {
    public static final Duration RETRO_REVIEW_WINDOW = Duration.ofHours(48);
    public static final int IMPACT_LIMIT = 500;
    private static final Set<String> MUTABLE = Set.of("draft");

    private final DecisionDefinitionRepository repo;
    private final DecisionDefinitionParser parser;
    private final DecisionLinter linter;
    private final GoldenCaseRepository golden;
    private final DecisionRecordRepository records;
    private final DecisionRuntime runtime;
    private final DefinitionCache cache;
    private final TenantConfigStore config;
    private final EventStore events;
    private final AuditLog audit;
    private final AuthorizationService authz;
    private final TimeSource time;

    public DecisionStudioService(DecisionDefinitionRepository repo, DecisionDefinitionParser parser, DecisionLinter linter, GoldenCaseRepository golden, DecisionRecordRepository records,
                                 DecisionRuntime runtime, DefinitionCache cache, TenantConfigStore config, EventStore events, AuditLog audit, AuthorizationService authz, TimeSource time) {
        this.repo = repo;
        this.parser = parser;
        this.linter = linter;
        this.golden = golden;
        this.records = records;
        this.runtime = runtime;
        this.cache = cache;
        this.config = config;
        this.events = events;
        this.audit = audit;
        this.authz = authz;
        this.time = time;
    }

    // ------------------------------------------------------------------ reads

    public List<String> names() { return repo.names(); }

    public List<Row> versions(String name) {
        List<Row> v = repo.versions(name);
        if (v.isEmpty()) throw new NotFoundException("Decision " + name + " not found");
        return v;
    }

    public Row get(String name, int version) { return repo.find(name, version).orElseThrow(() -> new NotFoundException("Decision " + name + " version " + version + " not found")); }

    /** Summary of every decision: live/canary/shadow versions and tier (studio list page). */
    public List<Map<String, Object>> summaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : repo.names()) {
            List<Row> versions = repo.versions(name);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("tier", versions.get(0).definition().tier().name());
            s.put("owner", versions.get(0).definition().owner());
            s.put("latestVersion", versions.get(0).definition().version());
            versions.stream().filter(r -> r.definition().status().equals("live")).findFirst().ifPresent(r -> s.put("liveVersion", r.definition().version()));
            versions.stream().filter(r -> r.definition().status().equals("canary")).findFirst().ifPresent(r -> { s.put("canaryVersion", r.definition().version()); s.put("canaryPercent", r.definition().canaryPercent()); });
            s.put("shadowVersions", versions.stream().filter(r -> r.definition().status().equals("shadow")).map(r -> r.definition().version()).toList());
            s.put("drafts", versions.stream().filter(r -> MUTABLE.contains(r.definition().status()) || r.definition().status().equals("pending_approval") || r.definition().status().equals("approved")).map(r -> r.definition().version()).toList());
            s.put("killSwitch", config.getBoolean(ConfigKeys.DECISION_KILL_SWITCH_PREFIX + name, false));
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ drafts (FR-PRM-01, FR-PRM-02)

    /** Creates the next version as a draft from YAML. The YAML's {@code decision} must match {@code name} when given. */
    @Transactional
    public DecisionDefinition createDraft(String name, String yaml) {
        requireHuman();
        DecisionDefinition parsed = parser.parse(yaml);
        String n = name == null || name.isBlank() ? parsed.name() : name;
        if (!n.equals(parsed.name())) throw new ValidationException("YAML declares decision '" + parsed.name() + "' but the request targets '" + n + "'");
        int version = repo.nextVersion(n);
        String normalised = DefinitionEditor.apply(yaml, Map.of("version", version, "status", "draft", "canaryPercent", 0));
        DecisionDefinition def = parser.parse(normalised).withStatus("draft", 0, parsed.changeReason(), parsed.ticket(), time.now());
        repo.insert(def, authz.actorRef());
        record("decision.definition.drafted", n, version, "draft created", Map.of("tier", def.tier().name()));
        return get(n, version).definition();
    }

    /** Replaces the YAML of a draft in place. */
    @Transactional
    public DecisionDefinition updateDraft(String name, int version, String yaml) {
        requireHuman();
        Row row = get(name, version);
        requireMutable(row);
        DecisionDefinition parsed = parser.parse(yaml);
        if (!parsed.name().equals(name)) throw new ValidationException("YAML declares decision '" + parsed.name() + "' but the request targets '" + name + "'");
        String normalised = DefinitionEditor.apply(yaml, Map.of("version", version, "status", "draft"));
        DecisionDefinition def = parser.parse(normalised);
        repo.updateYaml(name, version, def);
        record("decision.definition.updated", name, version, "draft updated", Map.of());
        return get(name, version).definition();
    }

    /** Structured edits (FR-PRM-01): {questions.<k>.criteria.<opt>: text, bands.act: n, rules[i].then: v, weights.<q>: w, ...}. Thresholds only by humans (GR-14). */
    @Transactional
    public DecisionDefinition edit(String name, int version, Map<String, Object> edits) {
        requireHuman();
        Row row = get(name, version);
        requireMutable(row);
        if (edits == null || edits.isEmpty()) throw new ValidationException("No edits supplied");
        for (String k : edits.keySet()) if (k.equals("decision") || k.equals("version") || k.equals("status") || k.equals("canaryPercent")) throw new ValidationException("Field '" + k + "' is managed by the studio and cannot be edited directly");
        String yaml = DefinitionEditor.apply(row.definition().yaml(), edits);
        DecisionDefinition def = parser.parse(yaml);
        repo.updateYaml(name, version, def);
        record("decision.definition.edited", name, version, "structured edit", Map.of("fields", new ArrayList<>(edits.keySet())));
        return get(name, version).definition();
    }

    public LintReport lint(String yaml) { return linter.lint(yaml); }

    /** Lints a stored version and keeps the report on the row. */
    public LintReport lint(String name, int version) {
        Row row = get(name, version);
        LintReport report = linter.lint(row.definition().yaml());
        repo.setLintReport(name, version, report);
        return report;
    }

    // ------------------------------------------------------------------ evaluation gate (GR-11)

    public EvaluationReport evaluate(String name, int version) {
        Row row = get(name, version);
        DecisionDefinition def = row.definition();
        EvaluationSpec spec = def.evaluation();
        Instant now = time.now();
        EvaluationReport report;
        if (spec == null) {
            report = new EvaluationReport(name, version, null, 0, 0, 0, 0, 0, null, 0, List.of(), Map.of(), true, List.of("evaluation not configured; no gate applied"), List.of(), 0, now);
        } else {
            List<GoldenCase> cases = golden.forSet(spec.goldenSet());
            List<Calibration.Observation> obs = new ArrayList<>();
            List<EvaluationReport.CaseOutcome> failures = new ArrayList<>();
            Map<String, Integer> bandMix = new LinkedHashMap<>();
            for (Band b : Band.values()) bandMix.put(b.name(), 0);
            int correct = 0, actCases = 0, actCorrect = 0;
            double cost = 0;
            for (GoldenCase c : cases) {
                DecisionResult r = runtime.evaluate(def, c.input(), DecisionOptions.simulation("golden_case", spec.goldenSet() + ":" + c.caseId(), version));
                boolean ok = Calibration.valueEquals(c.expectedValue(), r.value());
                if (c.expected().get("band") != null && !String.valueOf(c.expected().get("band")).equalsIgnoreCase(r.band().name())) ok = false;
                obs.add(new Calibration.Observation(r.confidence(), ok));
                bandMix.merge(r.band().name(), 1, Integer::sum);
                cost += r.costUsd();
                if (ok) correct++; else failures.add(new EvaluationReport.CaseOutcome(c.caseId(), c.expectedValue(), r.value(), r.band(), r.confidence()));
                if (r.band() == Band.ACT) { actCases++; if (ok) actCorrect++; }
            }
            Calibration.Result cal = Calibration.compute(obs);
            double accuracy = cases.isEmpty() ? 0 : correct / (double) cases.size();
            Double actAcc = actCases == 0 ? null : actCorrect / (double) actCases;
            List<String> gate = new ArrayList<>();
            if (cases.isEmpty()) gate.add("golden set '" + spec.goldenSet() + "' has no cases");
            double effective = actAcc != null ? actAcc : accuracy;
            if (!cases.isEmpty() && effective < spec.minAccuracyInActBand()) gate.add(String.format(java.util.Locale.ROOT, "accuracy %.3f (%s) below minimum %.3f", effective, actAcc != null ? "act band" : "overall; no act-band cases", spec.minAccuracyInActBand()));
            if (!cases.isEmpty() && cal.error() > spec.maxCalibrationError()) gate.add(String.format(java.util.Locale.ROOT, "calibration error %.3f above maximum %.3f", cal.error(), spec.maxCalibrationError()));
            report = new EvaluationReport(name, version, spec.goldenSet(), cases.size(), correct, Calibration.round(accuracy), actCases, actCorrect, actAcc == null ? null : Calibration.round(actAcc),
                cal.error(), cal.bins(), bandMix, gate.isEmpty(), gate, failures.size() > 50 ? failures.subList(0, 50) : failures, cost, now);
        }
        repo.setEvaluationReport(name, version, report);
        record("decision.definition.evaluated", name, version, "golden set evaluation", Map.of("passed", report.passed(), "cases", report.cases(), "accuracy", report.accuracy(), "calibrationError", report.calibrationError()));
        return report;
    }

    // ------------------------------------------------------------------ impact diff (FR-PRM-03)

    public ImpactReport impactDiff(String name, int draftVersion, int days, int limit) {
        Row live = repo.byStatus(name, "live").stream().findFirst().orElseThrow(() -> new NotFoundException("Decision " + name + " has no live version to diff against"));
        DecisionDefinition draft = get(name, draftVersion).definition();
        int lim = Math.max(1, Math.min(limit <= 0 ? IMPACT_LIMIT : limit, IMPACT_LIMIT));
        Instant since = time.now().minus(Duration.ofDays(Math.max(1, days)));
        List<DecisionRecord> samples = records.recentLive(name, live.definition().version(), since, lim);
        Map<String, Integer> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        for (Band b : Band.values()) { before.put(b.name(), 0); after.put(b.name(), 0); }
        int flips = 0, escBefore = 0, escAfter = 0;
        double costBefore = 0, costAfter = 0, latBefore = 0, latAfter = 0;
        List<ImpactReport.Flip> sample = new ArrayList<>();
        for (DecisionRecord r : samples) {
            DecisionResult sim = runtime.evaluate(draft, r.input(), new DecisionOptions(r.subjectType(), r.subjectId(), r.correlationId(), Mode.SIMULATION, draftVersion, Actor.system("impact-diff"), Map.of()));
            before.merge(r.band().name(), 1, Integer::sum);
            after.merge(sim.band().name(), 1, Integer::sum);
            if (r.requiresHuman() || r.band() == Band.ESCALATE) escBefore++;
            if (sim.requiresHuman() || sim.band() == Band.ESCALATE) escAfter++;
            costBefore += r.costUsd(); costAfter += sim.costUsd();
            latBefore += r.latencyMs(); latAfter += sim.latencyMs();
            if (!Calibration.valueEquals(r.value(), sim.value())) {
                flips++;
                if (sample.size() < 25) sample.add(new ImpactReport.Flip(r.id(), sim.recordId(), r.value(), sim.value(), r.band(), sim.band()));
            }
        }
        int n = samples.size();
        ImpactReport report = new ImpactReport(name, live.definition().version(), draftVersion, days, n, flips, n == 0 ? 0 : Calibration.round(flips / (double) n), before, after, escBefore, escAfter,
            costBefore, costAfter, n == 0 ? 0 : latBefore / n, n == 0 ? 0 : latAfter / n, sample, textDiff(live.definition().yaml(), draft.yaml()));
        record("decision.definition.impact_diffed", name, draftVersion, "impact diff", Map.of("liveVersion", live.definition().version(), "sampled", n, "flips", flips));
        return report;
    }

    static List<String> textDiff(String a, String b) {
        Set<String> la = new LinkedHashSet<>(List.of(a.split("\n"))), lb = new LinkedHashSet<>(List.of(b.split("\n")));
        List<String> out = new ArrayList<>();
        for (String l : la) if (!lb.contains(l) && !l.isBlank()) out.add("- " + l);
        for (String l : lb) if (!la.contains(l) && !l.isBlank()) out.add("+ " + l);
        return out;
    }

    // ------------------------------------------------------------------ governance (GR-10, GR-14)

    @Transactional
    public Row submitForApproval(String name, int version, String reason, String ticket) {
        requireHuman();
        if (reason == null || reason.isBlank()) throw new ValidationException("A change reason is mandatory (FR-PRM-06)");
        if (ticket == null || ticket.isBlank()) throw new ValidationException("A linked ticket is mandatory (FR-PRM-06)");
        Row row = get(name, version);
        requireMutable(row);
        LintReport lint = lint(name, version);
        if (!lint.ok()) throw new GuardrailViolationException("Lint errors must be fixed before submission", Map.of("issues", lint.issues()));
        repo.setSubmitted(name, version, authz.actorRef(), reason, ticket);
        record("decision.definition.submitted", name, version, reason, Map.of("ticket", ticket));
        return get(name, version);
    }

    @Transactional
    public Row approve(String name, int version) {
        requireHuman();
        Row row = get(name, version);
        String status = row.definition().status();
        if (!status.equals("pending_approval") && !status.equals("approved")) throw new ConflictException("Version " + version + " is " + status + ", not awaiting approval");
        String actor = authz.actorRef();
        if (actor.equals(row.submittedBy())) throw new ForbiddenException("The submitter cannot approve their own change (GR-14)");
        if (row.approvals().stream().anyMatch(a -> actor.equals(a.get("actor")))) throw new ConflictException("Already approved by " + actor);
        List<Map<String, Object>> approvals = new ArrayList<>(row.approvals());
        approvals.add(Map.of("actor", actor, "at", time.now().toString()));
        int required = row.definition().tier().requiredApprovers();
        String next = approvals.size() >= required ? "approved" : "pending_approval";
        repo.setApprovals(name, version, approvals, next);
        record("decision.definition.approved", name, version, "approval " + approvals.size() + "/" + required, Map.of("approvals", approvals.size(), "required", required, "complete", next.equals("approved")));
        return get(name, version);
    }

    /**
     * Publishes an approved version: rollout "shadow", "canary:<pct>" or "live" (FR-PRM-04). Requires completed
     * approvals and a passing evaluation (GR-11). {@code emergency} skips both for config.admin holders and schedules a
     * 48 h retro review (GR-10).
     */
    @Transactional
    public Row publish(String name, int version, String rollout, boolean emergency) {
        requireHuman();
        Row row = get(name, version);
        DecisionDefinition def = row.definition();
        String status = def.status();
        if (status.equals("retired")) throw new ConflictException("Retired versions cannot be published; create a new draft or roll back");
        Rollout r = Rollout.parse(rollout);
        Instant retro = null;
        if (emergency) {
            authz.require(Permissions.CONFIG_ADMIN);
            retro = time.now().plus(RETRO_REVIEW_WINDOW);
        } else {
            if (!status.equals("approved") && !(status.equals("shadow") || status.equals("canary"))) throw new GuardrailViolationException("Version " + version + " needs completed approvals before publishing (status " + status + ")");
            if (row.approvals().size() < def.tier().requiredApprovers()) throw new GuardrailViolationException("Tier " + def.tier() + " requires " + def.tier().requiredApprovers() + " approvers");
            if (def.evaluation() != null) {
                if (row.evaluationReport() == null) throw new GuardrailViolationException("Evaluation against golden set '" + def.evaluation().goldenSet() + "' has not been run (GR-11)");
                if (!Boolean.TRUE.equals(row.evaluationReport().get("passed"))) throw new GuardrailViolationException("Evaluation gate failed (GR-11)", Map.of("gateFailures", row.evaluationReport().getOrDefault("gateFailures", List.of())));
            }
            LintReport lint = lint(name, version);
            if (!lint.ok()) throw new GuardrailViolationException("Lint errors block publishing", Map.of("issues", lint.issues()));
        }
        switch (r.mode()) {
            case "shadow" -> repo.setStatus(name, version, "shadow", 0);
            case "canary" -> {
                for (Row other : repo.byStatus(name, "canary")) if (other.definition().version() != version) repo.setStatus(name, other.definition().version(), "retired", null);
                repo.setStatus(name, version, "canary", r.percent());
            }
            default -> {
                for (Row other : repo.byStatus(name, "live", "canary", "shadow")) if (other.definition().version() != version) repo.setStatus(name, other.definition().version(), "retired", null);
                repo.setStatus(name, version, "live", 0);
            }
        }
        Map<String, Object> ro = new LinkedHashMap<>();
        ro.put("mode", r.mode());
        ro.put("percent", r.percent());
        ro.put("at", time.now().toString());
        ro.put("by", authz.actorRef());
        ro.put("emergency", emergency);
        repo.setRollout(name, version, ro, retro);
        cache.invalidate(name);
        String reason = def.changeReason() == null ? (emergency ? "emergency publish" : "publish") : def.changeReason();
        record(emergency ? "decision.definition.emergency_published" : "decision.definition.published", name, version, reason,
            Map.of("rollout", rollout, "ticket", def.ticket() == null ? "" : def.ticket(), "retroReviewDueAt", retro == null ? "" : retro.toString()));
        return get(name, version);
    }

    /** One-click rollback (FR-PRM-04): the most recently retired version becomes live again; the current live is retired. */
    @Transactional
    public Row rollback(String name) {
        requireHuman();
        Row previous = repo.lastRetired(name).orElseThrow(() -> new ConflictException("Decision " + name + " has no retired version to roll back to"));
        for (Row live : repo.byStatus(name, "live")) repo.setStatus(name, live.definition().version(), "retired", null);
        repo.setStatus(name, previous.definition().version(), "live", 0);
        repo.setRollout(name, previous.definition().version(), Map.of("mode", "live", "percent", 0, "at", time.now().toString(), "by", authz.actorRef(), "rollback", true), null);
        cache.invalidate(name);
        record("decision.definition.rolled_back", name, previous.definition().version(), "rollback", Map.of());
        return get(name, previous.definition().version());
    }

    /** GR-06: per-decision kill switch; the runtime returns the L0 default while it is on. */
    public void killSwitch(String name, boolean on) {
        requireHuman();
        config.set(ConfigKeys.DECISION_KILL_SWITCH_PREFIX + name, on);
        cache.invalidate(name);
        audit.record("decision.kill_switch", "decision", name, on ? "kill switch on" : "kill switch off", Map.of("on", on));
        events.append("config", "decision:" + name, new NewEvent("decision.definition.kill_switch", Map.of("decision", name, "on", on), Actor.parse(authz.actorRef())));
    }

    // ------------------------------------------------------------------ helpers

    record Rollout(String mode, int percent) {
        static Rollout parse(String s) {
            if (s == null || s.isBlank()) throw new ValidationException("rollout is required: shadow | canary:<pct> | live");
            String t = s.trim().toLowerCase();
            if (t.equals("shadow") || t.equals("live")) return new Rollout(t, 0);
            if (t.startsWith("canary")) {
                int pct = 10;
                int c = t.indexOf(':');
                if (c >= 0) { try { pct = Integer.parseInt(t.substring(c + 1).trim()); } catch (NumberFormatException e) { throw new ValidationException("canary percentage must be a number"); } }
                if (pct < 1 || pct > 99) throw new ValidationException("canary percentage must be 1..99");
                return new Rollout("canary", pct);
            }
            throw new ValidationException("Unknown rollout '" + s + "'; use shadow | canary:<pct> | live");
        }
    }

    private void requireMutable(Row row) {
        if (!MUTABLE.contains(row.definition().status())) throw new ConflictException("Version " + row.definition().version() + " is " + row.definition().status() + " and can no longer be edited; create a new draft");
    }

    /** GR-14: thresholds, limits and publishes are changed by people, never by models or agents. */
    private void requireHuman() {
        Principal p = authz.requirePrincipal();
        if (p.type() == Principal.Type.DECISION || p.type() == Principal.Type.AGENT) throw new ForbiddenException("Only human users may change decision definitions (GR-14)");
    }

    private void record(String type, String name, int version, String reason, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("decision", name);
        payload.put("version", version);
        payload.put("reason", reason);
        Actor actor = Actor.parse(authz.actorRef());
        events.append("config", "decision:" + name, new NewEvent(type, payload, actor));
        audit.record(type, "decision", name + "@" + version, reason, details);
    }

}
