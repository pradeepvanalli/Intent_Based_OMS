package ai.intentoms.decision.model;

/** Layer that produced the final value (FR-DEC-02): L0 rules, L1 System One (Jev), L2 LLM, L3 human, or the L0 default. */
public enum Layer { L0_RULE, L1_SYSTEM_ONE, L2_LLM, L3_HUMAN, DEFAULT }
