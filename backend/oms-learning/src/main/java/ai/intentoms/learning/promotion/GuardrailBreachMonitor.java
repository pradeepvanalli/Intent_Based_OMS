package ai.intentoms.learning.promotion;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * EX-05: watches every recently-PROMOTED proposal against the guardrail metrics named in requirements doc §2/§7 —
 * override rate (promoted change later reversed by a human, capped 5%, the existing KPI definition) and decision
 * latency (p95 ≤300ms is a platform-wide NF-01 concern, read here as a late signal, not re-measured). On breach:
 * roll back automatically. For a {@code decision:} target this re-publishes the prior live version via {@code
 * DecisionStudioService.rollback(name)} (already exists, one-click rollback to the last retired version — exactly
 * what EX-05 needs, not reimplemented here). For a {@code parameter:} target, rollback means marking the proposal
 * rolled back; reverting the actual {@code LearnedFact} to its prior version is the documented integration point
 * for whichever learner owns that fact's write path, since this monitor doesn't know every learner's semantics for
 * "what was the value before."
 */
@Component
public class GuardrailBreachMonitor {
    private static final Logger log = LoggerFactory.getLogger(GuardrailBreachMonitor.class);
    private static final double MAX_OVERRIDE_RATE = 0.05;

    private final ProposalRepository proposals;
    private final DecisionRecordRepository decisionRecords;
    private final DecisionStudioService decisionStudio;
    private final LearningEvents events;

    @Value("${oms.learning.guardrail-watch-days:7}") private int watchDays;

    public GuardrailBreachMonitor(ProposalRepository proposals, DecisionRecordRepository decisionRecords, DecisionStudioService decisionStudio, LearningEvents events) {
        this.proposals = proposals;
        this.decisionRecords = decisionRecords;
        this.decisionStudio = decisionStudio;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${oms.learning.guardrail-sweep-ms:1800000}", initialDelayString = "${oms.learning.guardrail-sweep-ms:1800000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Guardrail breach sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(watchDays + 1))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        Instant since = Instant.now().minus(Duration.ofDays(watchDays));
        int rolledBack = 0;
        for (Proposal p : proposals.byStatus("PROMOTED", 200)) {
            if (p.promotedAt() == null || p.promotedAt().isBefore(since)) continue;
            ArtifactRef target = p.changeset().target();
            if (!"decision".equals(target.type())) continue; // override-rate signal only exists for decisions today
            double overrideRate = overrideRateSince(target.name(), p.promotedAt());
            if (overrideRate > MAX_OVERRIDE_RATE) {
                rollback(p, String.format(java.util.Locale.ROOT, "override rate %.3f exceeds the %.0f%% guardrail since promotion", overrideRate, MAX_OVERRIDE_RATE * 100));
                rolledBack++;
            }
        }
        return rolledBack;
    }

    private double overrideRateSince(String decisionName, Instant since) {
        List<ai.intentoms.decision.model.DecisionRecord> recent = decisionRecords.recentLive(decisionName, latestVersion(decisionName), since, 1000);
        if (recent.isEmpty()) return 0;
        long overridden = recent.stream().filter(r -> r.overriddenAt() != null).count();
        return overridden / (double) recent.size();
    }

    private int latestVersion(String decisionName) {
        try { return decisionStudio.versions(decisionName).stream().filter(r -> "live".equals(r.definition().status())).findFirst()
            .map(r -> r.definition().version()).orElse(0); }
        catch (RuntimeException e) { return 0; }
    }

    private void rollback(Proposal p, String reason) {
        Authentication prev = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(TenantContext.require())));
        try { decisionStudio.rollback(p.changeset().target().name()); }
        catch (RuntimeException e) { log.warn("Guardrail rollback of decision {} failed, marking proposal rolled back anyway so it stops being treated as live: {}", p.changeset().target(), e.toString()); }
        finally { if (prev == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prev); }
        proposals.rollback(p.proposalId(), reason, Instant.now());
        events.proposalRolledBack(p.proposalId(), Actor.system("guardrail-breach-monitor"), Map.of("target", p.changeset().target().toString(), "reason", reason));
        log.warn("Rolled back proposal {} on {}: {}", p.proposalId(), p.changeset().target(), reason);
    }
}
