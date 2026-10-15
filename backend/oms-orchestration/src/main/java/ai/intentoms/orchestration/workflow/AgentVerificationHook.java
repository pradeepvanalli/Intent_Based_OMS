package ai.intentoms.orchestration.workflow;

import ai.intentoms.order.model.Order;

import java.util.Optional;

/**
 * SPI through which the agent module (not a dependency of orchestration) can verify an agent-placed order against its
 * mandate (UC-09, FR-CAP-07, FR-AGT-04) while the order workflow is at {@code verify_agent}. {@code Optional.of(true)}
 * releases the {@code agent_verification} hold and continues; {@code Optional.of(false)} cancels the order; an empty
 * result (or no bean) parks the workflow until an {@code agent_verified} / {@code agent_rejected} signal arrives.
 */
public interface AgentVerificationHook {
    Optional<Boolean> verify(Order order);
}
