package org.triprecorderng;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What the car's own surround-view app (Image, com.byd.avc) is set to do when a turn signal goes on, asked the way its
 * own settings screen asks: one read-only message to its exported messenger service. The reply says where its
 * floating window goes; the window itself only exists in gear D.
 */
final class ImageSetting {
    /** The car's "Triggered by Turn Signal" choices, as the service reports them. */
    static final int UNKNOWN = -1;
    /** Floating window: the left signal's on the instrument cluster, the right signal's on the centre screen. */
    static final int WINDOW_ON_CLUSTER = 0;
    /** Floating window on the centre screen only. */
    static final int WINDOW_ON_CENTRE = 1;
    /** A full-screen image on the centre screen instead of a window. */
    static final int FULL_SCREEN = 2;
    /** Not triggered by the turn signal at all. */
    static final int OFF = 3;

    private static final int ASK = 1011;
    private static final int ANSWER = 1012;

    private ImageSetting() {
    }

    /** Waits for the answer for at most {@code timeoutMs}; call it off the main thread. */
    static int read(Context ctx, long timeoutMs) {
        final CountDownLatch done = new CountDownLatch(1);
        final int[] answer = {UNKNOWN, 0};
        final Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message m) {
                if (m.what == ANSWER) {
                    answer[0] = m.arg2;
                    answer[1] = m.arg1;
                    done.countDown();
                }
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    Message ask = Message.obtain(null, ASK, 0, 0);
                    ask.replyTo = reply;
                    new Messenger(service).send(ask);
                } catch (Throwable t) {
                    Diag.log("image app setting: asking failed", t);
                    done.countDown();
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
            }
        };
        Intent intent = new Intent("com.byd.action.AVCSERVICE").setComponent(new ComponentName("com.byd.avc", "com.byd.avc.AutoVideoService"));
        boolean bound = false;
        try {
            // flags 0: connect to the service if it runs, never start it
            bound = ctx.bindService(intent, connection, 0);
            if (bound) done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Diag.log("image app setting: binding failed", t);
        } finally {
            if (bound) {
                try {
                    ctx.unbindService(connection);
                } catch (Throwable ignored) {
                }
            }
        }
        // arg1: 1 = the car can show a window on the cluster, 2 = it cannot, 3 = no such setting on this car
        if (answer[0] != UNKNOWN && answer[1] == 2 && answer[0] == WINDOW_ON_CLUSTER) return WINDOW_ON_CENTRE;
        return answer[0];
    }

    static String describe(int setting) {
        switch (setting) {
            case WINDOW_ON_CLUSTER: return "floating window, the left signal's on the cluster";
            case WINDOW_ON_CENTRE: return "floating window on the centre screen only";
            case FULL_SCREEN: return "full-screen image";
            case OFF: return "off";
            default: return "not known";
        }
    }
}
