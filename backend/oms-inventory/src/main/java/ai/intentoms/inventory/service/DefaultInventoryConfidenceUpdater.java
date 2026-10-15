package ai.intentoms.inventory.service;

import ai.intentoms.inventory.store.InventoryPositionStore;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Writes FR-INV-05 confidence scores onto the on-hand position and records the change in the ledger. */
@Service
public class DefaultInventoryConfidenceUpdater implements InventoryConfidenceUpdater {
    private final InventoryPositionStore positions;
    private final EventStore events;
    private final TimeSource time;
    private final AuthorizationService authz;

    public DefaultInventoryConfidenceUpdater(InventoryPositionStore positions, EventStore events, TimeSource time, AuthorizationService authz) {
        this.positions = positions;
        this.events = events;
        this.time = time;
        this.authz = authz;
    }

    @Override
    @Transactional
    public void set(String sku, String nodeId, double confidence) {
        if (confidence < 0 || confidence > 1 || Double.isNaN(confidence))
            throw new ValidationException("confidence must be within 0..1", Map.of("sku", sku, "nodeId", nodeId, "confidence", confidence));
        String tenant = TenantContext.require();
        if (!positions.setConfidence(tenant, sku, nodeId, confidence, time.now()))
            throw new NotFoundException("No on-hand position for " + sku + " at " + nodeId, Map.of("sku", sku, "nodeId", nodeId));
        events.append(InventoryEvents.NODE, nodeId, new NewEvent(InventoryEvents.INVENTORY_CONFIDENCE_UPDATED,
            Map.of("nodeId", nodeId, "sku", sku, "confidence", confidence), Actor.parse(authz.actorRef())));
    }
}
