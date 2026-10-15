package ai.intentoms.copilot.llm;

import ai.intentoms.copilot.config.CopilotProperties;
import org.springframework.stereotype.Component;

/** The copilots call a model only when a key is configured; otherwise each feature runs its deterministic fallback. */
@Component
public class CopilotAvailability {
    private final CopilotProperties props;

    public CopilotAvailability(CopilotProperties props) { this.props = props; }

    public boolean available() { return props.hasKey(); }
    public String model() { return props.model(); }
    public String baseUrl() { return props.baseUrl(); }
}
