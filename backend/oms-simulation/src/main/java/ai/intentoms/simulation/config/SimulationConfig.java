package ai.intentoms.simulation.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** Wires {@link SimulationProperties} (ARCHITECTURE §2 conventions) and the multipart limits Margin Replay uploads need. */
@Configuration
@EnableConfigurationProperties(SimulationProperties.class)
public class SimulationConfig {
    /** 10 MB CSV uploads for {@code POST /api/v1/simulations/import} (Spring Boot's default is 1 MB); unused when the app defines its own. */
    @Bean
    @ConditionalOnMissingBean(MultipartConfigElement.class)
    @ConditionalOnClass(name = "jakarta.servlet.MultipartConfigElement")
    public MultipartConfigElement marginReplayMultipartConfig() {
        MultipartConfigFactory f = new MultipartConfigFactory();
        f.setMaxFileSize(DataSize.ofMegabytes(10));
        f.setMaxRequestSize(DataSize.ofMegabytes(12));
        return f.createMultipartConfig();
    }
}
