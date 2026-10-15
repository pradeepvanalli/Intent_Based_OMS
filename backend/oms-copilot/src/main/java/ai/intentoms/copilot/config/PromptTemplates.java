package ai.intentoms.copilot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt templates and artefact format descriptions (FR-CFG-01). The canonical files live in {@code config/copilot/}
 * and are read from {@code oms.copilot.templates-dir} at startup so operators can tune prompts without a rebuild; the
 * same files are packaged into the jar under {@code copilot/} as a fallback for deployments without the config mount.
 */
@Component
public class PromptTemplates {
    private static final Logger log = LoggerFactory.getLogger(PromptTemplates.class);
    public static final List<String> REQUIRED = List.of(
        "system-config.md", "system-repair.md", "system-explain-change.md", "system-bootstrap.md", "system-ops.md", "system-ops-answer.md",
        "system-explain.md", "system-cs.md", "system-digest.md", "cel.md", "format-decision.md", "format-state-model.md", "format-order-type.md",
        "format-sla.md", "format-playbook.md", "format-compliance.md", "format-tenant-config.md", "starter-blueprint.yaml");

    private final Map<String, String> templates = new LinkedHashMap<>();
    private final String source;

    public PromptTemplates(CopilotProperties props) {
        Path dir = Path.of(props.templatesDir()).toAbsolutePath().normalize();
        boolean fromDir = Files.isDirectory(dir);
        for (String name : REQUIRED) {
            String text = null;
            if (fromDir && Files.isRegularFile(dir.resolve(name))) {
                try { text = Files.readString(dir.resolve(name)); } catch (IOException e) { throw new IllegalStateException("Cannot read prompt template " + dir.resolve(name), e); }
            }
            if (text == null) {
                ClassPathResource res = new ClassPathResource("copilot/" + name);
                if (!res.exists()) throw new IllegalStateException("Prompt template " + name + " missing from " + dir + " and from the classpath (copilot/" + name + ")");
                try { text = res.getContentAsString(StandardCharsets.UTF_8); } catch (IOException e) { throw new IllegalStateException("Cannot read classpath prompt template " + name, e); }
            }
            templates.put(name, text);
        }
        this.source = fromDir ? dir.toString() : "classpath:copilot/";
        log.info("Loaded {} copilot prompt templates from {}", templates.size(), source);
    }

    public String get(String name) {
        String t = templates.get(name);
        if (t == null) throw new IllegalArgumentException("Unknown prompt template " + name);
        return t;
    }

    /** Every artefact format description plus the CEL reference, concatenated for the configuration prompts. */
    public String formats() {
        return String.join("\n\n", get("format-decision.md"), get("format-state-model.md"), get("format-order-type.md"), get("format-sla.md"),
            get("format-playbook.md"), get("format-compliance.md"), get("format-tenant-config.md"), get("cel.md"));
    }

    public String source() { return source; }
}
