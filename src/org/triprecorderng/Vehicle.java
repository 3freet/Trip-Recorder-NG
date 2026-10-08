package org.triprecorderng;

/**
 * Read-only view of the vehicle signals the recorder needs. Every getter returns NaN when the
 * value is unavailable (not supported, permission denied, or the vehicle reported "invalid").
 */
interface Vehicle {
    /** Short name of the access path, e.g. "BYD framework" or "autoservice binder". */
    String name();

    /** Vehicle speed in km/h. */
    double speedKmh();

    /** Ignition level: 0 off, 1 ACC, 2 on, 3 OK, 4 fake-OK, 255 invalid. NaN if unavailable. */
    double powerLevel();

    /** Traction battery state of charge, percent. */
    double socPercent();

    /** Cumulative electric energy consumed (counter), as reported by the vehicle. */
    double totalElecConsumption();

    /** Cumulative driving time counter, as reported by the vehicle. */
    double totalDrivingTime();

    /** Odometer in km. */
    double odometerKm();

    /** The cluster's current journey (trip meter) in km, 0.1 km steps; NaN if unavailable. */
    default double journeyKm() { return Double.NaN; }

    /** Cumulative fuel burned (counter, litres) on a plug-in hybrid; NaN if unavailable. */
    default double totalFuelLitres() { return Double.NaN; }

    /** Cumulative distance driven in electric mode / in hybrid mode (counters, km). */
    default double evMileageKm() { return Double.NaN; }

    default double hevMileageKm() { return Double.NaN; }

    /** Fuel tank level in percent. */
    default double fuelPercent() { return Double.NaN; }

    /** Remaining range on the battery / on fuel, km. */
    default double elecRangeKm() { return Double.NaN; }

    default double fuelRangeKm() { return Double.NaN; }

    /** Last error text, for the diagnostics screen. */
    String lastError();

    /** Sentinel values the BYD API uses for "no data". */
    static boolean valid(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v > -2.0e9 && v != -1.0;
    }

    /**
     * Charging snapshot {battery-management state, gun state, charger work state, power kW,
     * charged kWh} (NaN where unknown), or null if this source cannot read the charging device.
     * BMS state 1 = charging, 13 = paused; gun state 2/3/4 = AC/DC/both connected.
     */
    default double[] charging() {
        return null;
    }
}
