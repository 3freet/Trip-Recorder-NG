package org.triprecorderng;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Foreground service that records trips the way the stock My Car app does:
 *  - a trip is one ignition cycle (power level ON ... OFF); if the ignition level cannot be read,
 *    a trip starts when the car moves and ends after a few minutes standing still;
 *  - at the start it takes 7 baseline samples of the cumulative counters (median);
 *  - speed is sampled several times a second and integrated into distance;
 *  - the trip row is rewritten every 30 seconds and once more at the end.
 * It never writes anything to the vehicle: it only reads.
 */
public class RecorderService extends Service {
    // ---- timing and thresholds -------------------------------------------------------------
    private static final long TICK_MS = 250;
    private static final long POWER_POLL_MS = 1000;
    private static final long SLOW_POLL_MS = 5000;
    private static final long SAVE_MOVING_MS = 30_000;
    private static final long SAVE_IDLE_MS = 10_000;
    private static final long POINT_MS = 3000;
    /** While standing still a point is still stored (speed 0, battery, position), just less often. */
    private static final long IDLE_POINT_MS = 10_000;
    private static final double MOTION_START_KMH = 5.0;
    private static final long MOTION_START_HOLD_MS = 3000;
    private static final double MOVING_KMH = 1.0;
    private static final int BASELINE_SAMPLES = 7;
    private static final double MIN_KEEP_KM = 0.05;
    private static final long RESUME_WINDOW_MS = 10 * 60_000;
    // approximate "harsh" thresholds (the stock app downloads its own from BYD's server)
    private static final double HARD_ACCEL_MS2 = 2.8;
    private static final double HARD_BRAKE_MS2 = -3.3;
    private static final long EVENT_DEBOUNCE_MS = 2000;

    private static final String CHANNEL = "recorder";
    private static final int NOTIF_ID = 1;

    /** Snapshot read by the UI. */
    static final class Live {
        volatile boolean running;
        volatile long startedWall;
        volatile long lastTickWall;
        volatile String vehicle = "none";
        volatile String error = "";
        volatile double speed = Double.NaN;
        volatile double power = Double.NaN;
        volatile double soc = Double.NaN;
        volatile double elec = Double.NaN;
        volatile double odo = Double.NaN;
        volatile double fuelPct = Double.NaN;
        volatile double elecRange = Double.NaN;
        volatile double fuelRange = Double.NaN;
        volatile boolean tripActive;
        volatile double tripKm;
        volatile long tripSec;
        volatile boolean gps;
        volatile boolean gpsFix;
    }

    static final Live live = new Live();
    /** The trip being recorded right now (read-only for the UI), or null. */
    static volatile Trip current;
    static final VehicleProviderHolder holder = new VehicleProviderHolder();

    static final class VehicleProviderHolder {
        volatile VehicleProvider provider;
    }

    private HandlerThread thread;
    private Handler handler;
    private VehicleProvider provider;
    private Gps gps;
    private TripDb db;
    private ChargeRecorder charges;
    private NotificationManager nm;

    // ---- trip state ------------------------------------------------------------------------
    private Trip trip;
    private int baselineLeft;
    private final List<Double> bOdo = new ArrayList<>();
    private final List<Double> bElec = new ArrayList<>();
    private final List<Double> bSoc = new ArrayList<>();
    private final List<Double> bFuel = new ArrayList<>();
    private final List<Double> bEv = new ArrayList<>();
    private final List<Double> bHev = new ArrayList<>();
    private final List<double[]> pending = new ArrayList<>();
    private final List<double[]> pendingEvents = new ArrayList<>();
    private final ArrayDeque<double[]> speedHist = new ArrayDeque<>(); // {elapsedMs, kmh}

    private long lastTickMs;
    private long lastPowerPollMs;
    private long lastSlowPollMs;
    private long lastSaveMs;
    private long lastPointMs;
    private long lastOnMs;
    private long lastMoveMs;
    private long lastAccelEvt;
    private long lastBrakeEvt;
    private long lastNotifMs;
    private long lastLiveMs;
    private double lastAlt;
    private double lastBearing;
    private boolean ignitionWasKnown = true;
    private boolean ignitionWasOn;
    private long lastDistLogMs;
    private long lastDataMs;
    private long motionSinceMs;
    private long serviceStartMs;
    private double power = Double.NaN;

    @Override public void onCreate() {
        super.onCreate();
        Diag.init(this);
        Diag.log("service onCreate");
        serviceStartMs = SystemClock.elapsedRealtime();
        Prefs.noteRun(this);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, L.t("Trip recording"), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, buildNotification(L.t("Starting...")));
        if (!Prefs.isMainUser()) {
            // BYD runs a second Android user for its other display; recording there would only duplicate work
            Diag.log("service not needed in secondary user " + android.os.Process.myUid() / 100000);
            stopForeground(true);
            stopSelf();
            return;
        }

        db = TripDb.get(this);
        gps = new Gps(this);
        charges = new ChargeRecorder(this, db, gps);
        provider = new VehicleProvider(this, gps);
        holder.provider = provider;
        thread = new HandlerThread("trip-recorder");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            gps.start(thread.getLooper());
            provider.probe();
            resumeOrCloseOpenTrip();
            charges.resume();
            ChargeRecorder.repairSplits(this, db);
            Tweaks.ensureRoaming(this, true, "service start");
            Tweaks.avasStart(this, "service start", false);
            lastTickMs = SystemClock.elapsedRealtime();
            handler.postDelayed(tick, TICK_MS);
            if (Prefs.autoBackup(this)) {
                final Context app = getApplicationContext();
                Thread bt = new Thread(() -> {
                    Backup.migrateOldFolders(app);
                    Backup.writeMissing(app);
                }, "backup-catchup");
                bt.setDaemon(true);
                bt.start();
            }
        });

        registerReceiver(shutdownReceiver, new IntentFilter(Intent.ACTION_SHUTDOWN));
        live.running = true;
        live.startedWall = System.currentTimeMillis();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.isMainUser()) return START_NOT_STICKY;
        if (gps != null && handler != null) handler.post(() -> gps.start(thread.getLooper()));
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        Diag.log("service onDestroy");
        live.running = false;
        if (!Prefs.isMainUser()) {
            super.onDestroy();
            return;
        }
        try {
            unregisterReceiver(shutdownReceiver);
        } catch (Throwable ignored) {
            // not registered
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler.post(() -> {
                if (trip != null) saveTrip(true);
                if (charges != null) charges.flush();
                gps.stop();
            });
        }
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private final class ShutdownReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            onShutdownBroadcast();
        }
    }

    private final BroadcastReceiver shutdownReceiver = new ShutdownReceiver();

    private void onShutdownBroadcast() {
        Diag.log("shutdown broadcast");
        if (handler == null) return;
        handler.post(() -> {
            if (trip != null) endTrip("shutdown");
        });
    }

    // ---- main loop -------------------------------------------------------------------------
    private final class Tick implements Runnable {
        @Override public void run() {
            try {
                step();
            } catch (Throwable t) {
                Diag.log("tick error", t);
            }
            if (handler != null) handler.postDelayed(this, TICK_MS);
        }
    }

    private final Runnable tick = new Tick();

    private void step() {
        long now = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        double dt = Math.min(2.0, (now - lastTickMs) / 1000.0);
        lastTickMs = now;

        Vehicle v = provider.get();
        if (Prefs.linkEnabled(this) && !(v instanceof HelperVehicle)) {
            HelperLauncher.ensureAsync(this, false); // throttled by its own back-off
        }
        Tweaks.ensureRoaming(this, false, "periodic check");
        Tweaks.ensureAvas(this, "periodic check");
        live.lastTickWall = wall;
        live.gps = gps.isStarted();
        live.gpsFix = gps.fresh(5000) != null;
        if (v == null) {
            live.vehicle = "none";
            live.error = "no vehicle data path (" + provider.report().replace('\n', ' ') + ")";
            maybeNotify(now, L.t("No vehicle data"));
            return;
        }
        live.vehicle = v.name();
        live.error = v.lastError();
        charges.update(v, wall, now);
        if (now - lastLiveMs >= 5000) {
            lastLiveMs = now;
            live.fuelPct = v.fuelPercent();
            live.elecRange = v.elecRangeKm();
            live.fuelRange = v.fuelRangeKm();
        }

        double speed = v.speedKmh();
        live.speed = speed;
        if (!Double.isNaN(speed)) lastDataMs = now;

        if (now - lastPowerPollMs >= POWER_POLL_MS) {
            power = v.powerLevel();
            lastPowerPollMs = now;
            live.power = power;
        }
        boolean ignitionKnown = !Double.isNaN(power) && power != 255;
        boolean ignitionOn = ignitionKnown && (power == 2 || power == 3 || power == 4);
        if (ignitionOn && !ignitionWasOn) Tweaks.avasStart(this, "ignition on", false);
        if (ignitionKnown) ignitionWasOn = ignitionOn;
        boolean moving = !Double.isNaN(speed) && speed >= MOVING_KMH;

        if (trip == null) {
            // right after the service starts, give the data link a moment so the trip's baseline
            // (battery, energy, odometer) is taken from BYD data rather than missing
            if (Prefs.linkEnabled(this) && !(v instanceof HelperVehicle)
                    && now - serviceStartMs < 25_000) {
                maybeNotify(now, L.t("Connecting to the car's data..."));
                return;
            }
            boolean start;
            if (ignitionKnown) {
                start = ignitionOn;
            } else {
                // movement must be real: at least 5 km/h for 3 seconds in a row
                if (!Double.isNaN(speed) && speed >= MOTION_START_KMH) {
                    if (motionSinceMs == 0) motionSinceMs = now;
                } else {
                    motionSinceMs = 0;
                }
                start = motionSinceMs != 0 && now - motionSinceMs >= MOTION_START_HOLD_MS;
            }
            if (start) motionSinceMs = 0;
            if (start) startTrip(v, ignitionKnown ? "ignition" : "motion", now, wall);
            else maybeNotify(now, ignitionKnown ? L.t("Waiting for ignition") : L.t("Waiting for movement"));
            return;
        }

        // ---- running trip ----
        trip.lastUpdateMs = wall;
        if (ignitionOn) lastOnMs = now;
        if (moving) lastMoveMs = now;

        if (!Double.isNaN(speed)) {
            integrate(speed, dt, now);
            detectEvents(speed, now);
        }
        updateJourney(v, wall);

        if (baselineLeft > 0) {
            takeBaselineSample(v);
        } else if (now - lastSlowPollMs >= SLOW_POLL_MS) {
            lastSlowPollMs = now;
            refreshEnd(v);
        }

        if (!Double.isNaN(speed) && now - lastPointMs >= (moving ? POINT_MS : IDLE_POINT_MS)) {
            lastPointMs = now;
            recordPoint(wall, speed, moving);
        }

        long saveEvery = moving ? SAVE_MOVING_MS : SAVE_IDLE_MS;
        if (now - lastSaveMs >= saveEvery) saveTrip(false);
        if (moving && now - lastDistLogMs >= 300_000) {
            lastDistLogMs = now;
            Diag.log(String.format(java.util.Locale.US, "distance: shown %.2f km, speed integral %.2f, car counter %s",
                    trip.distanceKm, trip.speedKm, Double.isNaN(trip.journeyAcc) ? "n/a"
                    : String.format(java.util.Locale.US, "%.2f", trip.journeyAcc)));
        }

        live.tripActive = true;
        live.tripKm = trip.distanceKm;
        live.tripSec = trip.durationSec();
        maybeNotify(now, L.f("Recording: %.1f km", trip.distanceKm));

        // ---- end conditions ----
        boolean ignitionTrip = "ignition".equals(trip.mode);
        if (ignitionTrip && ignitionKnown != ignitionWasKnown) {
            ignitionWasKnown = ignitionKnown;
            Diag.log(ignitionKnown ? "ignition readable again, trip rules back to normal"
                    : "ignition not readable (data link down?): keeping the trip open");
        }
        TripEnd.Reason why = TripEnd.decide(ignitionTrip, ignitionKnown, ignitionOn, now, lastOnMs, lastMoveMs, lastDataMs);
        switch (why) {
            case NONE:
                break;
            case IGNITION_OFF:
                endTrip("ignition off");
                break;
            case IGNITION_UNREADABLE:
                // the time of the last sign of life, not now, is when the trip really ended
                endTrip("ignition unreadable for a long time", wall - (now - Math.max(lastOnMs, lastMoveMs)));
                break;
            case NO_DATA:
                endTrip("no speed data");
                break;
            default:
                endTrip("stopped");
        }
    }

    // ---- trip lifecycle --------------------------------------------------------------------
    private void startTrip(Vehicle v, String mode, long now, long wall) {
        trip = new Trip();
        trip.id = UUID.randomUUID().toString();
        trip.startMs = wall;
        trip.lastUpdateMs = wall;
        trip.mode = mode;
        baselineLeft = BASELINE_SAMPLES;
        bOdo.clear();
        bElec.clear();
        bSoc.clear();
        bFuel.clear();
        bEv.clear();
        bHev.clear();
        pending.clear();
        pendingEvents.clear();
        speedHist.clear();
        lastOnMs = now;
        lastMoveMs = now;
        lastDataMs = now;
        lastSaveMs = now;
        lastSlowPollMs = now;
        lastPointMs = 0;
        Location l = Prefs.recordRoute(this) ? gps.fresh(10_000) : null;
        if (l != null) {
            trip.startLat = l.getLatitude();
            trip.startLon = l.getLongitude();
        }
        db.upsert(trip);
        current = trip;
        Diag.log("trip started (" + mode + ") " + trip.id);
    }

    private void takeBaselineSample(Vehicle v) {
        bOdo.add(v.odometerKm());
        bElec.add(v.totalElecConsumption());
        bSoc.add(v.socPercent());
        bFuel.add(v.totalFuelLitres());
        bEv.add(v.evMileageKm());
        bHev.add(v.hevMileageKm());
        baselineLeft--;
        if (baselineLeft == 0) {
            trip.odoStartKm = median(bOdo);
            trip.elecStart = median(bElec);
            trip.socStart = median(bSoc);
            trip.fuelStart = median(bFuel);
            trip.evStart = median(bEv);
            trip.hevStart = median(bHev);
            trip.odoEndKm = trip.odoStartKm;
            trip.elecEnd = trip.elecStart;
            trip.socEnd = trip.socStart;
            trip.fuelEnd = trip.fuelStart;
            trip.evEnd = trip.evStart;
            trip.hevEnd = trip.hevStart;
            Diag.log("baseline: odo=" + trip.odoStartKm + " elec=" + trip.elecStart
                    + " soc=" + trip.socStart);
        }
    }

    /** Adds what the car's own trip counter says; this, not the speedometer integral, is the trip distance. */
    private void updateJourney(Vehicle v, long wall) {
        JourneyTracker.update(trip, v.journeyKm(), Prefs.speedScale(this), wall);
        trip.distanceKm = JourneyTracker.distance(trip);
    }

    private void refreshEnd(Vehicle v) {
        updateJourney(v, System.currentTimeMillis());
        double odo = v.odometerKm();
        double elec = v.totalElecConsumption();
        double soc = v.socPercent();
        if (!Double.isNaN(odo)) {
            if (Double.isNaN(trip.odoStartKm)) trip.odoStartKm = odo; // data link came up late
            trip.odoEndKm = odo;
        }
        if (!Double.isNaN(elec)) {
            if (Double.isNaN(trip.elecStart)) trip.elecStart = elec;
            trip.elecEnd = elec;
        }
        if (!Double.isNaN(soc)) {
            if (Double.isNaN(trip.socStart)) trip.socStart = soc;
            trip.socEnd = soc;
        }
        double fuel = v.totalFuelLitres();
        double ev = v.evMileageKm();
        double hev = v.hevMileageKm();
        if (!Double.isNaN(fuel)) {
            if (Double.isNaN(trip.fuelStart)) trip.fuelStart = fuel;
            trip.fuelEnd = fuel;
        }
        if (!Double.isNaN(ev)) {
            if (Double.isNaN(trip.evStart)) trip.evStart = ev;
            trip.evEnd = ev;
        }
        if (!Double.isNaN(hev)) {
            if (Double.isNaN(trip.hevStart)) trip.hevStart = hev;
            trip.hevEnd = hev;
        }
        live.odo = odo;
        live.elec = elec;
        live.soc = soc;
    }

    private void integrate(double speed, double dt, long now) {
        trip.speedKm += speed * dt / 3600.0;
        trip.distanceKm = JourneyTracker.distance(trip);
        if (speed >= MOVING_KMH) trip.movingSec += dt; else trip.idleSec += dt;
        if (speed > trip.maxSpeedKmh) trip.maxSpeedKmh = speed;
        speedHist.addLast(new double[]{now, speed});
        while (speedHist.size() > 2 && now - speedHist.peekFirst()[0] > 2000) speedHist.pollFirst();
    }

    private void detectEvents(double speed, long now) {
        double[] ref = null;
        for (double[] s : speedHist) {
            if (now - s[0] >= 900) ref = s; else break;
        }
        if (ref == null) return;
        double dtS = (now - ref[0]) / 1000.0;
        if (dtS <= 0) return;
        double a = (speed - ref[1]) / 3.6 / dtS;
        if (a >= HARD_ACCEL_MS2 && ref[1] >= 1 && now - lastAccelEvt > EVENT_DEBOUNCE_MS) {
            trip.hardAccel++;
            lastAccelEvt = now;
            recordEvent(0, a);
        } else if (a <= HARD_BRAKE_MS2 && ref[1] >= 10 && now - lastBrakeEvt > EVENT_DEBOUNCE_MS) {
            trip.hardBrake++;
            lastBrakeEvt = now;
            recordEvent(1, a);
        }
    }

    /** Remembers where a hard acceleration (type 0) or braking (type 1) happened, for the trip map. */
    private void recordEvent(int type, double accel) {
        Location l = Prefs.recordRoute(this) ? gps.fresh(5000) : null;
        pendingEvents.add(new double[]{System.currentTimeMillis(), type,
                l != null ? l.getLatitude() : Double.NaN, l != null ? l.getLongitude() : Double.NaN,
                accel});
    }

    private void recordPoint(long wall, double carSpeed, boolean moving) {
        if (!Prefs.recordRoute(this)) return; // the user switched the route (GPS track) off
        Location l = gps.fresh(5000);
        double lat, lon, alt, gpsKmh, bearing;
        if (l != null) {
            lat = l.getLatitude();
            lon = l.getLongitude();
            alt = l.hasAltitude() ? l.getAltitude() : 0;
            gpsKmh = l.hasSpeed() ? l.getSpeed() * 3.6 : 0;
            bearing = l.hasBearing() ? l.getBearing() : 0;
            lastAlt = alt;
            lastBearing = bearing;
        } else if (!moving && !Double.isNaN(trip.endLat)) {
            // parked without a GPS fix (garage): the car has not moved, so the last position still holds
            lat = trip.endLat;
            lon = trip.endLon;
            alt = lastAlt;
            gpsKmh = 0;
            bearing = lastBearing;
        } else {
            return;
        }
        if (Double.isNaN(trip.startLat)) {
            trip.startLat = lat;
            trip.startLon = lon;
        }
        trip.endLat = lat;
        trip.endLon = lon;
        pending.add(new double[]{wall, lat, lon, alt, gpsKmh, carSpeed, bearing, trip.socEnd, trip.elecEnd});
    }

    private void saveTrip(boolean final_) {
        if (trip == null) return;
        try {
            lastSaveMs = SystemClock.elapsedRealtime();
            db.upsert(trip);
            if (!pending.isEmpty()) {
                db.addPoints(trip.id, new ArrayList<>(pending));
                pending.clear();
            }
            if (!pendingEvents.isEmpty()) {
                db.addEvents(trip.id, new ArrayList<>(pendingEvents));
                pendingEvents.clear();
            }
        } catch (Throwable t) {
            Diag.log("save failed", t);
        }
    }

    private void endTrip(String reason) {
        endTrip(reason, 0);
    }

    /** endWall > 0 backdates the end of the trip to that time (wall clock, ms). */
    private void endTrip(String reason, long endWall) {
        if (trip == null) return;
        Vehicle v = provider.get();
        if (v != null) refreshEnd(v);
        long wall = endWall > trip.startMs ? endWall : System.currentTimeMillis();
        trip.ended = true;
        trip.endMs = wall;
        trip.lastUpdateMs = wall;
        ignitionWasKnown = true;
        saveTrip(true);
        double observed = JourneyTracker.observedScale(trip);
        if (!Double.isNaN(observed)) {
            double next = 0.7 * Prefs.speedScale(this) + 0.3 * observed;
            Prefs.setSpeedScale(this, next);
            Diag.log(String.format(java.util.Locale.US, "speed scale: trip %.3f -> stored %.3f", observed, next));
        }
        Diag.log(String.format(java.util.Locale.US,
                "trip ended (%s): %.2f km (speed integral %.2f, car counter %s), %d s, max %.0f km/h", reason,
                trip.distanceKm, trip.speedKm,
                Double.isNaN(trip.journeyAcc) ? "n/a" : String.format(java.util.Locale.US, "%.1f", trip.journeyAcc),
                trip.durationSec(), trip.maxSpeedKmh));
        if (trip.distanceKm < MIN_KEEP_KM) {
            db.delete(trip.id);
            Diag.log("trip discarded (too short)");
        } else if (Prefs.autoBackup(this)) {
            Backup.write(this, trip);
        }
        trip = null;
        current = null;
        baselineLeft = 0;
        live.tripActive = false;
        live.tripKm = 0;
        live.tripSec = 0;
    }

    /** After a service restart or reboot: continue a recent open trip, or close a stale one. */
    private void resumeOrCloseOpenTrip() {
        Trip open = db.lastOpen();
        if (open == null) return;
        long wall = System.currentTimeMillis();
        if (wall - open.lastUpdateMs <= RESUME_WINDOW_MS) {
            trip = open;
            current = open;
            baselineLeft = 0;
            if (Double.isNaN(trip.speedKm) || trip.speedKm == 0) trip.speedKm = trip.distanceKm;
            long now = SystemClock.elapsedRealtime();
            lastOnMs = now;
            lastMoveMs = now;
            lastDataMs = now;
            lastSaveMs = now;
            lastSlowPollMs = now;
            Diag.log("resumed open trip " + open.id);
        } else {
            open.ended = true;
            open.endMs = open.lastUpdateMs;
            if (open.distanceKm < MIN_KEEP_KM) db.delete(open.id); else db.upsert(open);
            Diag.log("closed stale trip " + open.id);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------
    private static double median(List<Double> in) {
        List<Double> v = new ArrayList<>();
        for (Double d : in) if (d != null && !Double.isNaN(d)) v.add(d);
        if (v.isEmpty()) return Double.NaN;
        Collections.sort(v);
        return v.get((v.size() - 1) / 2);
    }

    private void maybeNotify(long now, String text) {
        if (now - lastNotifMs < 30_000) return;
        lastNotifMs = now;
        try {
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Trip Recorder NG")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
