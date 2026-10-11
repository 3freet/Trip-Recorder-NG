package org.triprecorderng;

import android.content.Context;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small system tweaks that need the shell user's rights, run through the car's loopback debugging
 * (the same route that starts the data-link helper): keep mobile data roaming on, ask the car to connect
 * to BYD's cloud, turn the car's external EV sound (AVAS) off, and reboot the car's mobile modem. Reading the
 * roaming setting needs no special right, only changing it does.
 */
final class Tweaks {
    private Tweaks() {}

    /** The state of one tweak, read by the Tweaks screen. */
    static final class Action {
        static final int IDLE = 0;
        static final int BUSY = 1;
        static final int OK = 2;
        static final int FAILED = 3;

        volatile int state = IDLE;
        /** When it ran and how it went, or why it failed; empty when there is nothing to say. */
        volatile String detail = "";
    }

    static final Action ROAMING = new Action();
    static final Action CLOUD = new Action();
    static final Action AVAS = new Action();
    static final Action MODEM = new Action();

    /** The commands the roaming switch runs. */
    static final String ROAMING_COMMAND = "settings put global data_roaming 1";
    static final String ROAMING_OFF_COMMAND = "settings put global data_roaming 0";

    /**
     * Makes the car's cloud service connect to BYD's server over whatever network is available, whether or not
     * the car's own mobile connection (APN3) is up, so the BYD phone app can reach the car.
     */
    static final String CLOUD_COMMAND = "service call cloudmanager 1 i32 4";

    /** What the AVAS tool does, for the Details sheet. */
    static final String AVAS_CALL = "BYDAutoEngineDevice\n.setEngineVoiceSimulatorState(0)";

    /**
     * Resets the car's mobile modem for when its connection is stuck: "AT+CFUN=1,1" (full functionality, reset
     * first), the command Engineer Mode's AT Command Tool sends. It is handed to the phone service's raw OEM
     * request for SIM slot 0, which the shell user may use.
     */
    static final String MODEM_AT = "AT+CFUN=1,1";

    private static final long CHECK_EVERY_MS = 60_000;
    private static final long RETRY_AFTER_FAILURE_MS = 30_000;

    private static String hhmm() {
        return new SimpleDateFormat("HH:mm", L.dateLocale()).format(new Date());
    }

    private static String statusWord(AdbLoopback.Result r) {
        return r.status.name().toLowerCase(Locale.US).replace('_', ' ');
    }

    /** Marks a tweak as running; false when it already is. */
    private static boolean begin(Action a) {
        synchronized (Tweaks.class) {
            if (a.state == Action.BUSY) return false;
            a.state = Action.BUSY;
            a.detail = "";
            return true;
        }
    }

    private static void finish(Action a, boolean ok, String detail) {
        a.detail = detail;
        a.state = ok ? Action.OK : Action.FAILED;
    }

    // ---- data roaming -------------------------------------------------------------------------------
    private static long lastAttemptMs;
    private static long lastCheckMs;
    private static volatile boolean lastFailed;
    /** The value a running change is setting (1 or 0), so the switch can move at once; -1 when none. */
    static volatile int roamingTarget = -1;

    /** 1 = on, 0 = off, -1 = could not be read. */
    static int roamingState(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "data_roaming", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Turns the roaming setting on or off now (a switch, or a re-apply). Never blocks the caller. */
    static void setRoaming(final Context ctx, final boolean on, final String why) {
        final Context app = ctx.getApplicationContext();
        if (!begin(ROAMING)) return;
        roamingTarget = on ? 1 : 0;
        lastAttemptMs = System.currentTimeMillis();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String when = hhmm();
                try {
                    AdbLoopback.Result r = new AdbLoopback(app).runShell(
                            on ? ROAMING_COMMAND : ROAMING_OFF_COMMAND, 20_000);
                    int now = roamingState(app);
                    if (r.status == AdbLoopback.Status.OK) {
                        lastFailed = false;
                        ROAMING.detail = "";
                        ROAMING.state = Action.IDLE;
                    } else {
                        lastFailed = true;
                        finish(ROAMING, false, L.f("failed at %1$s: %2$s", when, statusWord(r)));
                    }
                    Diag.log("tweak roaming " + (on ? "on" : "off") + " (" + why + "): " + r.status + " "
                            + r.detail + " | " + r.output.trim() + " | now " + now);
                } catch (Throwable e) {
                    lastFailed = true;
                    finish(ROAMING, false, L.f("failed at %s", when));
                    Diag.log("tweak roaming failed", e);
                } finally {
                    roamingTarget = -1;
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
        setRoaming(ctx, true, why);
    }

    // ---- BYD cloud ----------------------------------------------------------------------------------------
    /** Runs the cloud command now (a button press). Never blocks the caller. */
    static void connectCloud(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!begin(CLOUD)) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String when = hhmm();
                try {
                    AdbLoopback.Result r = new AdbLoopback(app).runShell(CLOUD_COMMAND, 20_000);
                    if (r.status == AdbLoopback.Status.OK) {
                        finish(CLOUD, true, "");
                    } else {
                        finish(CLOUD, false, L.f("failed at %1$s: %2$s", when, statusWord(r)));
                    }
                    Diag.log("tweak cloud: " + r.status + " " + r.detail + " | " + r.output.trim());
                } catch (Throwable e) {
                    finish(CLOUD, false, L.f("failed at %s", when));
                    Diag.log("tweak cloud failed", e);
                }
            }
        }, "tweak-cloud");
        t.setDaemon(true);
        t.start();
    }

    // ---- AVAS ----------------------------------------------------------------------------------------------
    /** Where the AVAS tool writes what it did: the shell user's own scratch folder. */
    private static final String AVAS_OUT = "/data/local/tmp/triprecng-avas.out";
    /** The car can bring AVAS back on a little after it starts, so a start-up run keeps checking this long. */
    private static final int AVAS_KEEP_SECONDS = 90;
    private static final long AVAS_RETRY_MS = 30_000;
    private static final long AVAS_START_GAP_MS = 60_000;
    private static final int AVAS_MAX_TRIES = 6;

    private static boolean avasPending;
    private static int avasTries;
    private static long avasLastAttemptMs;
    private static long avasStartMs;

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
        if (!Prefs.keepAvasOff(ctx) || !Prefs.linkEnabled(ctx)) return;
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
        if (!avasPending || !Prefs.keepAvasOff(ctx) || !Prefs.linkEnabled(ctx)) return;
        runAvas(ctx, AVAS_KEEP_SECONDS, why);
    }

    private static void runAvas(final Context ctx, final int keepSeconds, final String why) {
        final Context app = ctx.getApplicationContext();
        synchronized (Tweaks.class) {
            if (AVAS.state == Action.BUSY) return;
            long now = System.currentTimeMillis();
            if (keepSeconds > 0) {
                // a start-up run: only while one is waiting, a few tries, 30 s apart
                if (!avasPending || avasTries >= AVAS_MAX_TRIES) return;
                if (avasTries > 0 && now - avasLastAttemptMs < AVAS_RETRY_MS) return;
                avasTries++;
            }
            AVAS.state = Action.BUSY;
            AVAS.detail = "";
            avasLastAttemptMs = now;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String when = hhmm();
                boolean done = false;
                try {
                    AdbLoopback.Result r = new AdbLoopback(app).runShell(avasCommand(app, keepSeconds), 30_000);
                    String word = r.status == AdbLoopback.Status.OK ? avasWord(r.output) : "";
                    if (word.equals("turned_off")) {
                        finish(AVAS, true, L.f("turned off at %s", when));
                        done = true;
                    } else if (word.equals("already_off")) {
                        finish(AVAS, true, L.f("already off at %s", when));
                        done = true;
                    } else if (word.equals("unavailable")) {
                        finish(AVAS, false, L.f("failed at %1$s: %2$s", when, L.t("not available on this car")));
                        done = true; // nothing to retry
                    } else if (word.equals("still_on")) {
                        finish(AVAS, false, L.f("failed at %1$s: %2$s", when, L.t("the car kept it on")));
                    } else if (r.status != AdbLoopback.Status.OK) {
                        finish(AVAS, false, L.f("failed at %1$s: %2$s", when, statusWord(r)));
                    } else {
                        finish(AVAS, false, L.f("failed at %s", when));
                    }
                    Diag.log("tweak avas (" + why + "): " + r.status + " " + r.detail + " | "
                            + r.output.trim().replace('\n', ' '));
                } catch (Throwable e) {
                    finish(AVAS, false, L.f("failed at %s", when));
                    Diag.log("tweak avas failed", e);
                } finally {
                    if (done) {
                        synchronized (Tweaks.class) {
                            avasPending = false;
                        }
                    }
                }
            }
        }, "tweak-avas");
        t.setDaemon(true);
        t.start();
    }

    // ---- modem reboot ------------------------------------------------------------------------------------
    private static final String MODEM_QUERY_AT = "AT+CEREG?";
    private static final Pattern LTE_AFTER = Pattern.compile("lte_after=(\\d+)");

    /** The modem must stay without a connection and signal this long before an automatic reboot. */
    private static final long MODEM_DOWN_MS = 150_000;
    private static final long MODEM_CHECK_MS = 30_000;
    private static final long MODEM_COOLDOWN_MS = 15 * 60_000;
    private static final int MODEM_MAX_AUTO = 3;

    private static boolean modemProbing;
    private static long modemProbeMs;
    private static long modemDownSinceMs;
    private static long modemAutoMs;
    private static int modemAutoCount;

    /**
     * The shell command for one AT command: "service call phoneEx 45" is invokeOemRilRequestRawBySlot(slot,
     * request, answer). The request is parceled as its length followed by its bytes packed into little-endian
     * 32-bit words; the last number is the size of the answer buffer.
     */
    static String atCall(int slot, String at, int answerBytes) {
        byte[] b = at.getBytes();
        StringBuilder sb = new StringBuilder("service call phoneEx 45 i32 ").append(slot)
                .append(" i32 ").append(b.length);
        for (int i = 0; i < b.length; i += 4) {
            int w = 0;
            for (int k = 0; k < 4 && i + k < b.length; k++) w |= (b[i + k] & 0xff) << (8 * k);
            sb.append(" i32 ").append(w);
        }
        return sb.append(" i32 ").append(answerBytes).toString();
    }

    /**
     * Reads the start of "service call" output: the exception code (0 = none), then the call's result (the
     * answer's length, or a negative error). Returns both, or null when the output is not understood.
     */
    static int[] parcelHead(String output) {
        int p = output.indexOf("Parcel(");
        if (p < 0) return null;
        int[] w = new int[2];
        int n = 0;
        for (String t : output.substring(p + 7).split("\\s+")) {
            if (!t.matches("[0-9a-f]{8}")) continue;
            w[n++] = (int) Long.parseLong(t, 16);
            if (n == 2) return w;
        }
        return null;
    }

    /**
     * The whole reboot as one shell script: send the AT command, then ask the modem itself (AT+CEREG?) once a
     * second whether LTE is registered again. The answer only counts after the modem was seen to go away (or
     * 25 s have passed), because it can still answer from before the reset in the first moments.
     */
    static String modemCommand() {
        String ask = atCall(0, MODEM_QUERY_AT, 48);
        return atCall(0, MODEM_AT, 256) + "; echo SENT; sleep 3; i=3; d=0; "
                + "while [ $i -le 60 ]; do "
                + "if " + ask + " 2>/dev/null | grep -q -E 'EREG: *[0-9]+,[15]'; then "
                + "if [ $d -eq 1 ] || [ $i -ge 25 ]; then echo lte_after=$i; break; fi; else d=1; fi; "
                + "sleep 1; i=$((i+1)); done; echo net=$(getprop gsm.network.type)";
    }

    /** Reboots the modem now (a confirmed button press, or the automatic reboot). Never blocks the caller. */
    static void rebootModem(final Context ctx, final String why) {
        final Context app = ctx.getApplicationContext();
        if (!begin(MODEM)) return;
        modemDownSinceMs = 0;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String when = hhmm();
                String prefix = "auto".equals(why) ? L.f("Automatic reboot at %s: ", when) : "";
                try {
                    AdbLoopback.Result r = new AdbLoopback(app).runShell(modemCommand(), 100_000);
                    int[] head = r.status == AdbLoopback.Status.OK ? parcelHead(r.output) : null;
                    Matcher m = LTE_AFTER.matcher(r.output);
                    if (r.status != AdbLoopback.Status.OK) {
                        finish(MODEM, false, prefix + L.f("failed at %1$s: %2$s", when, statusWord(r)));
                    } else if (head == null) {
                        finish(MODEM, false, prefix + L.f("failed at %s", when));
                    } else if (head[0] != 0) {
                        finish(MODEM, false, prefix + L.f("failed at %1$s: %2$s", when,
                                L.t("refused by the phone service")));
                    } else if (m.find()) {
                        finish(MODEM, true, prefix + L.f("LTE back after %d s", Integer.parseInt(m.group(1))));
                    } else {
                        finish(MODEM, false, prefix + L.t("still not on LTE: reboot again"));
                    }
                    String out = r.output.trim().replace('\n', ' ');
                    Diag.log("tweak modem (" + why + "): " + r.status + " " + r.detail + " | "
                            + out.substring(Math.max(0, out.length() - 120)));
                } catch (Throwable e) {
                    finish(MODEM, false, prefix + L.f("failed at %s", when));
                    Diag.log("tweak modem failed", e);
                }
            }
        }, "tweak-modem");
        t.setDaemon(true);
        t.start();
    }

    // ---- automatic modem reboot --------------------------------------------------------------------------
    static final int VERDICT_UNKNOWN = 0;
    static final int VERDICT_FINE = 1;
    static final int VERDICT_STUCK = 2;

    /** Reads the car's two mobile connections and the radio state, as one line. */
    private static final String MODEM_PROBE = "echo \"a2=$(getprop net.lte.apn2.real_state)/"
            + "$(getprop net.lte.apn2.state) a3=$(getprop net.lte.apn3.real_state)/$(getprop net.lte.apn3.state) "
            + "net=$(getprop gsm.network.type) sim=$(getprop gsm.sim.state)\"";

    private static String field(String out, String key) {
        Matcher m = Pattern.compile("(?:^|\\s)" + key + "=(\\S*)").matcher(out);
        return m.find() ? m.group(1) : "";
    }

    /** "<real state>/<state>" of one APN: 1 = down, 0 = up, -1 = not known. */
    private static int apnDown(String pair) {
        int i = pair.indexOf('/');
        String real = i < 0 ? pair : pair.substring(0, i);
        String state = i < 0 ? "" : pair.substring(i + 1);
        if (real.isEmpty()) return -1;
        return !real.equals("connect") && !state.equals("connect") ? 1 : 0;
    }

    /**
     * Stuck means: both mobile connections (APN2 and APN3) are down and the modem has no signal (no network
     * type) although the SIM is loaded, so it is not just restarting. Anything unclear is "unknown".
     */
    static int modemVerdict(String out) {
        int d2 = apnDown(field(out, "a2"));
        int d3 = apnDown(field(out, "a3"));
        if (d2 < 0 || d3 < 0) return VERDICT_UNKNOWN;
        String net = field(out, "net");
        int comma = net.indexOf(',');
        if (comma >= 0) net = net.substring(0, comma);
        String sim = field(out, "sim");
        boolean noSignal = net.isEmpty() || net.equalsIgnoreCase("unknown");
        boolean simOk = sim.equals("LOADED") || sim.equals("READY");
        return d2 == 1 && d3 == 1 && noSignal && simOk ? VERDICT_STUCK : VERDICT_FINE;
    }

    /** The user switched the automatic reboot on or off: forget what was seen so far. */
    static void autoModemChanged() {
        synchronized (Tweaks.class) {
            modemDownSinceMs = 0;
            modemAutoCount = 0;
        }
    }

    /**
     * Called every second by the recorder. While the automatic reboot is switched on it looks at the car's mobile
     * connections every 30 s, and reboots the modem when they have all been down with no signal for 2.5 minutes:
     * at most 3 times in a row, 15 minutes apart. Cheap when it is switched off.
     */
    static void ensureModem(Context ctx) {
        // without the Vehicle data link there is no approved debugging connection to look through
        if (!Prefs.autoModemReboot(ctx) || !Prefs.linkEnabled(ctx)) return;
        long now = System.currentTimeMillis();
        final Context app = ctx.getApplicationContext();
        synchronized (Tweaks.class) {
            if (modemProbing || MODEM.state == Action.BUSY) return;
            if (now - modemProbeMs < MODEM_CHECK_MS) return;
            modemProbeMs = now;
            modemProbing = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                boolean reboot = false;
                try {
                    AdbLoopback.Result r = new AdbLoopback(app).runShell(MODEM_PROBE, 15_000);
                    int verdict = r.status == AdbLoopback.Status.OK ? modemVerdict(r.output) : VERDICT_UNKNOWN;
                    long at = System.currentTimeMillis();
                    synchronized (Tweaks.class) {
                        if (verdict != VERDICT_STUCK) {
                            if (verdict == VERDICT_FINE) modemAutoCount = 0; // the outage is over
                            modemDownSinceMs = 0;
                        } else if (modemDownSinceMs == 0) {
                            modemDownSinceMs = at;
                            Diag.log("modem: both APNs down and no signal, watching (" + r.output.trim() + ")");
                        } else if (at - modemDownSinceMs >= MODEM_DOWN_MS && at - modemAutoMs >= MODEM_COOLDOWN_MS
                                && modemAutoCount < MODEM_MAX_AUTO) {
                            modemAutoMs = at;
                            modemAutoCount++;
                            reboot = true;
                        }
                    }
                } catch (Throwable e) {
                    Diag.log("modem probe failed", e);
                } finally {
                    synchronized (Tweaks.class) {
                        modemProbing = false;
                    }
                }
                if (reboot) rebootModem(app, "auto");
            }
        }, "tweak-modem-probe");
        t.setDaemon(true);
        t.start();
    }
}
