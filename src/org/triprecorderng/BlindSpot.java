package org.triprecorderng;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;

/**
 * Shows a side camera in a window while a turn signal is on. The turn signal is read through the helper (the car
 * refuses to tell an ordinary app), so this needs the Vehicle data link; the left signal's picture can go to the
 * instrument cluster, which also needs the helper (it writes the flag that makes the cluster show a window of ours).
 * The window stays up a moment after the signal goes off, like the car's own blind-spot window, and a tap closes it.
 */
final class BlindSpot {
    /** What the Tweaks screen says about it. */
    static final int OFF = 0;
    static final int NEEDS_PERMISSION = 1;
    static final int NEEDS_LINK = 2;
    static final int READY = 3;
    static final int SHOWING = 4;

    private static final long POLL_MS = 100;
    private static final long LINK_RETRY_MS = 3000;
    /**
     * How long the picture stays after the signal goes off. The car's own window hides 2 s after its lamp reading goes
     * off; ours sees the change up to one poll later and checks the time one poll later again, so it counts a little
     * less to end up at the same moment.
     */
    private static final long HOLD_MS = 1900;
    private static final long FLAG_CHECK_MS = 1000;
    /** The car's full-screen turn-signal image comes in gear D at this speed or less. */
    private static final double CAR_FULL_SCREEN_KMH = 15;

    /** Where the window goes, for the 2560 x 1440 centre screen and the 1920 x 720 cluster. */
    private static final int CENTRE_X = 760, CENTRE_Y = 113, CENTRE_W = 700;
    /**
     * The cluster shows only one rectangle of its display, 686 x 386 at (36, 208): where the car's own window goes. The
     * car's own picture keeps the left two thirds of it, ours takes the right third.
     */
    private static final int CLUSTER_X = 36 + 686 - 228, CLUSTER_Y = 208, CLUSTER_W = 228, CLUSTER_H = 386;
    /** The whole rectangle, for the picture shown while parked. */
    private static final int BOX_X = 36, BOX_Y = 208, BOX_W = 686, BOX_H = 386;

    private static BlindSpot current;
    private static volatile int state = OFF;
    // test hook: act as if this side's signal were on until then
    private static volatile int forcedSide;
    private static volatile long forcedUntil;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HelperLink link;
    private final SideCamera camera = new SideCamera();
    private volatile boolean running;
    private Thread watcher;
    // the signal now, what is on screen, and when the signal went off
    private int shownSide;
    private boolean onCluster;
    private volatile boolean dismissed;
    private long offSince;
    private int lastRaw = Integer.MIN_VALUE;
    private long flagCheckedAt;
    // what the car's Image app is set to do on a turn signal, asked now and then in the background
    private static final long SETTING_EVERY_MS = 5000;
    private volatile int imageSetting = ImageSetting.UNKNOWN;
    private long settingAskedAt;
    private volatile boolean askingSetting;

    private BlindSpot(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.link = new HelperLink(this.ctx);
    }

    /** Starts or stops watching to match the switch; cheap to call often. */
    static synchronized void sync(Context ctx) {
        boolean want = Prefs.blindSpot(ctx);
        if (want && current == null) {
            current = new BlindSpot(ctx);
            current.start();
        } else if (!want && current != null) {
            current.stop();
            current = null;
            state = OFF;
        }
    }

    static synchronized void shutdown() {
        if (current != null) current.stop();
        current = null;
        state = OFF;
    }

    static int state() {
        return state;
    }

    /** Test hook ("adb shell am start ... --es blind_test left --ei blind_secs 20"): acts as if that signal were on. */
    static void test(int side, int seconds) {
        forcedSide = side;
        forcedUntil = SystemClock.elapsedRealtime() + seconds * 1000L;
    }

    /** Whether the camera and the right to draw over other apps are in place. */
    static boolean permitted(Context ctx) {
        return Settings.canDrawOverlays(ctx) && ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void start() {
        running = true;
        watcher = new Thread(new Runnable() {
            @Override public void run() {
                watch();
            }
        }, "blind-spot");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void stop() {
        running = false;
        final boolean cluster = onCluster;
        ui.post(new Runnable() {
            @Override public void run() {
                camera.hide();
            }
        });
        // stop() is called from a click and from the service's onDestroy, both on the main thread, where the helper
        // cannot be talked to (a network call there is refused): the flag is switched off and the link closed elsewhere
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                if (cluster && link.connected()) link.projection(2);
                link.close();
            }
        }, "blind-spot-stop");
        t.setDaemon(true);
        t.start();
    }

    // ---- the watching thread ---------------------------------------------------------------------
    private void watch() {
        while (running) {
            try {
                if (!permitted(ctx)) {
                    state = NEEDS_PERMISSION;
                    Thread.sleep(LINK_RETRY_MS);
                    continue;
                }
                if (!link.connected() && !(Prefs.linkEnabled(ctx) && link.connect())) {
                    state = NEEDS_LINK;
                    Thread.sleep(LINK_RETRY_MS);
                    continue;
                }
                int t = link.turn();
                if (t != lastRaw) {
                    lastRaw = t;
                    Diag.log("blind-spot: turn signal reads " + t + " (" + link.lastReply() + ")");
                }
                if (t < 0) {
                    link.close(); // lost, or a helper that does not know the request; try again from the start
                    Thread.sleep(LINK_RETRY_MS);
                    continue;
                }
                int side = t == 2 || t == 3 ? SideCamera.LEFT : t == 4 || t == 5 ? SideCamera.RIGHT : 0;
                if (forcedUntil > SystemClock.elapsedRealtime()) side = forcedSide;
                if (shownSide == 0) askImageSetting();
                step(side);
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Diag.log("blind-spot watcher error", t);
            }
        }
    }

    /** Asks the car's Image app for its turn-signal setting now and then, without holding the signal watching up. */
    private void askImageSetting() {
        long now = SystemClock.elapsedRealtime();
        if (askingSetting || now - settingAskedAt < SETTING_EVERY_MS) return;
        settingAskedAt = now;
        askingSetting = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int v = ImageSetting.read(ctx, 1500);
                    if (v != imageSetting) Diag.log("blind-spot: the car's turn-signal view is set to: " + ImageSetting.describe(v));
                    imageSetting = v;
                } finally {
                    askingSetting = false;
                }
            }
        }, "image-setting");
        t.setDaemon(true);
        t.start();
    }

    private void step(int side) throws InterruptedException {
        long now = SystemClock.elapsedRealtime();
        if (side != 0) {
            offSince = 0;
            if (shownSide != side) {
                dismissed = false;
                if (shownSide != 0) hide();
                show(side);
            }
        } else {
            dismissed = false;
            if (shownSide != 0) {
                if (offSince == 0) offSince = now;
                else if (now - offSince >= HOLD_MS) hide();
            } else {
                state = READY;
            }
        }
        if (onCluster && shownSide != 0 && now - flagCheckedAt >= FLAG_CHECK_MS) {
            flagCheckedAt = now;
            // the car's own cluster window writes "off" when it goes, which would take ours with it
            if (link.projection(0) != 1) Diag.log("blind-spot: cluster flag was taken off, set again: " + link.projection(1));
        }
    }

    private void show(int side) throws InterruptedException {
        shownSide = side;
        flagCheckedAt = SystemClock.elapsedRealtime();
        onCluster = side == SideCamera.LEFT && Prefs.blindSpotCluster(ctx);
        final boolean cluster = onCluster;
        // the car's own window on the cluster only exists in gear D and when its setting puts the left signal's
        // floating window there; when it is not going to be there ours takes the whole rectangle instead of leaving
        // it black beside a third
        final int gear = link.gear();
        final int setting = imageSetting;
        final boolean carWindow = gear == 4 && (setting == ImageSetting.WINDOW_ON_CLUSTER || setting == ImageSetting.UNKNOWN);
        final boolean wholeBox = !carWindow;
        // set to a full-screen image, the car fills the centre screen with this very picture: ours stays away from it
        final double speed = RecorderService.live.speed;
        if (!cluster && gear == 4 && setting == ImageSetting.FULL_SCREEN && !(speed > CAR_FULL_SCREEN_KMH)) {
            Diag.log("blind-spot: the car shows its own full-screen image now, nothing to add on the centre screen");
            return;
        }
        // in P the picture can be the fisheye, as the parking view
        final int view = gear == 1 && Prefs.blindSpotFisheyeInPark(ctx) ? SideCamera.VIEW_FISHEYE : Prefs.blindSpotView(ctx);
        if (cluster) Diag.log("blind-spot: gear " + gear + ", the car's setting: " + ImageSetting.describe(setting) + " -> "
                + (wholeBox ? "whole rectangle" : "right third beside the car's own window"));
        state = SHOWING;
        final int s = side;
        ui.post(new Runnable() {
            @Override public void run() {
                place(s, view, cluster, wholeBox);
            }
        });
    }

    private void hide() {
        boolean cluster = onCluster;
        shownSide = 0;
        onCluster = false;
        state = READY;
        ui.post(new Runnable() {
            @Override public void run() {
                camera.hide();
            }
        });
        if (cluster) link.projection(2);
    }

    // ---- the window (main thread) ---------------------------------------------------------------
    private void place(int side, int view, boolean cluster, boolean wholeBox) {
        if (dismissed) return;
        double aspect = view == SideCamera.VIEW_SIDE ? SideCamera.SIDE_ASPECT : SideCamera.FISHEYE_ASPECT;
        try {
            if (cluster) {
                Display d = clusterDisplay();
                if (d != null) {
                    Context wc = ctx.createDisplayContext(d).createWindowContext(2038, null);
                    // the car's own window keeps two thirds of the rectangle, so ours is the right third, filled and
                    // cropped to fit; parked, the car shows nothing of its own there and ours is the whole rectangle
                    int x = CLUSTER_X, w = CLUSTER_W;
                    int h = view == SideCamera.VIEW_SIDE ? CLUSTER_H : (int) Math.round(w / aspect);
                    int y = CLUSTER_Y + (CLUSTER_H - h) / 2;
                    if (wholeBox) {
                        x = BOX_X;
                        y = BOX_Y;
                        w = BOX_W;
                        h = BOX_H;
                    }
                    Diag.log("blind-spot: window on the cluster display " + d.getDisplayId() + " at " + x + "," + y + " " + w + "x" + h);
                    // the flag that makes the cluster show the window comes once the first picture is on it, as the
                    // car's own window does it
                    camera.show(wc, side, view, x, y, w, h, false, null, new Runnable() {
                        @Override public void run() {
                            Diag.log("blind-spot: cluster flag reads " + link.projection(1) + " after setting it");
                        }
                    });
                    return;
                }
                Diag.log("blind-spot: no cluster display found, using the centre screen");
            }
            final DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            final double k = dm.widthPixels / 2560.0;
            int w = (int) Math.round(CENTRE_W * k), h = (int) Math.round(CENTRE_W / aspect * k);
            int[] saved = Prefs.blindSpotPlace(ctx);
            int x = (int) Math.round((saved != null ? saved[0] : CENTRE_X) * k);
            int y = (int) Math.round((saved != null ? saved[1] : CENTRE_Y) * k);
            x = Math.max(0, Math.min(dm.widthPixels - w, x));
            y = Math.max(0, Math.min(dm.heightPixels - h, y));
            camera.show(ctx, side, view, x, y, w, h, true, new SideCamera.Listener() {
                @Override public void onTap() {
                    dismissed = true;
                    camera.hide();
                }

                @Override public void onMoved(int px, int py) {
                    Prefs.setBlindSpotPlace(ctx, (int) Math.round(px / k), (int) Math.round(py / k));
                }
            }, null);
        } catch (Throwable t) {
            Diag.log("blind-spot window failed", t);
        }
    }

    /** The instrument cluster's own display: it is found by its name, not by its number. */
    private Display clusterDisplay() {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays()) {
            if (d.getName().startsWith("fission_")) return d;
        }
        return null;
    }
}
