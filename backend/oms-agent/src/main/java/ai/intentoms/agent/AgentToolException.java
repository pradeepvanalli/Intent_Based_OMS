package ai.intentoms.agent;

import java.util.Map;

/**
 * Agent-facing refusal (FR-AGT-04): carries the JSON-RPC error code the MCP server returns and the reason the UCP layer
 * maps to an HTTP status. Codes: -32001 {@code limit_exceeded}, -32003 {@code tool_not_allowed} / {@code permission_denied},
 * -32004 {@code not_owner}, -32005 {@code mandate_invalid}, -32006 {@code confirmation_required}, -32007 {@code forbidden}.
 */
public class AgentToolException extends RuntimeException {
    public static final int LIMIT_EXCEEDED = -32001;
    public static final int NOT_FOUND = -32002;
    public static final int TOOL_NOT_ALLOWED = -32003;
    public static final int NOT_OWNER = -32004;
    public static final int MANDATE_INVALID = -32005;
    public static final int CONFLICT = -32006;
    public static final int FORBIDDEN = -32007;
    public static final int VALIDATION_FAILED = -32009;

    private final int code;
    private final String reason;
    private final Map<String, Object> data;

    public AgentToolException(int code, String reason, String message) { this(code, reason, message, Map.of()); }

    public AgentToolException(int code, String reason, String message, Map<String, Object> data) {
        super(message);
        this.code = code;
        this.reason = reason;
        this.data = data == null ? Map.of() : data;
    }

    public int code() { return code; }
    public String reason() { return reason; }
    public Map<String, Object> data() { return data; }
}
