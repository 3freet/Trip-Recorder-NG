package org.triprecorderng;

import android.content.Context;

/**
 * Turns the car's daytime running lights off while the gearbox is in P, and back on as soon as it is
 * not, when the matching tweak is switched on. Only acts on a gear change: a manual switch on the car's
 * own settings page while parked is left alone until the gearbox actually moves, rather than being
 * fought every poll. The gearbox position comes through the same fast-answering connection as the turn
 * signal (the Vehicle data link), since the car refuses to tell an ordinary app.
 */
final class DaytimeLights {
    private static final long POLL_MS = 2000;
    private static final long LINK_RETRY_MS = 3000;

    private static DaytimeLights current;

    private final Context ctx;
    private final HelperLink link;
    private volatile boolean running;
    private Thread watcher;
    private int lastGear = Integer.MIN_VALUE;

    private DaytimeLights(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.link = new HelperLink(this.ctx);
    }

    /** Starts or stops watching to match the switch; cheap to call often. */
    static synchronized void sync(Context ctx) {
        boolean want = Prefs.daytimeLightsOffWhenParked(ctx);
        if (want && current == null) {
            current = new DaytimeLights(ctx);
            current.start();
        } else if (!want && current != null) {
            current.stop();
            current = null;
        }
    }

    static synchronized void shutdown() {
        if (current != null) current.stop();
        current = null;
    }

    private void start() {
        running = true;
        watcher = new Thread(new Runnable() {
            @Override public void run() {
                watch();
            }
        }, "daytime-lights");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void stop() {
        running = false;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                // the switch is turned off: give the lights back if they are currently held off
                if (link.connected() && link.drl(0) == 2) link.drl(1);
                link.close();
            }
        }, "daytime-lights-stop");
        t.setDaemon(true);
        t.start();
    }

    private void watch() {
        while (running) {
            try {
                if (!link.connected() && !(Prefs.linkEnabled(ctx) && link.connect())) {
                    Thread.sleep(LINK_RETRY_MS);
                    continue;
                }
                int t = link.turn(); // asked only for the gear that rides along with it
                if (t < 0) {
                    link.close(); // lost, or a helper that does not know the request; try again from the start
                    Thread.sleep(LINK_RETRY_MS);
                    continue;
                }
                int gear = link.gear();
                if (gear != lastGear) {
                    lastGear = gear;
                    apply(gear);
                }
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Diag.log("daytime-lights watcher error", t);
                try {
                    Thread.sleep(LINK_RETRY_MS); // an error that repeats (the system itself is down, say) must not spin
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** P (1) turns the lights off; any other known gear turns them back on. An unreadable gear (-1) is left alone. */
    private void apply(int gear) {
        if (gear < 0) return;
        int want = gear == 1 ? 2 : 1;
        link.drl(want);
        Diag.log("daytime lights: gear " + gear + ", turned " + (want == 2 ? "off" : "on"));
    }
}
