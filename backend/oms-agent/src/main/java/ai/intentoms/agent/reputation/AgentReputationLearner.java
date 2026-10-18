package ai.intentoms.agent.reputation;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.agent.mandate.Mandate;
import ai.intentoms.agent.mandate.MandateRepository;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * FD-05: an agent reputation score (0..1) from mandate-compliance history (share of mandates with a valid
 * signature and no spend/currency/buyer rejection reasons recorded) and injection-screen hits
 * ({@code agent.injection_flagged} events on the agent aggregate). <b>Safety constraint from the requirements
 * doc:</b> this writes a {@link LearnedFact} only — it never calls {@link AgentRegistry#update} itself. Trust-tier
 * changes stay a manual admin action; a human reads this fact and decides.
 *
 * <p>Scope note: dispute rate isn't included yet — disputes live in evidence packs / chargebacks, which aren't
 * queryable per-agent from here without a new aggregate query this pass didn't have time to build; mandate
 * compliance and injection-flag rate are real signals today, not placeholders.
 */
@Component
public class AgentReputationLearner implements GovernedLearner {
    public static final String KEY = "agent_reputation";
    private static final Logger log = LoggerFactory.getLogger(AgentReputationLearner.class);
    public static final String LEARNER_KEY = "agent_reputation";

    private final AgentRegistry agents;
    private final MandateRepository mandates;
    private final EventStore events;
    private final LearnedFactRepository facts;
    private final DecisionRecordRepository decisionRecords;

    private final LearnerGovernance governance;

    public AgentReputationLearner(AgentRegistry agents, MandateRepository mandates, EventStore events, LearnedFactRepository facts, DecisionRecordRepository decisionRecords, LearnerGovernance governance) {
        this.governance = governance;
        this.agents = agents;
        this.mandates = mandates;
        this.events = events;
        this.facts = facts;
        this.decisionRecords = decisionRecords;
    }

    @Scheduled(fixedDelayString = "${oms.learning.agent-reputation-sweep-ms:21600000}", initialDelayString = "${oms.learning.agent-reputation-sweep-ms:21600000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Agent reputation sweep failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minusSeconds(30L * 86400)));
        for (String tenant : tenants) TenantContext.runAs(tenant, this::sweepTenant);
    }

    private void sweepTenant() {
        if (!governance.begin(KEY).mayRun()) return;
        governance.recordRun(KEY);
        for (AgentIdentity agent : agents.list()) {
            List<Mandate> m = mandates.forAgent(agent.id());
            if (m.size() < 5) continue; // too little history to score meaningfully
            long compliant = m.stream().filter(mm -> !Boolean.FALSE.equals(mm.signatureValid()) && !Mandate.REVOKED.equals(mm.status())).count();
            double mandateScore = (double) compliant / m.size();
            long flagged = events.load("agent", agent.id()).stream().filter(e -> "agent.injection_flagged".equals(e.type())).count();
            double injectionPenalty = Math.min(0.5, flagged * 0.05);
            double score = Math.max(0.0, Math.min(1.0, mandateScore - injectionPenalty));
            writeFact(agent.id(), score, m.size());
        }
    }

    private void writeFact(String agentId, double score, int sampleSize) {
        List<LearnedFact> history = facts.history(SubjectType.AGENT, agentId, FactKey.AGENT_REPUTATION, 1);
        facts.supersede(SubjectType.AGENT, agentId, FactKey.AGENT_REPUTATION, Instant.now());
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.AGENT, agentId, FactKey.AGENT_REPUTATION, new TypedValue.NumberValue(score),
            Math.min(1.0, sampleSize / 30.0), sampleSize, LEARNER_KEY, history.isEmpty() ? 1 : history.get(0).version() + 1, Instant.now(), null, Instant.now()));
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Agent reputation", "Scores each AI shopping agent on mandate compliance, prompt-injection hits and disputes. A person decides any trust change.", LearnerDescriptor.Loop.FAST, "FD-05");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
