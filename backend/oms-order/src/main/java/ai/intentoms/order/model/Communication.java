package ai.intentoms.order.model;

import java.time.Instant;

/** Customer communication shown on the order view (FR-CS-01): emails, chat, voice summaries. */
public record Communication(String id, Instant at, String channel, String direction, String subject, String summary, String by) {}
