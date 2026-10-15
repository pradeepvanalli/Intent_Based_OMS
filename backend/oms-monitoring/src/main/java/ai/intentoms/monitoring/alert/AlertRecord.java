package ai.intentoms.monitoring.alert;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Stored alert episode (FR-MON-06, table {@code alert}): first/last seen, repeat count and the channels that received it. */
public record AlertRecord(String id, String key, String severity, String title, String body, Map<String, String> links, Instant firstSeen, Instant lastSeen, int count, List<String> channelsSent) {}
