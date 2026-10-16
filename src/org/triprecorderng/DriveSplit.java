package org.triprecorderng;

import android.content.Context;

import java.util.List;

/**
 * Tells the driving of a trip from the car standing still, so kWh per 100 km and the drive time are not inflated by a
 * car left parked with the air conditioning on, or with the cable in (a trip stays open while the ignition reads on).
 *
 * <p>The samples come in time order: the time, the speed, the energy counter and the battery percentage. Between two
 * samples the car either drove or stood still. A standstill of {@link #PARKED_MS} or more is parking and is left out;
 * shorter stops (junctions, queues) are part of the drive. What is counted as driving is the time, the change of the
 * energy counter (net of regeneration and of what the engine charges) and the battery points used. What is left out
 * is the rest of the trip: its parked part is the whole trip minus this.
 */
final class DriveSplit {
    /** At or below this speed (km/h) the car counts as standing. */
    static final double STILL_KMH = 3;
    /** Standing still this long (ms) is parking; a shorter stop is traffic. */
    static final long PARKED_MS = 300_000;
    /** A hole in the samples this long (ms) next to a standstill is taken to be parking as well. */
    static final long GAP_MS = 300_000;

    private boolean have;
    private long lastTs;
    private boolean lastStill;
    private double lastElec = Double.NaN;
    private double lastSoc = Double.NaN;

    // the standstill in progress: its energy, time and battery points so far
    private boolean inRun;
    private double runKwh;
    private double runSec;
    private double runSoc;

    private double driveKwh;
    private double driveSec;
    private double driveSoc;
    private boolean energySeen;

    /** Adds the next sample; a sample without a speed says nothing about driving and is skipped. */
    void add(long tsMs, double speedKmh, double elec, double soc) {
        if (Double.isNaN(speedKmh)) return;
        boolean still = speedKmh <= STILL_KMH;
        if (!have) {
            have = true;
            lastTs = tsMs;
            lastStill = still;
            lastElec = elec;
            lastSoc = soc;
            inRun = still;
            runKwh = runSec = runSoc = 0;
            return;
        }
        long dtMs = tsMs - lastTs;
        if (dtMs <= 0) return;
        double dt = dtMs / 1000.0;
        double de = 0;
        if (!Double.isNaN(elec) && !Double.isNaN(lastElec)) {
            de = elec - lastElec;
            energySeen = true;
        }
        double ds = Double.isNaN(soc) || Double.isNaN(lastSoc) ? 0 : lastSoc - soc;
        boolean gap = dtMs > GAP_MS;
        if (lastStill && still) {
            runKwh += de;
            runSec += dt;
            runSoc += ds;
        } else if (lastStill) {                       // moving off
            if (gap) {
                runKwh += de;
                runSec += dt;
                runSoc += ds;
            } else {
                drive(de, dt, ds);
            }
            closeRun();
        } else if (still) {                           // coming to a stop
            if (gap) {
                inRun = true;
                runKwh = de;
                runSec = dt;
                runSoc = ds;
            } else {
                drive(de, dt, ds);
                inRun = true;
                runKwh = runSec = runSoc = 0;
            }
        } else {
            drive(de, dt, ds);
        }
        lastTs = tsMs;
        lastStill = still;
        if (!Double.isNaN(elec)) lastElec = elec;
        if (!Double.isNaN(soc)) lastSoc = soc;
    }

    private void drive(double kwh, double sec, double soc) {
        driveKwh += kwh;
        driveSec += sec;
        driveSoc += soc;
    }

    /** A standstill that is over: it was parking (left out) or traffic (part of the drive). */
    private void closeRun() {
        if (inRun && runSec * 1000 < PARKED_MS) drive(runKwh, runSec, runSoc);
        inRun = false;
        runKwh = runSec = runSoc = 0;
    }

    /** The trip is over: a standstill at its end is closed the same way. */
    void finish() {
        closeRun();
    }

    /** The drive so far, with a standstill in progress counted as traffic until it has lasted long enough. */
    private boolean runCounts() {
        return inRun && runSec * 1000 < PARKED_MS;
    }

    /** Energy counter change while driving (kWh, net), NaN when the samples had no energy counter. */
    double driveKwh() {
        if (!energySeen) return Double.NaN;
        return driveKwh + (runCounts() ? runKwh : 0);
    }

    /** Seconds spent driving. */
    double driveSec() {
        return driveSec + (runCounts() ? runSec : 0);
    }

    /** Battery points used while driving (net, so what the engine charged is already taken off). */
    double driveSoc() {
        return driveSoc + (runCounts() ? runSoc : 0);
    }

    /** Stores the result in the trip. */
    void applyTo(Trip t) {
        t.driveKwh = driveKwh();
        t.driveSec = driveSec();
    }

    /** The split of a finished or running trip from its stored samples (ts, ..., speeds, soc, elec as in TripDb). */
    static DriveSplit replay(List<double[]> points) {
        DriveSplit s = new DriveSplit();
        for (double[] p : points) {
            double speed = Double.isNaN(p[5]) ? p[4] : p[5];
            s.add((long) p[0], speed, p[8], p[7]);
        }
        return s;
    }

    private static boolean running;
    /** Trips already tried in this run of the app (one with no samples stays unsplit; do not look at it again). */
    private static final java.util.Set<String> tried = new java.util.HashSet<>();

    /**
     * Works the split out for the finished trips that have none yet (older trips, restored ones), from their stored
     * samples. Returns how many were done. Safe to call from several places: only one run at a time.
     */
    static int backfill(Context ctx) {
        synchronized (DriveSplit.class) {
            if (running) return 0;
            running = true;
        }
        int done = 0;
        try {
            TripDb db = TripDb.get(ctx);
            for (Trip t : db.all()) {
                if (!t.ended || !Double.isNaN(t.driveSec)) continue;
                synchronized (DriveSplit.class) {
                    if (!tried.add(t.id)) continue;
                }
                List<double[]> pts = db.points(t.id);
                if (pts.size() < 2) continue;
                DriveSplit s = replay(pts);
                s.finish();
                s.applyTo(t);
                db.upsert(t);
                done++;
            }
        } catch (Throwable x) {
            Diag.log("drive split failed", x);
        } finally {
            synchronized (DriveSplit.class) {
                running = false;
            }
        }
        if (done > 0) Diag.log("drive split worked out for " + done + " trip(s)");
        return done;
    }
}
