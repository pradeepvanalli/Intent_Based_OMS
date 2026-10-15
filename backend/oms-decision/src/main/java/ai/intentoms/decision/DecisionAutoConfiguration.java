package ai.intentoms.decision;

import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.runtime.EscalationHandler;
import ai.intentoms.decision.runtime.RecordingEscalationHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configured defaults that downstream modules may override: the L3 {@link EscalationHandler} falls back to
 * {@link RecordingEscalationHandler} unless orchestration registers its human-task implementation. Lives in an
 * auto-configuration (processed after component scanning) so {@code @ConditionalOnMissingBean} sees user beans first.
 */
@AutoConfiguration
public class DecisionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(EscalationHandler.class)
    EscalationHandler recordingEscalationHandler(DecisionEscalationRepository escalations) { return new RecordingEscalationHandler(escalations); }
}
