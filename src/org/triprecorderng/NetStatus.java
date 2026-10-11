package org.triprecorderng;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

/** What the Tweaks screen's Connection card shows: the roaming setting, cellular internet, and what apps use. */
final class NetStatus {
    private NetStatus() {}

    static final int APPS_NOTHING = 0;
    static final int APPS_WIFI = 1;
    static final int APPS_CELLULAR = 2;
    static final int APPS_ETHERNET = 3;
    static final int APPS_OTHER = 4;

    /** One reading of the network state. */
    static final class Snapshot {
        /** The Android data-roaming setting: 1 on, 0 off, -1 unknown. */
        int roamingSetting = -1;
        boolean cellular;
        boolean validated;
        boolean roamingNow;
        int appsUse = APPS_NOTHING;
        /** False when the state could not be read at all. */
        boolean available = true;
    }

    static Snapshot read(Context ctx) {
        Snapshot s = new Snapshot();
        try {
            s.roamingSetting = Settings.Global.getInt(ctx.getContentResolver(), "data_roaming", -1);
        } catch (Throwable t) {
            s.roamingSetting = -1;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                s.cellular = true;
                s.validated |= nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                s.roamingNow |= !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
            }
            NetworkCapabilities a = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (a != null) {
                s.appsUse = a.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? APPS_WIFI
                        : a.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? APPS_CELLULAR
                        : a.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? APPS_ETHERNET : APPS_OTHER;
            }
        } catch (Throwable t) {
            s.available = false;
        }
        return s;
    }
}
