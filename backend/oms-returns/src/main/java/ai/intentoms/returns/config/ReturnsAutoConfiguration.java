package ai.intentoms.returns.config;

import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.returns.service.DefaultStoreCreditIssuer;
import ai.intentoms.returns.service.StoreCreditIssuer;
import ai.intentoms.returns.store.StoreCreditRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Defaults downstream modules may override: the {@link StoreCreditIssuer} SPI falls back to the table-backed
 * {@link DefaultStoreCreditIssuer} unless an integration module declares its own bean. Lives in an auto-configuration
 * (processed after component scanning) so {@code @ConditionalOnMissingBean} sees user beans first.
 */
@AutoConfiguration
public class ReturnsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(StoreCreditIssuer.class)
    StoreCreditIssuer defaultStoreCreditIssuer(StoreCreditRepository repo, EventStore events, TimeSource time, Metrics metrics) {
        return new DefaultStoreCreditIssuer(repo, events, time, metrics);
    }
}
