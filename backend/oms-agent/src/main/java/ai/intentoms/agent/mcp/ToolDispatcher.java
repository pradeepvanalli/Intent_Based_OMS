package ai.intentoms.agent.mcp;

import java.util.List;
import java.util.Map;

/** What the MCP server needs from the tool layer: the catalogue and guarded execution. */
public interface ToolDispatcher {
    /** Tool descriptors for {@code tools/list}. */
    List<Map<String, Object>> tools(ToolContext ctx);

    /** Runs a tool with every guard applied; business failures come back as {@link Outcome#isError()}. */
    Outcome call(ToolContext ctx, String tool, Map<String, Object> args);

    record Outcome(Object structured, boolean isError) {}
}
