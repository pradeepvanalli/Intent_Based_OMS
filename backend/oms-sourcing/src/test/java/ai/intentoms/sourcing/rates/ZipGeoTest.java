package ai.intentoms.sourcing.rates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Haversine and the built-in ZIP3 / state / country geocoder (FR-SRC-01 distance filter, FR-SRC-05 determinism). */
class ZipGeoTest {
    @Test
    void haversineMatchesKnownDistances() {
        // New York (40.7128,-74.0060) to Los Angeles (34.0522,-118.2437) ≈ 3936 km.
        assertThat(ZipGeo.haversineKm(40.7128, -74.0060, 34.0522, -118.2437)).isCloseTo(3936, within(15.0));
        assertThat(ZipGeo.haversineKm(41.0, -87.0, 41.0, -87.0)).isZero();
        // Symmetric.
        assertThat(ZipGeo.haversineKm(10, 20, 30, 40)).isCloseTo(ZipGeo.haversineKm(30, 40, 10, 20), within(1e-9));
    }

    @Test
    void zip3ResolvesToCentroidAndState() {
        GeoPoint chicago = ZipGeo.locate("60611", "XX");
        assertThat(chicago.precision()).isEqualTo(GeoPoint.ZIP3);
        assertThat(chicago.state()).isEqualTo("IL");
        assertThat(chicago.lat()).isCloseTo(41.88, within(0.1));
        assertThat(chicago.transitPenaltyDays()).isZero();
        // ZIP+4 and whitespace tolerated.
        assertThat(ZipGeo.locate(" 90036-1234 ", null).state()).isEqualTo("CA");
        assertThat(ZipGeo.zip3Count()).isGreaterThanOrEqualTo(120);
    }

    @Test
    void unknownZipFallsBackToStateCentroidThenContinentalCentre() {
        GeoPoint tx = ZipGeo.locate("79599", "tx");
        assertThat(tx.precision()).isEqualTo(GeoPoint.STATE);
        assertThat(tx.state()).isEqualTo("TX");
        assertThat(tx.transitPenaltyDays()).isZero();

        GeoPoint unknown = ZipGeo.locate("ABCDE", "ZZ");
        assertThat(unknown.precision()).isEqualTo(GeoPoint.UNKNOWN);
        assertThat(unknown.transitPenaltyDays()).isEqualTo(1);
        assertThat(unknown.lat()).isCloseTo(39.83, within(0.01));

        assertThat(ZipGeo.locate(null, null).precision()).isEqualTo(GeoPoint.UNKNOWN);
    }

    @Test
    void foreignCountriesUseCountryCentroid() {
        GeoPoint mx = ZipGeo.locate("64000", "NL", "MX");
        assertThat(mx.precision()).isEqualTo(GeoPoint.COUNTRY);
        assertThat(mx.lat()).isCloseTo(23.63, within(0.01));
        assertThat(ZipGeo.locate("1", null, "ZZ").precision()).isEqualTo(GeoPoint.UNKNOWN);
    }

    @Test
    void distanceFromNodeToZipIsStable() {
        GeoPoint dest = ZipGeo.locate("08837", "NJ");
        double d = dest.distanceKm(40.5187, -74.4121); // DC-NJ sits in 088
        assertThat(d).isLessThan(15);
    }
}
