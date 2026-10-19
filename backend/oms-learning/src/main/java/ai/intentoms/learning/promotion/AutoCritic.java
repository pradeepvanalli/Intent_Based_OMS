package ai.intentoms.learning.promotion;

import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * XP-02: every evaluation is followed by a critic review, whoever evaluated it (the replay service, a learner that
 * brings its own replay, a re-test), so the change card always carries one before a person decides. Runs after the
 * evaluation is committed; a failing critic never undoes or blocks the evaluation. Off with
 * {@code oms.learning.critic.auto=false}.
 */
@Component
public class AutoCritic {
    private static final Logger log = LoggerFactory.getLogger(AutoCritic.class);

    private final ObjectProvider<ProposalCritic> critic;
    @Value("${oms.learning.critic.auto:true}") private boolean enabled;

    public AutoCritic(ObjectProvider<ProposalCritic> critic) { this.critic = critic; }

    @TransactionalEventListener(fallbackExecution = true)
    public void onEvaluated(ProposalRepository.Evaluated e) {
        ProposalCritic c = critic.getIfAvailable();
        if (!enabled || c == null) return;
        try {
            TenantContext.supplyAs(e.tenantId(), () -> c.review(e.proposalId()));
        } catch (RuntimeException ex) {
            log.warn("Automatic critic review of {} failed; the evaluation stands without one: {}", e.proposalId(), ex.toString());
        }
    }
}
