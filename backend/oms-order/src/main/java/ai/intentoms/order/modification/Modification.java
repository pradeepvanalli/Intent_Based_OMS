package ai.intentoms.order.modification;

import ai.intentoms.order.model.Address;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A customer-service change to an order (FR-CS-02, UC-07). JSON carries the variant in {@code type}
 * ({@code ChangeAddress | ChangeQty | CancelLine | CancelOrder | ChangeShipMethod | ChangeItem}) with the variant's fields flat,
 * matching the UI's {@code Modification} shape.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Modification.ChangeAddress.class, name = "ChangeAddress"),
    @JsonSubTypes.Type(value = Modification.ChangeQty.class, name = "ChangeQty"),
    @JsonSubTypes.Type(value = Modification.CancelLine.class, name = "CancelLine"),
    @JsonSubTypes.Type(value = Modification.CancelOrder.class, name = "CancelOrder"),
    @JsonSubTypes.Type(value = Modification.ChangeShipMethod.class, name = "ChangeShipMethod"),
    @JsonSubTypes.Type(value = Modification.ChangeItem.class, name = "ChangeItem")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface Modification {
    /** Variant name as used in JSON and events. */
    default String type() { return getClass().getSimpleName(); }

    /** Replace the ship-to address (carrier intercept once shipped). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChangeAddress(Address address) implements Modification {}

    /** Set a line to a new quantity; a decrease cancels units, an increase needs ATP. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChangeQty(String lineId, int qty) implements Modification {}

    /** Cancel every cancellable unit of a line. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelLine(String lineId) implements Modification {}

    /** Cancel the whole order. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelOrder() implements Modification {}

    /** Change the carrier service (ground, air_2day, ...). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChangeShipMethod(@JsonAlias({"method"}) String shipMethod) implements Modification {}

    /** Swap a line's sku before release (same quantity). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChangeItem(String lineId, String newSku) implements Modification {}
}
