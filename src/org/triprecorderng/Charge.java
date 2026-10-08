package org.triprecorderng;

/** One charging session: from the cable going in and charging starting, until it stops. */
final class Charge {
    String id;
    long startMs;
    long endMs;            // 0 while charging
    long lastUpdateMs;
    boolean ended;
    double socStart = Double.NaN;
    double socEnd = Double.NaN;
    double kwhSignal = Double.NaN;   // charged energy reported by the car (0 or missing on many units)
    double maxPowerKw = Double.NaN;  // highest charging power reported by the car
    int gun;                         // 2 AC, 3 DC, 4 both (BYD gun state), 0 unknown
    boolean partial;                 // the app missed part of it (head unit asleep or app not running)
    boolean windowOnly;              // charged while the unit slept: only the levels before and after are known
    double lat = Double.NaN, lon = Double.NaN;

    long durationSec() {
        long end = ended && endMs > 0 ? endMs : lastUpdateMs;
        return Math.max(0, (end - startMs) / 1000);
    }

    /** Battery percentage points added during the session. */
    double socGained() {
        if (Double.isNaN(socStart) || Double.isNaN(socEnd)) return Double.NaN;
        return socEnd - socStart;
    }

    String typeName() {
        return gun == 3 ? "DC" : gun == 2 ? "AC" : gun == 4 ? "AC+DC" : "";
    }

    /**
     * Energy added to the battery in kWh: the car's own reading when it gives one, otherwise the
     * percentage gained times kWh per percent learned from trips (NaN if neither is known).
     */
    double energyKwh(double kwhPerPercent) {
        if (!Double.isNaN(kwhSignal) && kwhSignal > 0.05) return kwhSignal;
        double g = socGained();
        if (Double.isNaN(g) || Double.isNaN(kwhPerPercent) || g <= 0) return Double.NaN;
        return g * kwhPerPercent;
    }

    boolean energyIsEstimate() {
        return Double.isNaN(kwhSignal) || kwhSignal <= 0.05;
    }

    /** kWh per battery percent, from the trips with a large enough drop (median); NaN if none. */
    static double kwhPerPercent(java.util.List<Trip> trips) {
        java.util.List<Double> v = new java.util.ArrayList<>();
        for (Trip t : trips) {
            double used = t.socUsed(), e = t.energyUsed();
            if (!Double.isNaN(used) && !Double.isNaN(e) && used >= 8 && e > 0.5) v.add(e / used);
        }
        if (v.isEmpty()) return Double.NaN;
        java.util.Collections.sort(v);
        return v.get((v.size() - 1) / 2);
    }
}
