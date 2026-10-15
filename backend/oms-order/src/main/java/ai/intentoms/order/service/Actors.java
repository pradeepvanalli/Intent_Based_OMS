package ai.intentoms.order.service;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;

/** Resolves the acting {@link Actor}: the authenticated principal, else the order module's system actor (workflow steps, sweepers). */
final class Actors {
    private Actors() {}

    static Actor current(AuthorizationService authz) {
        return authz.currentPrincipal().map(p -> Actor.parse(p.actorRef())).orElse(Actor.system("order"));
    }
}
