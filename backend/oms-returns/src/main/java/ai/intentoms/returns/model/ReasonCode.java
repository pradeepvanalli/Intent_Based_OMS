package ai.intentoms.returns.model;

/** A reason code produced by a Noul question with its probability (FR-RET-03, section 9 rule 6). */
public record ReasonCode(String code, double probability) {}
