package ai.intentoms.order.modification;

import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The UI sends {@code {type, lineId, qty, address, shipMethod, newSku}} flat; Jackson must pick the variant from {@code type}. */
class ModificationJsonTest {
    @Test
    void polymorphicRoundTrip() {
        assertThat(Json.read("{\"type\":\"CancelLine\",\"lineId\":\"L1\",\"reason\":\"x\"}", Modification.class)).isEqualTo(new Modification.CancelLine("L1"));
        assertThat(Json.read("{\"type\":\"ChangeQty\",\"lineId\":\"L1\",\"qty\":3}", Modification.class)).isEqualTo(new Modification.ChangeQty("L1", 3));
        assertThat(Json.read("{\"type\":\"CancelOrder\"}", Modification.class)).isEqualTo(new Modification.CancelOrder());
        assertThat(Json.read("{\"type\":\"ChangeShipMethod\",\"shipMethod\":\"air_2day\"}", Modification.class)).isEqualTo(new Modification.ChangeShipMethod("air_2day"));
        assertThat(Json.read("{\"type\":\"ChangeShipMethod\",\"method\":\"ground\"}", Modification.class)).isEqualTo(new Modification.ChangeShipMethod("ground"));
        assertThat(Json.read("{\"type\":\"ChangeItem\",\"lineId\":\"L1\",\"newSku\":\"CAP-NVY\"}", Modification.class)).isEqualTo(new Modification.ChangeItem("L1", "CAP-NVY"));
        Modification m = Json.read("{\"type\":\"ChangeAddress\",\"address\":{\"type\":\"ship_to\",\"line1\":\"1 Main\",\"city\":\"SF\",\"state\":\"CA\",\"zip\":\"94105\",\"country\":\"US\"}}", Modification.class);
        assertThat(m).isInstanceOf(Modification.ChangeAddress.class);
        assertThat(((Modification.ChangeAddress) m).address().zip()).isEqualTo("94105");
        assertThat(Json.write(new Modification.CancelLine("L1"))).contains("\"type\":\"CancelLine\"");
    }
}
