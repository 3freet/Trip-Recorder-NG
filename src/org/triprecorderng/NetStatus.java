package org.triprecorderng;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

/** One short line for the Tweaks tab: the roaming setting, whether cellular internet works, and what apps use. */
final class NetStatus {
    private NetStatus() {}

    static String line(Context ctx) {
        int roaming;
        try {
            roaming = Settings.Global.getInt(ctx.getContentResolver(), "data_roaming", -1);
        } catch (Throwable t) {
            roaming = -1;
        }
        boolean cellular = false, validated = false, roamingNow = false;
        String active = "";
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                cellular = true;
                validated |= nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                roamingNow |= !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
            }
            NetworkCapabilities a = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (a != null) {
                active = a.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? L.t("Wi-Fi")
                        : a.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? L.t("cellular")
                        : a.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? L.t("ethernet") : L.t("other");
            }
        } catch (Throwable t) {
            return L.t("Network status unavailable");
        }
        return describe(roaming, cellular, validated, roamingNow, active);
    }

    /** Pure text builder (kept apart so it can be tested without a phone). */
    static String describe(int roamingSetting, boolean cellular, boolean validated, boolean roamingNow, String active) {
        String r = roamingSetting == 1 ? L.t("on") : roamingSetting == 0 ? L.t("off") : L.t("unknown");
        String c = !cellular ? L.t("not connected")
                : validated ? L.t("connected, internet works") + (roamingNow ? L.t(" (roaming)") : "")
                : L.t("connected, no internet yet") + (roamingNow ? L.t(" (roaming)") : "");
        String u = active.isEmpty() ? L.t("nothing") : active;
        return L.f("Roaming setting: %1$s   |   Cellular: %2$s   |   Apps use: %3$s", r, c, u);
    }
}
