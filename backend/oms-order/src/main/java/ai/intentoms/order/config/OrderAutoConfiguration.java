package ai.intentoms.order.config;

import ai.intentoms.order.service.CommunicationProvider;
import ai.intentoms.order.service.OrderRiskProvider;
import ai.intentoms.order.service.ReturnSummaryProvider;
import ai.intentoms.order.service.ShipmentProvider;
import ai.intentoms.order.tracking.TrackingNarrativeProvider;
import ai.intentoms.order.validation.AddressVerifier;
import ai.intentoms.order.validation.MockAddressVerifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Defaults for the order module's SPIs that downstream modules override by registering their own bean: the mock
 * {@link AddressVerifier}, and empty {@link ShipmentProvider} / {@link ReturnSummaryProvider} / {@link OrderRiskProvider} /
 * {@link CommunicationProvider} so the order view works before orchestration, returns and monitoring are wired in, and
 * the template-only {@link TrackingNarrativeProvider} the copilot module replaces when a model is configured.
 * An auto-configuration (processed after component scanning) so {@code @ConditionalOnMissingBean} sees user beans first.
 */
@AutoConfiguration
public class OrderAutoConfiguration {
    @Bean @ConditionalOnMissingBean(AddressVerifier.class)
    AddressVerifier mockAddressVerifier() { return new MockAddressVerifier(); }

    @Bean @ConditionalOnMissingBean(ShipmentProvider.class)
    ShipmentProvider noShipments() { return ShipmentProvider.none(); }

    @Bean @ConditionalOnMissingBean(ReturnSummaryProvider.class)
    ReturnSummaryProvider noReturns() { return ReturnSummaryProvider.none(); }

    @Bean @ConditionalOnMissingBean(OrderRiskProvider.class)
    OrderRiskProvider noRisk() { return OrderRiskProvider.none(); }

    @Bean @ConditionalOnMissingBean(CommunicationProvider.class)
    CommunicationProvider noCommunications() { return CommunicationProvider.none(); }

    @Bean @ConditionalOnMissingBean(TrackingNarrativeProvider.class)
    TrackingNarrativeProvider templateOnlyTrackingNarrative() { return TrackingNarrativeProvider.none(); }
}
