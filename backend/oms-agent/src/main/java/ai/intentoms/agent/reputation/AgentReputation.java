package ai.intentoms.agent.reputation;

/**
 * FD-05's typed AgentReputation: an agent's track record from its real mandates, injection-screen hits and disputes,
 * with the score and the tier a reviewer might move it to. Nothing acts on it: a person reads it in the trust-tier
 * review and decides ({@code AgentRegistry} is changed only by an admin).
 *
 * @param complianceRate share of mandates with a valid (or no) signature that were not revoked
 * @param disputeRate    chargebacks on the agent's orders per mandate
 * @param scored         false below {@value #MIN_MANDATES} mandates, when the score is not meaningful yet
 * @param suggestedTier  one tier up from a score of 0.9 (to at most 3), one down below 0.5 (to at least 0); else the same
 */
public record AgentReputation(String agentId, int trustTier, int mandates, int compliantMandates, double complianceRate, long injectionHits, long disputes,
                              double disputeRate, double score, boolean scored, int suggestedTier) {
    public static final int MIN_MANDATES = 5;
    public static final double RAISE_AT = 0.9;
    public static final double LOWER_BELOW = 0.5;

    public static int suggestTier(int current, double score) {
        if (score >= RAISE_AT) return Math.min(3, current + 1);
        if (score < LOWER_BELOW) return Math.max(0, current - 1);
        return current;
    }

    /** Always true: the reputation never changes a tier by itself. */
    public boolean manualDecision() { return true; }
}
