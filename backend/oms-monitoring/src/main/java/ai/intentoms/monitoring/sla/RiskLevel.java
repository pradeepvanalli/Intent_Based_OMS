package ai.intentoms.monitoring.sla;

/** Breach-risk level derived from the {@code sla_breach_risk} score 0-4 (FR-MON-02): >=3 critical, >=2 high, >=1 medium, else low. */
public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static RiskLevel of(double score) {
        if (score >= 3) return CRITICAL;
        if (score >= 2) return HIGH;
        if (score >= 1) return MEDIUM;
        return LOW;
    }

    /** Lower-case key used in the API and database ({@code low|medium|high|critical}). */
    public String key() { return name().toLowerCase(); }

    public boolean atRisk() { return this == HIGH || this == CRITICAL; }
}
