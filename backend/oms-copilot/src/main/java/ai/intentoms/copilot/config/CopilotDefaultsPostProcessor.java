package ai.intentoms.copilot.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Lowest-precedence defaults for {@code oms.copilot.*} so the module works without entries in the application's
 * configuration: the API key defaults to {@code ${OPENROUTER_API_KEY:${OPENAI_API_KEY:}}} and the templates directory to
 * {@code ${oms.config-dir:../../config}/copilot}. Any explicit property (application.yml, env, test properties) wins.
 */
public class CopilotDefaultsPostProcessor implements EnvironmentPostProcessor {
    public static final String SOURCE_NAME = "copilotDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) return;
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, Map.of(
            "oms.copilot.api-key", "${OPENROUTER_API_KEY:${OPENAI_API_KEY:}}",
            "oms.copilot.templates-dir", "${oms.config-dir:../../config}/copilot",
            "oms.copilot.starter-dir", "${oms.config-dir:../../config}/tenants/demo")));
    }
}
