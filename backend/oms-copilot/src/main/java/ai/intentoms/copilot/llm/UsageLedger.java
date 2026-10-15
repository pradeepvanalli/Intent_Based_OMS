package ai.intentoms.copilot.llm;

/** Where the copilot's token usage is recorded and read back for the daily budget (GR-17). */
public interface UsageLedger {
    void record(LlmUsage usage);

    /** Copilot spend so far today for the current tenant, in USD. */
    double usedTodayUsd();

    /** Number of copilot calls today for the current tenant. */
    long callsToday();
}
