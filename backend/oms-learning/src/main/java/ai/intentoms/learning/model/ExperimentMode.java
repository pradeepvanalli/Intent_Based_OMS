package ai.intentoms.learning.model;

/** EX-02..EX-04: shadow runs in parallel without acting; canary takes a sticky traffic share; holdout is a
 * canary with no end date, kept at the tenant's persistent holdout share to measure cumulative lift. */
public enum ExperimentMode { SHADOW, CANARY, HOLDOUT }
