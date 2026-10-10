package org.triprecorderng;

import android.content.Context;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small system tweaks that need the shell user's rights, run through the car's loopback debugging
 * (the same route that starts the data-link helper): keep mobile data roaming on, and ask the car to connect
 * to BYD's cloud. Reading the roaming setting needs no special right, only changing it does.
 */
final class Tweaks {
    private Tweaks() {}

    /** The one command this tweak runs. */
    static final String ROAMING_COMMAND = "settings put global data_roaming 1";

    /**
     * Makes the car's cloud service connect to BYD's server over whatever network is available, whether or not
     * the car's own mobile connection (APN3) is up, so the BYD phone app can reach the car.
     */
    static final String CLOUD_COMMAND = "service call cloudmanager 1 i32 4";

    private static final long CHECK_EVERY_MS = 60_000;
    private static final long RETRY_AFTER_FAILURE_MS = 30_000;

    private static boolean busy;
    private static long lastAttemptMs;
    private static long lastCheckMs;
    private static volatile String lastResult = "";
    private static volatile boolean lastFailed;

    /** 1 = on, 0 = off, -1 = could not be read. */
    static int roamingState(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "data_roaming", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean cloudBusy;
    private static volatile String cloudResult = "";

    /** One line for the Tweaks screen: what the button does until it has been used, then how it went. */
    static String cloudText() {
        return cloudResult.isEmpty()
                ? L.t("Asks the car to connect to BYD's server over any network, for the BYD phone app")
                : cloudResult;
    }

    /** Runs the cloud command now (a button press). Never blocks the caller. */
    static void connectCloud(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        synchronized (Tweaks.class) {
            if (cloudBusy) return;
            cloudBusy = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String when = new SimpleDateFormat("HH:mm", L.dateLocale()).format(new Date());
                try {
                    AdbLoopback.Result r = new AdbLoopback(app).runShell(CLOUD_COMMAND, 20_000);
                    if (r.status == AdbLoopback.Status.OK) {
                        cloudResult = L.f("sent at %s", when);
                    } else {
                        cloudResult = L.f("failed at %1$s: %2$s", when,
                                r.status.name().toLowerCase(Locale.US).replace('_', ' '));
                    }
                    Diag.log("tweak cloud: " + r.status + " " + r.detail + " | " + r.output.trim());
                } catch (Throwable e) {
                    cloudResult = L.f("failed at %s", when);
                    Diag.log("tweak cloud failed", e);
                } finally {
                    synchronized (Tweaks.class) {
                        cloudBusy = false;
                    }
                }
            }
        }, "tweak-cloud");
        t.setDaemon(true);
        t.start();
    }

    static String roamingText(Context ctx) {
        int s = roamingState(ctx);
        String state = s == 1 ? L.t("On") : s == 0 ? L.t("Off") : L.t("Unknown");
        return state + (lastResult.isEmpty() ? "" : "   -   " + lastResult);
    }

    /** Runs the command now (a button press, or a re-apply). Never blocks the caller. */
    static void applyRoaming(final Context ctx, final String why) {
        final Context app = ctx.getApplicationContext();
        synchronized (Tweaks.class) {
            if (busy) return;
            busy = true;
            lastAttemptMs = System.currentTimeMillis();
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String when = new SimpleDateFormat("HH:mm", L.dateLocale()).format(new Date());
                try {
                    AdbLoopback.Result r = new AdbLoopback(app).runShell(ROAMING_COMMAND, 20_000);
                    int now = roamingState(app);
                    if (r.status == AdbLoopback.Status.OK) {
                        lastFailed = false;
                        lastResult = now == 1 ? L.f("turned on at %s", when)
                                : L.f("command sent at %1$s (the setting still reads %2$s)", when,
                                now == 0 ? L.t("off") : L.t("unknown"));
                    } else {
                        lastFailed = true;
                        lastResult = L.f("failed at %1$s: %2$s", when,
                                r.status.name().toLowerCase(Locale.US).replace('_', ' '));
                    }
                    Diag.log("tweak roaming (" + why + "): " + r.status + " " + r.detail + " | " + r.output.trim()
                            + " | now " + now);
                } catch (Throwable e) {
                    lastFailed = true;
                    lastResult = L.f("failed at %s", when);
                    Diag.log("tweak roaming failed", e);
                } finally {
                    synchronized (Tweaks.class) {
                        busy = false;
                    }
                }
            }
        }, "tweak-roaming");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Keeps roaming on when the user switched that on: applies it if it is off, at most once a
     * minute, and waits a little longer after a failed attempt. Cheap when it is already on.
     */
    static void ensureRoaming(Context ctx, boolean force, String why) {
        if (!Prefs.keepRoaming(ctx)) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastCheckMs < CHECK_EVERY_MS) return;
        lastCheckMs = now;
        if (roamingState(ctx) == 1) return;
        long wait = lastFailed ? RETRY_AFTER_FAILURE_MS : 0;
        if (!force && now - lastAttemptMs < wait) return;
        applyRoaming(ctx, why);
    }
}
