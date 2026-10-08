package org.triprecorderng;

/**
 * Trip distance from the car's own journey counter (the trip meter of the instrument cluster,
 * 0.1 km steps), which agrees with the odometer and GPS. The speedometer value that the recorder also
 * integrates reads a few percent above the real speed, so it is only the fallback and the short-trip
 * estimate. The counter can restart from zero (new journey, or the driver resets it): that is handled
 * by adding up the steps instead of reading it as a total.
 */
final class JourneyTracker {
    private JourneyTracker() {}

    /** Real distance over integrated speedometer distance, measured on this car; refined from long trips. */
    static final double DEFAULT_SCALE = 0.958;
    /** Below this the counter's 0.1 km steps are too coarse and the speed integral is used. */
    static final double TRUST_KM = 1.0;
    private static final double RESET_DROP_KM = 0.3;
    private static final double MAX_KMH = 260;

    /**
     * Feeds one reading (NaN = none). The first reading of a trip starts the count; if speed was already
     * integrated before it (the data link came up late, or a trip from an older version is resumed)
     * the km driven so far are estimated from speed times scale.
     */
    static void update(Trip t, double reading, double scale, long wallMs) {
        if (Double.isNaN(reading) || reading < 0 || reading > 999_999) return;
        if (Double.isNaN(t.journeyLast)) {
            t.journeyLast = reading;
            t.journeyLastWall = wallMs;
            if (Double.isNaN(t.journeyAcc)) t.journeyAcc = Math.max(0, t.speedKm) * scale;
            return;
        }
        double step = reading - t.journeyLast;
        if (step < -RESET_DROP_KM) {
            // the counter started again from zero: what it shows now was driven since the restart
            t.journeyAcc += reading;
        } else if (step > 0) {
            // a jump the car could not have driven since the last reading is a bad value: ignore it
            double hours = t.journeyLastWall > 0 ? Math.max(0, wallMs - t.journeyLastWall) / 3_600_000.0 : Double.MAX_VALUE;
            if (step > 0.5 + hours * MAX_KMH) return;
            t.journeyAcc += step;
        } else if (step < 0) {
            return; // noise: keep the higher reading as the reference
        }
        t.journeyLast = reading;
        t.journeyLastWall = wallMs;
    }

    /** The distance to show for the trip so far. */
    static double distance(Trip t) {
        if (!Double.isNaN(t.journeyAcc) && t.journeyAcc >= TRUST_KM) return t.journeyAcc;
        return t.speedKm;
    }

    /** Ratio of the counter to the speed integral for a long trip, for the new scale; NaN if the trip is too short. */
    static double observedScale(Trip t) {
        if (Double.isNaN(t.journeyAcc) || t.journeyAcc < 5 || t.speedKm < 5) return Double.NaN;
        double r = t.journeyAcc / t.speedKm;
        return r < 0.85 || r > 1.1 ? Double.NaN : r;
    }
}
