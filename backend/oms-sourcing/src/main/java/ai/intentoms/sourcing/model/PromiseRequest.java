package ai.intentoms.sourcing.model;

import java.util.List;

/** Promise query from product page, cart or checkout (FR-SRC-05). */
public record PromiseRequest(List<PromiseLine> lines, String destinationZip, String destinationState, String channel, String shipMethod) {
    public record PromiseLine(String sku, int qty) {}

    public PromiseRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
        shipMethod = shipMethod == null || shipMethod.isBlank() ? SourcingRequest.METHOD_GROUND : shipMethod;
        destinationState = destinationState == null ? null : destinationState.toUpperCase();
    }
}
