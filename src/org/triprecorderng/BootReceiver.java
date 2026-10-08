package org.triprecorderng;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the recorder after the head unit boots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Diag.init(context);
        if (!Prefs.isMainUser()) {
            Diag.log("boot receiver ignored (secondary user " + android.os.Process.myUid() / 100000 + ")");
            return;
        }
        Diag.log("boot receiver: " + intent.getAction());
        String action = intent.getAction();
        // only a real boot broadcast shows that the car lets the app start by itself (not a manual START)
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Prefs.markBootReceiverRan(context);
        }
        try {
            context.startForegroundService(new Intent(context, RecorderService.class));
        } catch (Throwable t) {
            Diag.log("could not start service from boot", t);
        }
    }
}
