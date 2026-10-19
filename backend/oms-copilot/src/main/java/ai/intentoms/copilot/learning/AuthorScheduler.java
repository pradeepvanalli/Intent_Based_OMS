package ai.intentoms.copilot.learning;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DF-04 auto-drafting: every night (default 03:00) the Author drafts proposals for open hypotheses, oldest first and at
 * most {@code oms.learning.author.max-per-run} per tenant: override patterns people keep resolving the same way become a
 * reviewable L0 rule, and analyst hypotheses become changesets. Golden-case candidates are left for reviewer sign-off
 * (DF-03). Each draft still goes through evaluation, critic review and human approval.
 */
@Component
public class AuthorScheduler {
    private static final Logger log = LoggerFactory.getLogger(AuthorScheduler.class);
    static final Set<String> AUTHORED_ACTIONS = Set.of("add_rule", "retire_rule", "allow_list_change");

    private final AuthorService author;
    private final HypothesisRepository hypotheses;
    private final DecisionRecordRepository decisionRecords;

    @Value("${oms.learning.author.max-per-run:5}") private int maxPerRun;

    public AuthorScheduler(AuthorService author, HypothesisRepository hypotheses, DecisionRecordRepository decisionRecords) {
        this.author = author;
        this.hypotheses = hypotheses;
        this.decisionRecords = decisionRecords;
    }

    @Scheduled(cron = "${oms.learning.author-cron:0 0 3 * * *}")
    public void scheduled() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(90))));
        for (String tenant : tenants) {
            try { TenantContext.runAs(tenant, this::runOnce); }
            catch (RuntimeException e) { log.error("Author run failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    /** One pass for the current tenant; returns the proposals drafted. */
    public List<Proposal> runOnce() {
        List<Proposal> drafted = new ArrayList<>();
        List<Hypothesis> open = new ArrayList<>(hypotheses.byStatus("OPEN", 200));
        open.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        for (Hypothesis h : open) {
            if (drafted.size() >= maxPerRun) break;
            if (!authorable(h)) continue;
            try { author.draft(h.hypothesisId()).ifPresent(drafted::add); }
            catch (RuntimeException e) { log.warn("Author draft for {} failed: {}", h.hypothesisId(), e.toString()); }
        }
        if (!drafted.isEmpty()) log.info("DF-04 author run drafted {} proposals", drafted.size());
        return drafted;
    }

    static boolean authorable(Hypothesis h) {
        if (TemplateAuthor.allowList(h)) return true;
        Object action = h.lever().get("action");
        if (action != null) return AUTHORED_ACTIONS.contains(String.valueOf(action));
        return AuthorService.leverTarget(h) != null;
    }
}
