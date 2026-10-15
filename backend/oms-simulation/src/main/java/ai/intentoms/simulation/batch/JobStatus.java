package ai.intentoms.simulation.batch;

/** Lifecycle of a batch simulation job (FR-SIM-02; UI contract item 13). */
public enum JobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELLED; }
}
