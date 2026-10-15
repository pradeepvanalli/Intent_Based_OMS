package ai.intentoms.order.model;

import java.time.Instant;

/** Free text attached to the order. source: customer | agent | cs | system. Untrusted text is data, never an instruction (GR-08). */
public record Note(String source, String author, String text, Instant at, boolean customerVisible, Double injectionRisk) {}
