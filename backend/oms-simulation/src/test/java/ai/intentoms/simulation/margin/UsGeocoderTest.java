package ai.intentoms.simulation.margin;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Embedded geocoder: city, ZIP3 and state fallbacks, name/code normalisation, non-US skip. */
class UsGeocoderTest {
    @Test
    void resolvesCitiesStatesAndZipsInOrderOfPrecision() {
        assertThat(UsGeocoder.cityCount()).isGreaterThanOrEqualTo(150);
        UsGeocoder.Location sf = UsGeocoder.locate("San Francisco", "CA", "94105", "US").orElseThrow();
        assertThat(sf.precision()).isEqualTo("city");
        assertThat(sf.lat()).isCloseTo(37.77, org.assertj.core.data.Offset.offset(0.05));
        assertThat(sf.state()).isEqualTo("CA");
        // Full state name, odd casing and punctuation variants still hit the city table.
        assertThat(UsGeocoder.locate("st louis", "Missouri", null, "United States").orElseThrow().precision()).isEqualTo("city");
        assertThat(UsGeocoder.locate("Saint Paul", "mn", "", "").orElseThrow().precision()).isEqualTo("city");
        assertThat(UsGeocoder.locate("Ft. Worth", "TX", null, "USA").orElseThrow().precision()).isEqualTo("city");
        // Unknown city but known ZIP3 → ZIP3 centroid; unknown city and ZIP → state centroid.
        UsGeocoder.Location zip = UsGeocoder.locate("Smallville", "NY", "10512", "US").orElseThrow();
        assertThat(zip.precision()).isEqualTo("zip3");
        assertThat(zip.state()).isEqualTo("NY");
        UsGeocoder.Location st = UsGeocoder.locate("Nowhere", "WY", "", "US").orElseThrow();
        assertThat(st.precision()).isEqualTo("state");
        assertThat(st.lat()).isCloseTo(42.76, org.assertj.core.data.Offset.offset(0.01));
        // State recovered from the ZIP when the column is blank.
        assertThat(UsGeocoder.locate("Seattle", "", "98101", "US").orElseThrow()).satisfies(l -> { assertThat(l.state()).isEqualTo("WA"); assertThat(l.precision()).isEqualTo("city"); });
        // Distances are great-circle: Reno DC → Los Angeles ≈ 640 km.
        assertThat(UsGeocoder.locate("Los Angeles", "CA", null, null).orElseThrow().distanceKm(39.5296, -119.8138)).isBetween(600.0, 700.0);
    }

    @Test
    void nonUsAndUnknownDestinationsAreEmpty() {
        assertThat(UsGeocoder.locate("Toronto", "ON", "M5V 2T6", "CA")).isEmpty();
        assertThat(UsGeocoder.locate("London", "", "SW1A 1AA", "GB")).isEmpty();
        assertThat(UsGeocoder.locate("", "", "", "US")).isEmpty();
        assertThat(UsGeocoder.locate("Atlantis", "ZZ", "ABCDE", "US")).isEmpty();
        assertThat(UsGeocoder.isUs("United States")).isTrue();
        assertThat(UsGeocoder.isUs("us")).isTrue();
        assertThat(UsGeocoder.isUs("Canada")).isFalse();
        assertThat(UsGeocoder.stateCode("new york")).isEqualTo("NY");
        assertThat(UsGeocoder.stateCode("District of Columbia")).isEqualTo("DC");
        assertThat(UsGeocoder.stateCode("Ontario")).isNull();
        assertThat(Optional.ofNullable(UsGeocoder.stateCode(null))).isEmpty();
    }
}
