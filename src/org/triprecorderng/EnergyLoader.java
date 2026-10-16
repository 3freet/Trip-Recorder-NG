package org.triprecorderng;

import android.content.Context;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives the screens the {@link TripEnergy} of a trip. A trip the engine did not drive needs only its own counters. On
 * a hybrid trip the battery trace is read from the database on a background thread (what the engine charged is told
 * from it) and kept; until it is ready the counters-only figures are returned, and the screens, which redraw every
 * second, pick the full ones up when they arrive. A running trip is read again every 30 seconds.
 */
final class EnergyLoader {
    private static final long LIVE_STEP_MS = 30_000;

    private static final class Entry {
        final long stamp;
        final TripEnergy energy;

        Entry(long stamp, TripEnergy energy) {
            this.stamp = stamp;
            this.energy = energy;
        }
    }

    private static final Map<String, Entry> done = new HashMap<>();
    private static final Set<String> busy = new HashSet<>();

    private EnergyLoader() {}

    /** The figures for this trip, as complete as they are right now (never blocks). */
    static TripEnergy get(final Context ctx, Trip t) {
        final String id = t.id;
        TripEnergy basic = TripEnergy.basic(t);
        if (id == null || !basic.hybrid) return basic;
        final long stamp = t.ended ? 0 : t.lastUpdateMs / LIVE_STEP_MS;
        Entry have;
        synchronized (EnergyLoader.class) {
            have = done.get(id);
            if (have != null && have.stamp == stamp) return have.energy;
            if (busy.contains(id)) return have != null ? have.energy : basic;
            busy.add(id);
        }
        final double km = t.distanceKm;
        final double whole = t.energyUsed();
        final double driveKwh = t.driveEnergy();
        final double fuel = t.fuelUsed();
        final double ev = t.evKm();
        final double hev = t.hevKm();
        final boolean over = t.ended;
        final Context app = ctx.getApplicationContext();
        final String tripId = id;
        final long forStamp = stamp;
        new Thread(new Runnable() {
            @Override public void run() {
                TripEnergy e;
                try {
                    List<double[]> pts = TripDb.get(app).points(tripId);
                    DriveSplit split = DriveSplit.replay(pts);
                    if (over) split.finish();
                    double soc = split.driveSoc();
                    double kwh = split.driveKwh();
                    double kwhPerPoint = soc >= TripEnergy.MIN_DROP_PCT && kwh > 0 ? kwh / soc : Double.NaN;
                    double[] moving = movingSoc(pts);
                    double charged = moving.length >= 10 ? TripEnergy.swings(moving, TripEnergy.SWING_PCT)[0] : Double.NaN;
                    e = TripEnergy.of(km, whole, driveKwh, fuel, ev, hev, kwhPerPoint, charged, true);
                } catch (Throwable x) {
                    Diag.log("energy figures failed", x);
                    e = TripEnergy.of(km, whole, driveKwh, fuel, ev, hev, Double.NaN, Double.NaN, true);
                }
                synchronized (EnergyLoader.class) {
                    done.put(tripId, new Entry(forStamp, e));
                    busy.remove(tripId);
                }
            }
        }, "energy-figures").start();
        return have != null ? have.energy : basic;
    }

    /** The battery percentage of the samples taken while the car was moving (columns 5 car speed or 4 GPS, 7 soc). */
    static double[] movingSoc(List<double[]> pts) {
        double[] out = new double[pts.size()];
        int n = 0;
        for (double[] p : pts) {
            double soc = p[7];
            if (Double.isNaN(soc)) continue;
            double speed = Double.isNaN(p[5]) ? p[4] : p[5];
            if (Double.isNaN(speed) || speed <= DriveSplit.STILL_KMH) continue;
            out[n++] = soc;
        }
        double[] r = new double[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }
}
