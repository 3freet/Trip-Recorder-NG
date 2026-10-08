package org.triprecorderng;

/** One recorded trip (an ignition cycle, or a drive when ignition state is unavailable). */
final class Trip {
    String id;
    long startMs;
    long endMs;            // 0 while the trip is running
    long lastUpdateMs;
    boolean ended;
    String mode = "";      // "ignition" or "motion"

    double distanceKm;     // the distance shown: the car's journey counter when it is available, else speedKm
    double speedKm;        // integrated from the speedometer value (reads a few percent above the real distance)
    double journeyAcc = Double.NaN;   // km counted from the car's own journey (trip) counter, NaN if never read
    double journeyLast = Double.NaN;  // last reading of that counter (kept so a restart can carry on)
    long journeyLastWall;             // when journeyLast was read (not stored)
    double odoStartKm = Double.NaN;
    double odoEndKm = Double.NaN;
    double movingSec;
    double idleSec;
    double maxSpeedKmh;

    double socStart = Double.NaN;
    double socEnd = Double.NaN;
    double elecStart = Double.NaN;
    double elecEnd = Double.NaN;

    // plug-in hybrid counters: fuel burned (litres) and distance in electric / hybrid mode (km)
    double fuelStart = Double.NaN, fuelEnd = Double.NaN;
    double evStart = Double.NaN, evEnd = Double.NaN;
    double hevStart = Double.NaN, hevEnd = Double.NaN;

    int hardAccel;
    int hardBrake;

    double startLat = Double.NaN, startLon = Double.NaN;
    double endLat = Double.NaN, endLon = Double.NaN;

    long durationSec() {
        long end = ended && endMs > 0 ? endMs : lastUpdateMs;
        return Math.max(0, (end - startMs) / 1000);
    }

    double avgSpeedKmh() {
        return movingSec > 5 ? distanceKm / (movingSec / 3600.0) : 0;
    }

    double odometerDeltaKm() {
        if (Double.isNaN(odoStartKm) || Double.isNaN(odoEndKm)) return Double.NaN;
        return Math.max(0, odoEndKm - odoStartKm);
    }

    /** Energy counter delta in the vehicle's own unit (kWh on the tested unit). */
    double energyUsed() {
        if (Double.isNaN(elecStart) || Double.isNaN(elecEnd)) return Double.NaN;
        return Math.max(0, elecEnd - elecStart);
    }

    double energyPer100Km() {
        double e = energyUsed();
        if (Double.isNaN(e) || distanceKm < 0.5) return Double.NaN;
        return e / distanceKm * 100.0;
    }

    private static double delta(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return Double.NaN;
        return Math.max(0, b - a);
    }

    /** Fuel burned during the trip in litres (NaN if the counter was not recorded). */
    double fuelUsed() { return delta(fuelStart, fuelEnd); }

    double evKm() { return delta(evStart, evEnd); }

    double hevKm() { return delta(hevStart, hevEnd); }

    double fuelPer100Km() {
        double f = fuelUsed();
        if (Double.isNaN(f) || distanceKm < 0.5) return Double.NaN;
        return f / distanceKm * 100.0;
    }

    double socUsed() {
        if (Double.isNaN(socStart) || Double.isNaN(socEnd)) return Double.NaN;
        return socStart - socEnd;
    }
}
