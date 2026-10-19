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
 * <p>Dispute rate: every chargeback on an order the agent placed under a mandate appends
 * {@code agent.dispute_recorded} to the agent (finance's {@code ChargebackService}); disputes per mandate are
 * published as a {@code DISPUTE_RATE} fact and lower the score by twice the rate, at most 0.5.
 *
 * <p>The typed result is an {@link AgentReputation} with every component and a suggested tier, served to the manual
 * trust-tier review ({@code GET /api/v1/agents/reputation}). It feeds no decision and no live parameter family: the
 * requirement says the manual tier change only.
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
            AgentReputation r = reputation(agent);
            if (!r.scored()) continue; // too little history to score meaningfully
            writeFact(agent.id(), FactKey.DISPUTE_RATE, r.disputeRate(), r.mandates());
            writeFact(agent.id(), FactKey.AGENT_REPUTATION, r.score(), r.mandates());
        }
    }

    /** The agent's reputation from its real mandates, injection-screen hits and disputes (scored from 5 mandates). */
    public AgentReputation reputation(AgentIdentity agent) {
        List<Mandate> m = mandates.forAgent(agent.id());
        long compliant = m.stream().filter(mm -> !Boolean.FALSE.equals(mm.signatureValid()) && !Mandate.REVOKED.equals(mm.status())).count();
        var agentEvents = events.load("agent", agent.id());
        long flagged = agentEvents.stream().filter(e -> "agent.injection_flagged".equals(e.type())).count();
        long disputes = agentEvents.stream().filter(e -> "agent.dispute_recorded".equals(e.type())).count();
        boolean scored = m.size() >= AgentReputation.MIN_MANDATES;
        double compliance = m.isEmpty() ? 0 : (double) compliant / m.size();
        double rate = disputeRate(disputes, m.size());
        double score = scored ? score(compliance, Math.min(0.5, flagged * 0.05), rate) : 0;
        return new AgentReputation(agent.id(), agent.trustTier(), m.size(), (int) compliant, round(compliance), flagged, disputes, round(rate), round(score), scored,
            scored ? AgentReputation.suggestTier(agent.trustTier(), score) : agent.trustTier());
    }

    public List<AgentReputation> reputations() {
        return agents.list().stream().map(this::reputation).toList();
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

    /** FD-05: chargebacks on the agent's orders per mandate it placed orders under. */
    static double disputeRate(long disputes, int mandates) { return mandates == 0 ? 0 : Math.min(1.0, (double) disputes / mandates); }

    /** Mandate compliance, minus up to 0.5 for injection hits, minus twice the dispute rate (capped at 0.5). */
    static double score(double mandateScore, double injectionPenalty, double disputeRate) {
        return Math.max(0.0, Math.min(1.0, mandateScore - injectionPenalty - Math.min(0.5, 2 * disputeRate)));
    }

    private void writeFact(String agentId, FactKey key, double value, int sampleSize) {
        List<LearnedFact> history = facts.history(SubjectType.AGENT, agentId, key, 1);
        facts.supersede(SubjectType.AGENT, agentId, key, Instant.now());
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.AGENT, agentId, key, new TypedValue.NumberValue(value),
            Math.min(1.0, sampleSize / 30.0), sampleSize, LEARNER_KEY, history.isEmpty() ? 1 : history.get(0).version() + 1, Instant.now(), null, Instant.now()));
    }

    @Override
    public LearnerDescriptor descriptor() {
        // No feeds(): the reputation goes to the manual trust-tier review only, never to a decision or live parameter.
        return LearnerDescriptor.of(KEY, "Agent reputation", "Scores each AI shopping agent on mandate compliance, prompt-injection hits and disputes. A person decides any trust change.", LearnerDescriptor.Loop.FAST, "FD-05");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
