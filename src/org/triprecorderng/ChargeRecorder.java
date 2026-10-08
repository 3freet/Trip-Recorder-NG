package org.triprecorderng;

import android.content.Context;
import android.location.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Records charging sessions from the car's charging device (needs the Vehicle data link). A session
 * runs while the battery-management state says "charging" (or "paused") with the cable connected, and
 * ends one minute after that stops. The battery percentage is sampled along the way for the chart.
 *
 * The head unit sleeps when the car is off, and the app does not run while it sleeps. So the recorder
 * notices gaps: a session it did not see start, or that went unobserved for a while, is marked partial,
 * and when a session ends the battery level at that moment is taken as the final level.
 * Read-only: nothing is ever written to the car.
 */
final class ChargeRecorder {
    private static final long POLL_MS = 5000;
    private static final long POINT_MS = 60_000;
    private static final long SAVE_MS = 30_000;
    private static final long END_DEBOUNCE_MS = 60_000;
    private static final long RESUME_WINDOW_MS = 10 * 60_000;
    /** No readings for longer than this means the unit was asleep or the app was not running. */
    private static final long GAP_MS = 150_000;
    /** Battery gain that proves a charge happened unseen: with a cable/charge state showing, or without. */
    private static final double MISSED_GAIN_WITH_EVIDENCE = 2.0;
    private static final double MISSED_GAIN_NO_EVIDENCE = 5.0;
    /** The final level is not accepted if it is lower than the last one seen by more than this (driving). */
    private static final double FINAL_SOC_DROP_OK = 1.0;

    /** Two sessions are one when the second starts where the first stopped: same level, nothing driven between. */
    private static final double SAME_LEVEL_PCT = 1.0;
    private static final long MAX_JOIN_GAP_MS = 12 * 3600_000L;
    private static final long JOIN_START_SLACK_MS = 120_000;

    private static final int BMS_CHARGING = 1;
    private static final int BMS_PAUSED = 13;

    /** The session being recorded (read by the UI), or null. */
    static volatile Charge current;
    /** One line for the This Trip screen, empty when not charging. */
    static volatile String status = "";

    private final Context ctx;
    private final TripDb db;
    private final Gps gps;
    private final List<double[]> pending = new ArrayList<>();

    private Charge cur;
    private long lastPollMs;
    private long lastPointMs;
    private long lastSaveMs;
    private long inactiveSinceMs;
    private long firstInactiveWall;
    private long lastActiveWall;
    private long lastSeenWall;
    private boolean sawIdleBefore;
    private double lastPointSoc = Double.NaN;
    /** True until the battery level has been compared with the last one seen before a sleep or restart. */
    private boolean wakeCheckPending = true;
    private double lastNotedSoc = Double.NaN;
    private long lastNotedWall;
    /** Set when a charge went on unseen and is still running: the session starts from the old level. */
    private double missedStartSoc = Double.NaN;
    private long missedStartWall;

    ChargeRecorder(Context ctx, TripDb db, Gps gps) {
        this.ctx = ctx.getApplicationContext();
        this.db = db;
        this.gps = gps;
    }

    /** After a service restart: carry on with a recent open session, or close an old one. */
    void resume() {
        Charge open = db.lastOpenCharge();
        if (open == null) return;
        long wall = System.currentTimeMillis();
        if (wall - open.lastUpdateMs <= RESUME_WINDOW_MS) {
            if (wall - open.lastUpdateMs > GAP_MS) open.partial = true;
            cur = open;
            current = open;
            lastActiveWall = open.lastUpdateMs;
            Diag.log("charge: resumed open session " + open.id);
        } else {
            // the app was not running for a long time: the final level is whatever we saw last
            open.ended = true;
            open.partial = true;
            open.endMs = open.lastUpdateMs;
            finish(open);
            Diag.log("charge: closed stale session " + open.id);
        }
    }

    /** Called from the recorder's tick; does real work every few seconds. */
    void update(Vehicle v, long wall, long nowMs) {
        if (nowMs - lastPollMs < POLL_MS) return;
        lastPollMs = nowMs;

        // a long silence means the head unit slept (or the app was not running): we missed things
        if (lastSeenWall != 0 && wall - lastSeenWall > GAP_MS) {
            sawIdleBefore = false;
            wakeCheckPending = true;
            if (cur != null && !cur.partial) {
                cur.partial = true;
                Diag.log("charge: gap of " + (wall - lastSeenWall) / 1000 + " s, marked partial");
            }
        }
        lastSeenWall = wall;

        double[] c = v.charging();
        boolean known = c != null && !Double.isNaN(c[0]);
        int bms = known ? (int) c[0] : -1;
        int gun = known && !Double.isNaN(c[1]) ? (int) c[1] : 0;
        boolean gunIn = gun == 2 || gun == 3 || gun == 4;
        double speed = v.speedKmh();
        boolean active = known && (bms == BMS_CHARGING || bms == BMS_PAUSED)
                && (gunIn || Double.isNaN(speed) || speed < 1);
        double soc = v.socPercent();

        if (wakeCheckPending && !Double.isNaN(soc)) {
            wakeCheckPending = false;
            checkMissedCharge(soc, active, known, bms, gun, wall);
        }
        noteSoc(soc, wall);

        if (active) {
            inactiveSinceMs = 0;
            firstInactiveWall = 0;
            lastActiveWall = wall;
            if (cur == null) start(gun, soc, wall);
            cur.lastUpdateMs = wall;
            if (!Double.isNaN(soc)) {
                if (Double.isNaN(cur.socStart)) cur.socStart = soc;
                cur.socEnd = soc;
            }
            double kwh = c[4], power = c[3];
            if (!Double.isNaN(kwh) && (Double.isNaN(cur.kwhSignal) || kwh > cur.kwhSignal)) cur.kwhSignal = kwh;
            if (!Double.isNaN(power) && power > 0
                    && (Double.isNaN(cur.maxPowerKw) || power > cur.maxPowerKw)) cur.maxPowerKw = power;
            if (cur.gun == 0 && gunIn) cur.gun = gun;
            if (nowMs - lastPointMs >= POINT_MS || (!Double.isNaN(soc) && soc != lastPointSoc)) {
                lastPointMs = nowMs;
                lastPointSoc = soc;
                pending.add(new double[]{wall, soc, power});
            }
            if (nowMs - lastSaveMs >= SAVE_MS) save();
            status = L.t("Charging") + (cur.typeName().isEmpty() ? "" : " (" + cur.typeName() + ")")
                    + (bms == BMS_PAUSED ? L.t(" - paused") : "");
        } else {
            status = "";
            if (known) sawIdleBefore = true;
            if (cur != null) {
                if (inactiveSinceMs == 0) {
                    inactiveSinceMs = nowMs;
                    firstInactiveWall = wall;
                }
                if (nowMs - inactiveSinceMs >= END_DEBOUNCE_MS) end(soc, wall);
            }
        }
    }

    /** Remembers the latest battery level (persisted) so a charge that happens while we sleep can be noticed. */
    private void noteSoc(double soc, long wall) {
        if (Double.isNaN(soc)) return;
        if (soc != lastNotedSoc || wall - lastNotedWall > 60_000) {
            lastNotedSoc = soc;
            lastNotedWall = wall;
            Prefs.setLastSoc(ctx, soc, wall);
        }
    }

    /**
     * Called once after the app starts or the unit wakes. If the battery is clearly higher than the last
     * level seen before the silence, the car was charged unseen (the head unit sleeps with the car off):
     * record that as a session that only knows the levels before and after.
     */
    private void checkMissedCharge(double soc, boolean active, boolean known, int bms, int gun, long wall) {
        double[] last = Prefs.lastSoc(ctx);
        if (last == null || cur != null) return;          // an open session covers its own gap
        long lastWall = (long) last[1];
        if (wall - lastWall <= GAP_MS) return;            // nothing was missed
        double gain = soc - last[0];
        boolean gunIn = gun == 2 || gun == 3 || gun == 4;
        boolean evidence = gunIn || (known && (bms == 1 || bms == 2 || bms == 4 || bms == 13));
        if (gain < (evidence ? MISSED_GAIN_WITH_EVIDENCE : MISSED_GAIN_NO_EVIDENCE)) return;

        if (active) {
            // still charging now: the normal start() picks this up, beginning at the old level
            missedStartSoc = last[0];
            missedStartWall = lastWall;
            return;
        }
        Charge prev = db.latestEndedCharge();
        if (continues(db, prev, last[0], lastWall)) {
            // the session that was running before the unit slept goes on: extend it instead of adding a second one
            prev.endMs = wall;
            prev.lastUpdateMs = wall;
            prev.socEnd = soc;
            prev.partial = true;
            if (prev.gun == 0 && gunIn) prev.gun = gun;
            db.upsertCharge(prev);
            db.addChargePoints(prev.id, java.util.Collections.singletonList(new double[]{wall, soc, Double.NaN}));
            Diag.log(String.format(Locale.US, "charge: the earlier session continues through the sleep, soc %.0f -> %.0f",
                    last[0], soc));
            if (Prefs.autoBackup(ctx)) Backup.writeCharge(ctx, prev);
            return;
        }
        Charge c = new Charge();
        c.id = UUID.randomUUID().toString();
        c.startMs = lastWall;
        c.endMs = wall;
        c.lastUpdateMs = wall;
        c.ended = true;
        c.socStart = last[0];
        c.socEnd = soc;
        c.gun = gunIn ? gun : 0;
        c.partial = true;
        c.windowOnly = true;
        Location l = gps != null ? gps.fresh(120_000) : null;
        if (l != null) {
            c.lat = l.getLatitude();
            c.lon = l.getLongitude();
        }
        db.upsertCharge(c);
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{lastWall, last[0], Double.NaN});
        pts.add(new double[]{wall, soc, Double.NaN});
        db.addChargePoints(c.id, pts);
        Diag.log(String.format(Locale.US, "charge: found a charge made while asleep, soc %.0f -> %.0f (%d min window)",
                last[0], soc, (wall - lastWall) / 60_000));
        finish(c);
    }

    private void start(int gun, double soc, long wall) {
        double startSoc = Double.isNaN(missedStartSoc) ? soc : missedStartSoc;
        long startWall = Double.isNaN(missedStartSoc) ? wall : missedStartWall;
        Charge prev = db.latestEndedCharge();
        if (continues(db, prev, startSoc, startWall)) {
            // charging picks up at the level the last session stopped at: carry on with that session
            cur = prev;
            cur.ended = false;
            cur.endMs = 0;
            cur.lastUpdateMs = wall;
            cur.socEnd = soc;
            if (!Double.isNaN(missedStartSoc) || wall - prev.endMs > GAP_MS) cur.partial = true;
            if (cur.gun == 0 && (gun == 2 || gun == 3 || gun == 4)) cur.gun = gun;
            missedStartSoc = Double.NaN;
            pending.clear();
            lastPointMs = 0;
            lastPointSoc = Double.NaN;
            lastSaveMs = 0;
            db.upsertCharge(cur);
            current = cur;
            Diag.log("charge: continues the session that stopped at " + Math.round(prev.socEnd) + "%");
            return;
        }
        cur = new Charge();
        cur.id = UUID.randomUUID().toString();
        cur.startMs = wall;
        cur.lastUpdateMs = wall;
        cur.socStart = soc;
        cur.socEnd = soc;
        cur.gun = gun == 2 || gun == 3 || gun == 4 ? gun : 0;
        // not seen idle first: charging was already under way when the app (or the unit) woke up
        cur.partial = !sawIdleBefore;
        if (!Double.isNaN(missedStartSoc)) {
            // charging began while the unit slept: count from the last level seen before that
            cur.startMs = missedStartWall;
            cur.socStart = missedStartSoc;
            cur.partial = true;
            cur.windowOnly = true;
            missedStartSoc = Double.NaN;
        }
        Location l = gps != null ? gps.fresh(120_000) : null;
        if (l != null) {
            cur.lat = l.getLatitude();
            cur.lon = l.getLongitude();
        }
        pending.clear();
        lastPointMs = 0;
        lastPointSoc = Double.NaN;
        lastSaveMs = 0;
        db.upsertCharge(cur);
        current = cur;
        Diag.log(String.format(Locale.US, "charge: started %s soc=%.0f gun=%d%s", cur.id, soc, gun,
                cur.partial ? " (already in progress, start not seen)" : ""));
    }

    private void save() {
        if (cur == null) return;
        try {
            lastSaveMs = android.os.SystemClock.elapsedRealtime();
            db.upsertCharge(cur);
            if (!pending.isEmpty()) {
                db.addChargePoints(cur.id, new ArrayList<>(pending));
                pending.clear();
            }
        } catch (Throwable t) {
            Diag.log("charge: save failed", t);
        }
    }

    /**
     * Closes the session. soc is the battery level now: if charging finished while nobody was looking
     * (unit asleep) this is the real final level, so it replaces the last level seen while charging.
     */
    private void end(double soc, long wall) {
        if (cur == null) return;
        Charge c = cur;
        boolean gap = firstInactiveWall - lastActiveWall > GAP_MS;
        if (gap) c.partial = true;
        if (!Double.isNaN(soc) && (Double.isNaN(c.socEnd) || soc >= c.socEnd - FINAL_SOC_DROP_OK)) {
            c.socEnd = soc;
        }
        c.ended = true;
        // with a gap the end is only known to be when it was first noticed; otherwise the last active sample
        c.endMs = gap && firstInactiveWall > 0 ? firstInactiveWall : lastActiveWall;
        if (c.endMs <= 0) c.endMs = wall;
        c.lastUpdateMs = c.endMs;
        pending.add(new double[]{c.endMs, c.socEnd, Double.NaN});
        save();
        cur = null;
        current = null;
        status = "";
        finish(c);
        Diag.log(String.format(Locale.US, "charge: ended %s %d s, soc %.0f -> %.0f%s", c.id,
                c.durationSec(), c.socStart, c.socEnd, c.partial ? " (partial)" : ""));
    }

    /** Keeps or discards a closed session and backs it up. */
    private void finish(Charge c) {
        double gained = c.socGained();
        boolean tiny = c.durationSec() < 120 && (Double.isNaN(gained) || gained < 1);
        if (tiny) {
            db.deleteCharge(c.id);
            Diag.log("charge: discarded (too short)");
            return;
        }
        db.upsertCharge(c);
        if (Prefs.autoBackup(ctx)) Backup.writeCharge(ctx, c);
    }

    /**
     * True if a session that begins at (startSoc, startMs) is the continuation of prev: prev has ended, the
     * battery level is the same (so nothing was driven or used), it begins no more than 12 h later, and no
     * trip was made in between.
     */
    static boolean continues(TripDb db, Charge prev, double startSoc, long startMs) {
        if (prev == null || !prev.ended || Double.isNaN(prev.socEnd) || Double.isNaN(startSoc)) return false;
        if (Math.abs(prev.socEnd - startSoc) > SAME_LEVEL_PCT) return false;
        long gap = startMs - prev.endMs;
        if (gap < -JOIN_START_SLACK_MS || gap > MAX_JOIN_GAP_MS) return false;
        return !db.hasTripBetween(prev.endMs, startMs);
    }

    /** Joins b into a (b follows a): a keeps its start, takes b's end and level, and b disappears. */
    static void join(TripDb db, Charge a, Charge b) {
        boolean gapBetween = b.startMs - a.endMs > GAP_MS;
        a.endMs = Math.max(a.endMs, b.endMs);
        a.lastUpdateMs = a.endMs;
        a.socEnd = b.socEnd;
        // the car's own kWh readings only add up if both sessions have one, else the energy is estimated
        a.kwhSignal = (a.kwhSignal > 0.05 && b.kwhSignal > 0.05) ? a.kwhSignal + b.kwhSignal : Double.NaN;
        if (!Double.isNaN(b.maxPowerKw) && (Double.isNaN(a.maxPowerKw) || b.maxPowerKw > a.maxPowerKw)) a.maxPowerKw = b.maxPowerKw;
        if (a.gun == 0) a.gun = b.gun;
        a.partial = a.partial || b.partial || gapBetween;
        a.windowOnly = a.windowOnly && b.windowOnly;
        if (Double.isNaN(a.lat)) {
            a.lat = b.lat;
            a.lon = b.lon;
        }
        db.moveChargePoints(b.id, a.id);
        db.upsertCharge(a);
        db.deleteCharge(b.id);   // also removes b's backup file
    }

    /**
     * One-time clean-up of sessions that were saved as two pieces (for example one made across a night
     * when the head unit slept). Safe to run again: it only joins pairs that follow the rule above.
     */
    static int repairSplits(Context ctx, TripDb db) {
        int joined = 0;
        try {
            java.util.List<Charge> all = db.charges();            // newest first
            java.util.Collections.reverse(all);                    // oldest first
            for (int i = 0; i + 1 < all.size(); ) {
                Charge a = all.get(i), b = all.get(i + 1);
                if (a.ended && b.ended && continues(db, a, b.socStart, b.startMs)) {
                    join(db, a, b);
                    all.remove(i + 1);                              // a may now continue into the next one too
                    joined++;
                    if (Prefs.autoBackup(ctx)) Backup.writeCharge(ctx, a);
                    Diag.log(String.format(Locale.US, "charge: joined two pieces into one session %.0f -> %.0f",
                            a.socStart, a.socEnd));
                } else {
                    i++;
                }
            }
        } catch (Throwable t) {
            Diag.log("charge: repair failed", t);
        }
        return joined;
    }

    /** Saves what is pending when the service stops (the session itself stays open). */
    void flush() {
        save();
    }
}
