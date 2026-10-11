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
    private static final long MODEM_COOLDOWN_MS = 15 * 60_000;
    private static final int MODEM_MAX_AUTO = 3;

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

    // ---- the car's mobile connections and the automatic modem reboot ---------------------------------------
    static final int VERDICT_UNKNOWN = 0;
    static final int VERDICT_FINE = 1;
    static final int VERDICT_STUCK = 2;

    /** One reading of the car's two mobile connections and the radio, as the helper read it from the system. */
    static final class Net {
        final String apn2Real, apn2State, apn3Real, apn3State, type, sim;
        final long atMs;

        Net(String[] v, long atMs) {
            apn2Real = v[0];
            apn2State = v[1];
            apn3Real = v[2];
            apn3State = v[3];
            type = v[4];
            sim = v[5];
            this.atMs = atMs;
        }

        boolean sameAs(Net o) {
            return o != null && apn2Real.equals(o.apn2Real) && apn2State.equals(o.apn2State)
                    && apn3Real.equals(o.apn3Real) && apn3State.equals(o.apn3State) && type.equals(o.type)
                    && sim.equals(o.sim);
        }

        /** The raw values on separate lines, for the Connection card. */
        String lines() {
            return "apn2  " + apn2Real + " / " + apn2State + "\napn3  " + apn3Real + " / " + apn3State
                    + "\nnet  " + type + "   sim  " + sim;
        }

        /** The raw values on one line, which is also what is written to the log whenever they change. */
        @Override public String toString() {
            return "apn2=" + apn2Real + "/" + apn2State + " apn3=" + apn3Real + "/" + apn3State + " net=" + type
                    + " sim=" + sim;
        }
    }

    private static volatile Net net;

    /** The latest reading, or null before the first one (the helper has to be running). */
    static Net net() {
        return net;
    }

    /** "<real state>", "<state>" of one connection: 1 = down, 0 = up, -1 = not known. */
    static int apnDown(String real, String state) {
        if (real.isEmpty()) return -1;
        return !real.equals("connect") && !state.equals("connect") ? 1 : 0;
    }

    /** The radio generation of a network type name: 0 none, 2, 3, 4 (LTE) or 5. */
    static int generation(String type) {
        int comma = type.indexOf(',');
        String t = (comma >= 0 ? type.substring(0, comma) : type).trim().toUpperCase(Locale.US);
        if (t.isEmpty() || t.equals("UNKNOWN")) return 0;
        if (t.startsWith("NR")) return 5;
        if (t.startsWith("LTE") || t.equals("IWLAN")) return 4;
        if (t.equals("GPRS") || t.equals("EDGE") || t.equals("GSM") || t.equals("CDMA") || t.equals("1XRTT")) return 2;
        return 3; // UMTS, HSPA and friends
    }

    /**
     * Stuck means: both mobile connections (APN2 and APN3) are down and the modem has no signal (no network type)
     * although the SIM is loaded, so it is not just restarting. Anything unclear is "unknown".
     */
    static int modemVerdict(String a2Real, String a2State, String a3Real, String a3State, String type, String sim) {
        int d2 = apnDown(a2Real, a2State);
        int d3 = apnDown(a3Real, a3State);
        if (d2 < 0 || d3 < 0) return VERDICT_UNKNOWN;
        boolean simOk = sim.equals("LOADED") || sim.equals("READY");
        return d2 == 1 && d3 == 1 && generation(type) == 0 && simOk ? VERDICT_STUCK : VERDICT_FINE;
    }

    /** The user switched the automatic reboot on or off: forget what was seen so far. */
    static void autoModemChanged() {
        synchronized (Tweaks.class) {
            modemDownSinceMs = 0;
            modemAutoCount = 0;
        }
    }

    /**
     * Called by the recorder with a fresh reading (every 30 s while the helper runs). It keeps the latest one for
     * the Connection card and writes a line to the log whenever a value changes, so a real outage leaves the
     * exact values behind. While the automatic reboot is switched on it also reboots the modem when both
     * connections have been down with no signal for 2.5 minutes: at most 3 times in a row, 15 minutes apart.
     */
    static void noteNet(Context ctx, String[] v) {
        if (v == null || v.length < 6) return;
        long at = System.currentTimeMillis();
        Net n = new Net(v, at);
        Net old = net;
        net = n;
        if (old == null || !old.sameAs(n)) Diag.log("modem: " + n);
        boolean reboot = false;
        synchronized (Tweaks.class) {
            if (!Prefs.autoModemReboot(ctx) || !Prefs.linkEnabled(ctx) || MODEM.state == Action.BUSY) {
                modemDownSinceMs = 0;
                return;
            }
            int verdict = modemVerdict(n.apn2Real, n.apn2State, n.apn3Real, n.apn3State, n.type, n.sim);
            if (verdict != VERDICT_STUCK) {
                if (verdict == VERDICT_FINE) modemAutoCount = 0; // the outage is over
                modemDownSinceMs = 0;
            } else if (modemDownSinceMs == 0) {
                modemDownSinceMs = at;
                Diag.log("modem: both connections down and no signal, watching");
            } else if (at - modemDownSinceMs >= MODEM_DOWN_MS && at - modemAutoMs >= MODEM_COOLDOWN_MS
                    && modemAutoCount < MODEM_MAX_AUTO) {
                modemAutoMs = at;
                modemAutoCount++;
                reboot = true;
            }
        }
        if (reboot) rebootModem(ctx, "auto");
    }
}
