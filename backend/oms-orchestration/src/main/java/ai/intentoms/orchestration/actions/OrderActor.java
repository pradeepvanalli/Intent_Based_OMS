package ai.intentoms.orchestration.actions;

import ai.intentoms.platform.ledger.Actor;

/** Ledger actor for writes made by playbook / bulk handlers. */
final class OrderActor {
    private OrderActor() {}
    static final Actor ACTOR = Actor.system("orchestration");
}
