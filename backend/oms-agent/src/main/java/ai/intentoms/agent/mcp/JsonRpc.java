package ai.intentoms.agent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-RPC 2.0 envelope helpers and error codes used by the MCP server (FR-AGT-01). */
public final class JsonRpc {
    public static final String VERSION = "2.0";
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32000;

    private JsonRpc() {}

    public static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", VERSION);
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    public static Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        if (data != null) err.put("data", data);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", VERSION);
        m.put("id", id);
        m.put("error", err);
        return m;
    }

    /** Thrown inside request handling; becomes a JSON-RPC error object. */
    public static final class RpcException extends RuntimeException {
        private final int code;
        private final Object data;
        public RpcException(int code, String message) { this(code, message, null); }
        public RpcException(int code, String message, Object data) { super(message); this.code = code; this.data = data; }
        public int code() { return code; }
        public Object data() { return data; }
    }
}
