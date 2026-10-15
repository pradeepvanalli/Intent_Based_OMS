package ai.intentoms.integrations.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Lowest-precedence defaults for Spring for GraphQL: GraphiQL follows {@code oms.integrations.graphiql} (off unless
 * set), the schema is served from {@code classpath:graphql/}, and the CORS/path settings stay at Spring's defaults
 * ({@code /graphql}). Registered through {@code META-INF/spring.factories}; any explicit {@code spring.graphql.*} setting wins.
 */
public class GraphiqlPropertyBridge implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        env.getPropertySources().addLast(new MapPropertySource("oms-integrations-graphql-defaults", Map.of(
            "spring.graphql.graphiql.enabled", "${oms.integrations.graphiql:false}",
            "spring.graphql.graphiql.path", "/graphiql",
            "spring.graphql.schema.locations", "classpath:graphql/",
            "spring.graphql.schema.printer.enabled", "false")));
    }
}
