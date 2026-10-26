package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoDistanceTest {

    @Test
    void samePointIsZero() {
        assertThat(GeoDistance.meters(9.9325, -84.0796, 9.9325, -84.0796)).isZero();
    }

    @Test
    void oneDegreeOfLongitudeAtTheEquator() {
        assertThat(GeoDistance.meters(0, 0, 0, 1)).isCloseTo(111_195, within(1.0));
    }

    @Test
    void twoSanJosePointsAboutThirtyFiveMetersApart() {
        // 0.000315 degrees of latitude is about 35 m.
        double d = GeoDistance.meters(9.9325, -84.0796, 9.9325 + 0.000315, -84.0796);
        assertThat(d).isCloseTo(35, within(1.0));
    }

    @Test
    void acrossTheAntimeridian() {
        // 0.2 degrees of longitude at the equator, not 359.8.
        assertThat(GeoDistance.meters(0, 179.9, 0, -179.9)).isCloseTo(22_239, within(2.0));
    }

    @Test
    void nearThePole() {
        double d = GeoDistance.meters(89.9, 0, 89.9, 180);
        assertThat(d).isCloseTo(22_239, within(2.0));
    }

    @Test
    void antipodalPointsAreHalfTheCircumferenceAndNeverNaN() {
        double d = GeoDistance.meters(0, 0, 0, 180);
        assertThat(d).isNotNaN().isCloseTo(Math.PI * 6_371_008.8, within(1.0));
        assertThat(GeoDistance.meters(45, 10, -45, -170)).isNotNaN().isGreaterThan(20_000_000);
    }
}
