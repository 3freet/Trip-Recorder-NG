package org.triprecorderng;

import android.content.Context;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small system tweaks that need the shell user's rights, run through the car's loopback debugging
 * (the same route that starts the data-link helper): keep mobile data roaming on, ask the car to connect
 * to BYD's cloud, and turn the car's external EV sound (AVAS) off. Reading the roaming setting needs no special
 * right, only changing it does.
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

    /** What the AVAS tool does, for the note on the Tweaks screen. */
    static final String AVAS_CALL = "BYDAutoEngineDevice.setEngineVoiceSimulatorState(0)";

    private static final long CHECK_EVERY_MS = 60_000;
    private static final long RETRY_AFTER_FAILURE_MS = 30_000;

    /** Where the AVAS tool writes what it did: the shell user's own scratch folder. */
    private static final String AVAS_OUT = "/data/local/tmp/triprecng-avas.out";
    /** The car can bring AVAS back on a little after it starts, so a start-up run keeps checking this long. */
    private static final int AVAS_KEEP_SECONDS = 90;
    private static final long AVAS_RETRY_MS = 30_000;
    private static final long AVAS_START_GAP_MS = 60_000;
    private static final int AVAS_MAX_TRIES = 6;

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

    private static boolean avasBusy;
    private static boolean avasPending;
    private static int avasTries;
    private static long avasLastAttemptMs;
    private static long avasStartMs;
    private static volatile String avasResult = "";

    /** One line for the Tweaks screen: what the button does until it has been used, then how it went. */
    static String avasText() {
        return avasResult.isEmpty()
                ? L.t("Turns the car's external EV sound (AVAS) off, the same switch as in the control center")
                : avasResult;
    }

    /**
     * Starts the AVAS tool as the shell user and prints what it did once its first attempt is done.
     * keepSeconds = 0 is a single attempt; more keeps checking in the background for that long.
     */
    private static String avasCommand(Context ctx, int keepSeconds) {
        return "P=$(pidof triprecng_avas); [ -n \"$P\" ] && kill -9 $P; rm -f " + AVAS_OUT + "; "
                + "(CLASSPATH=" + ctx.getApplicationInfo().sourceDir + " nohup app_process /system/bin "
                + "--nice-name=triprecng_avas org.triprecorderng.AvasTool " + keepSeconds
                + " > " + AVAS_OUT + " 2>&1 < /dev/null &); "
                + "i=0; while [ $i -lt 30 ] && ! grep -q '^result=' " + AVAS_OUT + " 2>/dev/null; "
                + "do sleep 0.5; i=$((i+1)); done; cat " + AVAS_OUT;
    }

    private static String avasWord(String output) {
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("result=")) return line.substring(7);
        }
        return "";
    }

    /** Turns AVAS off now (a button press). Never blocks the caller. */
    static void avasOff(Context ctx) {
        runAvas(ctx, 0, "button");
    }

    /**
     * The car brings AVAS back on every time it starts. When the user switched "Keep AVAS off" on, a start (the
     * service starting, or the ignition coming on) turns it off again and keeps checking for a short while. If
     * the car's debugging is not ready yet, the periodic ensureAvas() tries again.
     */
    static void avasStart(Context ctx, String why, boolean force) {
        if (!Prefs.keepAvasOff(ctx)) return;
        long now = System.currentTimeMillis();
        synchronized (Tweaks.class) {
            if (!force && now - avasStartMs < AVAS_START_GAP_MS) return;
            avasStartMs = now;
            avasPending = true;
            avasTries = 0;
        }
        runAvas(ctx, AVAS_KEEP_SECONDS, why);
    }

    /** Re-tries a start-up that could not be applied yet. Cheap when nothing is waiting. */
    static void ensureAvas(Context ctx, String why) {
        if (!avasPending || !Prefs.keepAvasOff(ctx)) return;
        runAvas(ctx, AVAS_KEEP_SECONDS, why);
    }

    private static void runAvas(final Context ctx, final int keepSeconds, final String why) {
        final Context app = ctx.getApplicationContext();
        synchronized (Tweaks.class) {
            if (avasBusy) return;
            long now = System.currentTimeMillis();
            if (keepSeconds > 0) {
                // a start-up run: only while one is waiting, a few tries, 30 s apart
                if (!avasPending || avasTries >= AVAS_MAX_TRIES) return;
                if (avasTries > 0 && now - avasLastAttemptMs < AVAS_RETRY_MS) return;
                avasTries++;
            }
            avasBusy = true;
            avasLastAttemptMs = now;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String when = new SimpleDateFormat("HH:mm", L.dateLocale()).format(new Date());
                boolean done = false;
                try {
                    AdbLoopback.Result r = new AdbLoopback(app).runShell(avasCommand(app, keepSeconds), 30_000);
                    String word = r.status == AdbLoopback.Status.OK ? avasWord(r.output) : "";
                    if (word.equals("turned_off")) {
                        avasResult = L.f("turned off at %s", when);
                        done = true;
                    } else if (word.equals("already_off")) {
                        avasResult = L.f("already off at %s", when);
                        done = true;
                    } else if (word.equals("unavailable")) {
                        avasResult = L.f("failed at %1$s: %2$s", when, L.t("not available on this car"));
                        done = true; // nothing to retry
                    } else if (word.equals("still_on")) {
                        avasResult = L.f("failed at %1$s: %2$s", when, L.t("the car kept it on"));
                    } else if (r.status != AdbLoopback.Status.OK) {
                        avasResult = L.f("failed at %1$s: %2$s", when,
                                r.status.name().toLowerCase(Locale.US).replace('_', ' '));
                    } else {
                        avasResult = L.f("failed at %s", when);
                    }
                    Diag.log("tweak avas (" + why + "): " + r.status + " " + r.detail + " | "
                            + r.output.trim().replace('\n', ' '));
                } catch (Throwable e) {
                    avasResult = L.f("failed at %s", when);
                    Diag.log("tweak avas failed", e);
                } finally {
                    synchronized (Tweaks.class) {
                        avasBusy = false;
                        if (done) avasPending = false;
                    }
                }
            }
        }, "tweak-avas");
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
