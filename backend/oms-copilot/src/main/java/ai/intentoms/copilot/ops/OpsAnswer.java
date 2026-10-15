package ai.intentoms.copilot.ops;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Answer to an ops question (FR-MON-08): the text, the template and parameters that ran, the SQL shown to the user, and the rows. */
public record OpsAnswer(String answer, String queryName, Map<String, Object> parameters, String sql, List<Map<String, Object>> rows, int rowCount, Instant generatedAt,
                        boolean generated, String model, String selectionReason) {}
