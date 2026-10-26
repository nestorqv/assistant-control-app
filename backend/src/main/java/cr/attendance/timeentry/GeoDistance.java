package cr.attendance.timeentry;

/** Haversine great-circle distance. */
public final class GeoDistance {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        // Rounding can push "a" slightly outside [0, 1] (antipodal points): clamp to avoid NaN.
        double clamped = Math.min(1.0, Math.max(0.0, a));
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(clamped));
    }
}
