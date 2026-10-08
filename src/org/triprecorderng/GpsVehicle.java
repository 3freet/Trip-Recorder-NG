package org.triprecorderng;

import android.location.Location;

/**
 * Last-resort data source that needs nothing from the car: speed comes from the GPS receiver.
 * No ignition, battery, energy or odometer values, so trips are detected by movement.
 */
final class GpsVehicle implements Vehicle {
    private final Gps gps;

    GpsVehicle(Gps gps) {
        this.gps = gps;
    }

    @Override public String name() { return "GPS only"; }

    @Override public double speedKmh() {
        Location l = gps.fresh(4000);
        if (l == null || !l.hasSpeed()) return Double.NaN;
        // ignore the receiver's jitter while standing still
        double kmh = l.getSpeed() * 3.6;
        return kmh < 3.0 ? 0 : kmh;
    }

    @Override public double powerLevel() { return Double.NaN; }

    @Override public double socPercent() { return Double.NaN; }

    @Override public double totalElecConsumption() { return Double.NaN; }

    @Override public double totalDrivingTime() { return Double.NaN; }

    @Override public double odometerKm() { return Double.NaN; }

    @Override public String lastError() { return gps.isStarted() ? "" : "GPS not running"; }
}
