package org.triprecorderng;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives the screens the {@link TripEnergy} of a trip. The figures that need the battery trace are worked out on a
 * background thread (the trace is read from the database) and kept; until they are ready the counters-only figures
 * are returned, and the screens, which redraw every second, pick the full ones up when they arrive. A running trip
 * is read again every 30 seconds.
 */
final class EnergyLoader {
    private static final long LIVE_STEP_MS = 30_000;
    /** A charging session this close to the start or end of a trip is the cable going in or out, not mid-trip. */
    private static final long OVERLAP_SLACK_MS = 60_000;

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
    static TripEnergy get(final Context ctx, Trip t, List<Charge> charges) {
        final String id = t.id;
        if (id == null) return TripEnergy.basic(t);
        final long stamp = t.ended ? 0 : t.lastUpdateMs / LIVE_STEP_MS;
        Entry have;
        synchronized (EnergyLoader.class) {
            have = done.get(id);
            if (have != null && have.stamp == stamp) return have.energy;
            if (busy.contains(id)) return have != null ? have.energy : TripEnergy.basic(t);
            busy.add(id);
        }
        final double km = t.distanceKm;
        final double net = t.energyUsed();
        final double fuel = t.fuelUsed();
        final double ev = t.evKm();
        final double hev = t.hevKm();
        final double s0 = t.socStart;
        final double s1 = t.socEnd;
        final long from = t.startMs;
        final long to = t.ended && t.endMs > 0 ? t.endMs : System.currentTimeMillis();
        final List<Charge> sessions = new ArrayList<>(charges);
        final Context app = ctx.getApplicationContext();
        final String tripId = id;
        final long forStamp = stamp;
        new Thread(new Runnable() {
            @Override public void run() {
                TripEnergy e;
                try {
                    List<double[]> pts = TripDb.get(app).points(tripId);
                    double[] moving = movingSoc(pts);
                    e = TripEnergy.of(km, net, fuel, ev, hev, s0, s1, moving, pluggedIn(sessions, from, to));
                } catch (Throwable x) {
                    Diag.log("energy figures failed", x);
                    e = TripEnergy.of(km, net, fuel, ev, hev, s0, s1, new double[0], false);
                }
                synchronized (EnergyLoader.class) {
                    done.put(tripId, new Entry(forStamp, e));
                    busy.remove(tripId);
                }
            }
        }, "energy-figures").start();
        return have != null ? have.energy : TripEnergy.basic(t);
    }

    /** The battery percentage of the samples taken while the car was moving (columns 5 car speed or 4 GPS, 7 soc). */
    static double[] movingSoc(List<double[]> pts) {
        double[] out = new double[pts.size()];
        int n = 0;
        for (double[] p : pts) {
            double soc = p[7];
            if (Double.isNaN(soc)) continue;
            double speed = Double.isNaN(p[5]) ? p[4] : p[5];
            if (Double.isNaN(speed) || speed <= 3) continue;
            out[n++] = soc;
        }
        double[] r = new double[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    /**
     * Whether the car was charged from a cable during the time span: a charging session that overlaps it by more than
     * the slack at its ends and that really put something into the battery (a plug blip at a full battery, or a cable
     * that went in just before driving off, changes nothing).
     */
    static boolean pluggedIn(List<Charge> sessions, long from, long to) {
        for (Charge c : sessions) {
            double gain = c.socGained();
            boolean real = Double.isNaN(gain) ? c.durationSec() >= 600 : gain >= 2;
            if (!real) continue;
            long end = c.endMs > 0 ? c.endMs : (c.ended && c.lastUpdateMs > 0 ? c.lastUpdateMs : System.currentTimeMillis());
            if (c.startMs < to - OVERLAP_SLACK_MS && end > from + OVERLAP_SLACK_MS) return true;
        }
        return false;
    }
}
