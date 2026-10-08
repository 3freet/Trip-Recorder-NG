package org.triprecorderng;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Starts (and re-starts after a boot) the shell-user helper process by asking the head unit's own
 * network-debugging daemon on 127.0.0.1 to run it. Switched on/off by the user in Setting.
 *
 * Attempts are retried with a short back-off, a button press is never dropped (it is queued if an
 * attempt is already running), and the status text is derived from what is really connected.
 */
final class HelperLauncher {
    enum State { NOT_STARTED, STARTING, WAITING_FOR_APPROVAL, RUNNING, FAILED }

    private static final int[] BACKOFF_SECONDS = {3, 5, 8, 13, 20, 30, 45, 60};

    private static volatile State state = State.NOT_STARTED;
    private static volatile String detail = "";
    private static volatile long nextAttemptMs;
    private static int failures;
    private static boolean busy;
    private static boolean pendingForce;

    private HelperLauncher() {}

    static State state() { return state; }

    /** One line for the Setting screen, based on what is actually connected. */
    static String statusText(Context ctx) {
        if (!Prefs.linkEnabled(ctx)) return L.t("Off");
        if (isConnected()) return L.t("Connected - helper running");
        return pendingText();
    }

    /** True when the recorder is really getting data from the helper. */
    static boolean isConnected() {
        return "BYD API via helper".equals(RecorderService.live.vehicle);
    }

    private static String pendingText() {
        String base;
        switch (state) {
            case STARTING:
                return L.t("Connecting...");
            case WAITING_FOR_APPROVAL:
                base = L.t("Waiting for the car to accept the request (tap Allow on the car screen if it asks)");
                break;
            case FAILED:
                base = L.t("Not connected - ") + detail;
                break;
            default:
                base = L.t("Connecting soon...");
        }
        long wait = nextAttemptMs - System.currentTimeMillis();
        if (wait > 1000) base += L.f(" (retrying in %d s)", wait / 1000);
        return base;
    }

    static synchronized String token(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("triprecng", Context.MODE_PRIVATE);
        String t = p.getString("helper_token", null);
        if (t == null) {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            p.edit().putString("helper_token", t).apply();
        }
        return t;
    }

    /** Switches the link on and starts connecting right away. */
    static void enable(Context ctx) {
        Prefs.setLinkEnabled(ctx, true);
        resetBackoff();
        state = State.STARTING;
        detail = L.t("starting");
        Diag.log("vehicle data link enabled");
        ensureAsync(ctx, true);
    }

    /** Switches the link off, tells the helper process to stop and falls back to GPS only. */
    static void disable(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Prefs.setLinkEnabled(app, false);
        resetBackoff();
        state = State.NOT_STARTED;
        detail = L.t("turned off");
        Diag.log("vehicle data link disabled");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                HelperVehicle hv = new HelperVehicle(token(app));
                if (hv.connect() == null) hv.requestExit();
                hv.close();
            }
        }, "helper-stop");
        t.setDaemon(true);
        t.start();
    }

    /** Deletes the app's debugging key so it can no longer connect without a fresh approval. */
    static void forgetKey(Context ctx) {
        AdbKey.delete(ctx);
    }

    private static synchronized void resetBackoff() {
        failures = 0;
        nextAttemptMs = 0;
    }

    /**
     * Starts the helper in the background. Normal calls respect the back-off; force=true (a button
     * press) always runs, queued behind a running attempt if there is one.
     */
    static void ensureAsync(final Context ctx, boolean force) {
        final Context app = ctx.getApplicationContext();
        if (!Prefs.linkEnabled(app)) return;
        synchronized (HelperLauncher.class) {
            if (busy) {
                if (force) pendingForce = true;
                return;
            }
            long now = System.currentTimeMillis();
            if (!force && now < nextAttemptMs) return;
            busy = true;
            if (state != State.RUNNING) state = State.STARTING;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    start(app);
                } catch (Throwable e) {
                    state = State.FAILED;
                    detail = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Diag.log("helper start failed", e);
                } finally {
                    boolean again;
                    synchronized (HelperLauncher.class) {
                        noteResult();
                        busy = false;
                        again = pendingForce;
                        pendingForce = false;
                    }
                    nudgeProvider();
                    if (state == State.RUNNING) Tweaks.ensureRoaming(app, true, "helper started");
                    if (again) ensureAsync(app, true);
                }
            }
        }, "helper-launcher");
        t.setDaemon(true);
        t.start();
    }

    /** Schedules the next automatic attempt: soon after a failure, never after a success. */
    private static void noteResult() {
        if (state == State.RUNNING) {
            failures = 0;
            nextAttemptMs = 0;
        } else {
            int i = Math.min(failures, BACKOFF_SECONDS.length - 1);
            failures++;
            nextAttemptMs = System.currentTimeMillis() + BACKOFF_SECONDS[i] * 1000L;
        }
    }

    /** Lets the recorder switch to the helper right away instead of at its next periodic check. */
    private static void nudgeProvider() {
        VehicleProvider p = RecorderService.holder.provider;
        if (p != null) p.requestProbe();
    }

    private static void start(Context ctx) {
        state = State.STARTING;
        String token = token(ctx);

        HelperVehicle probe = new HelperVehicle(token);
        String err = probe.connect();
        probe.close();
        if (err == null) {
            state = State.RUNNING;
            detail = L.t("helper already running");
            return;
        }

        String apk = ctx.getApplicationInfo().sourceDir;
        String cmd = "P=$(pidof triprec_helper triprecng_helper); [ -n \"$P\" ] && kill -9 $P; "
                + "(CLASSPATH=" + apk + " nohup app_process /system/bin --nice-name=triprecng_helper "
                + "org.triprecorderng.Helper " + HelperVehicle.PORT + " " + token
                + " > /data/local/tmp/triprecng-helper.out 2>&1 < /dev/null &); "
                + "sleep 2; echo pid=$(pidof triprecng_helper); head -5 /data/local/tmp/triprecng-helper.out";
        AdbLoopback.Result r = new AdbLoopback(ctx).runShell(cmd, 120_000);
        Diag.log("helper launch: " + r.status + " " + r.detail + " | " + r.output.replace('\n', ' '));

        switch (r.status) {
            case UNREACHABLE:
                state = State.FAILED;
                detail = L.t("the car's network debugging (ADB) is not ready yet");
                return;
            case WAITING_FOR_APPROVAL:
                state = State.WAITING_FOR_APPROVAL;
                detail = L.t("approval pending");
                return;
            case REFUSED:
                state = State.WAITING_FOR_APPROVAL;
                detail = L.t("debugging request was not accepted yet");
                return;
            case ERROR:
                state = State.FAILED;
                detail = r.detail;
                return;
            default:
                break;
        }

        // the helper needs a moment to open its socket: poll briefly instead of one fixed wait
        String err2 = "helper did not answer";
        for (int i = 0; i < 6; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                // keep polling
            }
            HelperVehicle check = new HelperVehicle(token);
            err2 = check.connect();
            check.close();
            if (err2 == null) break;
        }
        if (err2 == null) {
            state = State.RUNNING;
            detail = L.t("helper started");
        } else {
            state = State.FAILED;
            detail = err2 + " | " + r.output.replace('\n', ' ');
        }
    }
}
