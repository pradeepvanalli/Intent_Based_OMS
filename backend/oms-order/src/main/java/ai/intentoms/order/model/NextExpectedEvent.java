package ai.intentoms.order.model;

import java.time.Instant;

/** What the workflow is waiting for and by when (FR-MON-05 stuck detection input, UI "next expected event"). */
public record NextExpectedEvent(String name, Instant expectedBy) {}
