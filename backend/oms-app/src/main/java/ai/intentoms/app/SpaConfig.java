package ai.intentoms.app;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the built React UI from classpath:/static and falls back to index.html for client-side routes
 * (everything that is not /api, /mcp, /ucp, /webhooks, /actuator, /v3 or a real asset).
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .setCachePeriod(3600)
            .resourceChain(true)
            .addResolver(new PathResourceResolver() {
                @Override
                protected Resource getResource(String resourcePath, Resource location) throws IOException {
                    Resource requested = location.createRelative(resourcePath);
                    if (requested.exists() && requested.isReadable()) return requested;
                    if (resourcePath.startsWith("api/") || resourcePath.startsWith("mcp") || resourcePath.startsWith("ucp/")
                        || resourcePath.startsWith("webhooks/") || resourcePath.startsWith("actuator") || resourcePath.startsWith("v3/")
                        || resourcePath.startsWith("swagger-ui")) return null;
                    Resource index = new ClassPathResource("/static/index.html");
                    return index.exists() ? index : null;
                }
            });
    }
}
