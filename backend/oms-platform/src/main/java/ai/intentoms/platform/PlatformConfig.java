package ai.intentoms.platform;

import ai.intentoms.platform.ledger.OutboxRelay;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({OutboxRelay.OutboxProperties.class})
public class PlatformConfig {

    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    }

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info().title("Intent OMS API").version("v1").description("Configurable, event-sourced, decision-first order management"))
            .addSecurityItem(new SecurityRequirement().addList("bearer").addList("apiKey"))
            .schemaRequirement("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
            .schemaRequirement("apiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Api-Key"));
    }

    /** CORS from {@code oms.security.cors.*}: {@code *} (dev default) allows any origin; production must list explicit origins. */
    @Bean
    WebMvcConfigurer cors(ai.intentoms.platform.security.SecurityProperties security) {
        var cors = security.cors();
        return new WebMvcConfigurer() {
            @Override public void addCorsMappings(CorsRegistry r) {
                var m = r.addMapping("/**").allowedMethods("*").allowedHeaders("*").exposedHeaders("Location", "X-Request-Id").maxAge(3600);
                if (cors.allowsAny()) m.allowedOriginPatterns("*");
                else m.allowedOrigins(cors.allowedOrigins().toArray(String[]::new)).allowCredentials(cors.allowCredentials());
            }
        };
    }
}
