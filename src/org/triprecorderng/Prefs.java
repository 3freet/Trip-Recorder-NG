package org.triprecorderng;

import android.content.Context;
import android.content.SharedPreferences;

/** Small app settings. */
final class Prefs {
    private Prefs() {}

    /** True when this copy of the app runs in the main Android user (not BYD's secondary-display user). */
    static boolean isMainUser() {
        return android.os.Process.myUid() / 100000 == 0;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("triprecng", Context.MODE_PRIVATE);
    }

    /** Boot counter of the Android system (changes on every real restart); -1 if unavailable. */
    static int currentBootCount(Context c) {
        try {
            return android.provider.Settings.Global.getInt(c.getContentResolver(), "boot_count", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Called by the boot receiver: remembers that the system delivered the start broadcast this boot. */
    static void markBootReceiverRan(Context c) {
        sp(c).edit().putInt("boot_seen_v2", currentBootCount(c)).apply();
    }

    /**
     * Notes the first run after an install or update. BYD resets the app's "disable background"
     * switch whenever the package is installed or replaced, so the boot at which that happened
     * cannot show whether auto-start works.
     */
    static void noteRun(Context c) {
        try {
            long updated = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            SharedPreferences p = sp(c);
            if (p.getLong("last_update", -1) != updated) {
                p.edit().putLong("last_update", updated).putInt("update_boot", currentBootCount(c)).apply();
            }
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    static final int AUTOSTART_OK = 0;
    static final int AUTOSTART_UNKNOWN = 1;
    static final int AUTOSTART_BLOCKED = 2;

    /**
     * Did the car start the app by itself after the last restart? An install or update in the current boot comes
     * first: BYD switched its "Disable background Apps" setting back on then, so what the earlier start of this boot
     * showed no longer counts, and nothing is confirmed until the next restart.
     */
    static int autoStartHealth(Context c) {
        SharedPreferences p = sp(c);
        return healthFor(currentBootCount(c), p.getInt("boot_seen_v2", -2), p.getInt("update_boot", -2));
    }

    /** The decision itself: current boot count, the boot at which the start broadcast last arrived, the boot of the last install. */
    static int healthFor(int boot, int broadcastBoot, int installBoot) {
        if (boot < 0) return AUTOSTART_UNKNOWN;
        if (installBoot == boot) return AUTOSTART_UNKNOWN;
        if (broadcastBoot == boot) return AUTOSTART_OK;
        return AUTOSTART_BLOCKED;
    }

    static boolean linkConsented(Context c) {
        return sp(c).getBoolean("link_consent", false);
    }

    static void setLinkConsented(Context c) {
        sp(c).edit().putBoolean("link_consent", true).apply();
    }

    /** Whether the optional "vehicle data link" (ADB helper) is switched on. Off by default. */
    static boolean linkEnabled(Context c) {
        return sp(c).getBoolean("link_enabled", false);
    }

    static void setLinkEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("link_enabled", on).apply();
    }

    /** Whether each finished trip is also written to the backup folder. On by default. */
    static boolean autoBackup(Context c) {
        return sp(c).getBoolean("auto_backup", true);
    }

    static void setAutoBackup(Context c, boolean on) {
        sp(c).edit().putBoolean("auto_backup", on).apply();
    }

    /** The last battery level the recorder saw and when ({percent, wall ms}), or null if never. */
    static double[] lastSoc(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains("last_soc_wall")) return null;
        return new double[]{Double.longBitsToDouble(p.getLong("last_soc_bits", 0)), p.getLong("last_soc_wall", 0)};
    }

    static void setLastSoc(Context c, double soc, long wallMs) {
        sp(c).edit().putLong("last_soc_bits", Double.doubleToLongBits(soc)).putLong("last_soc_wall", wallMs).apply();
    }

    /** Real distance over integrated speedometer distance; starts at the measured default and is refined. */
    static double speedScale(Context c) {
        String s = sp(c).getString("speed_scale", "");
        try {
            double v = Double.parseDouble(s);
            return v >= 0.85 && v <= 1.1 ? v : JourneyTracker.DEFAULT_SCALE;
        } catch (NumberFormatException e) {
            return JourneyTracker.DEFAULT_SCALE;
        }
    }

    static void setSpeedScale(Context c, double v) {
        sp(c).edit().putString("speed_scale", String.valueOf(v)).apply();
    }

    /** Whether the Tweaks screen's "keep data roaming on" is switched on. Off by default. */
    static boolean keepRoaming(Context c) {
        return sp(c).getBoolean("keep_roaming", false);
    }

    static void setKeepRoaming(Context c, boolean on) {
        sp(c).edit().putBoolean("keep_roaming", on).apply();
    }

    /** Whether trips store their GPS route and event positions. On by default. */
    static boolean recordRoute(Context c) {
        return sp(c).getBoolean("record_route", true);
    }

    static void setRecordRoute(Context c, boolean on) {
        sp(c).edit().putBoolean("record_route", on).apply();
    }

    // ---- energy prices for the cost calculator: what the user typed, NaN if nothing (Cost applies defaults) ----
    static double elecPriceSet(Context c) {
        return price(c, "price_elec");
    }

    static double fuelPriceSet(Context c) {
        return price(c, "price_fuel");
    }

    private static double price(Context c, String key) {
        String s = sp(c).getString(key, "");
        try {
            double v = Double.parseDouble(s);
            return v > 0 ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String currency(Context c) {
        return sp(c).getString("price_currency", "OMR");
    }

    /** "stable", "dev", or "" when never chosen (then the update check follows the installed build's channel). */
    static String updateChannel(Context c) {
        return sp(c).getString("update_channel", "");
    }

    static void setUpdateChannel(Context c, String channel) {
        sp(c).edit().putString("update_channel", channel).apply();
    }

    // ---- the auto-start reminder: "Don't show again" still reminds on every 15th opening ----------

    static final int MUTED_REMINDER_EVERY = 15;

    static boolean autoStartAlertMuted(Context c) {
        return sp(c).getBoolean("autostart_alert_muted", false);
    }

    /** Muting (or un-muting) starts the count of openings from zero. */
    static void setAutoStartAlertMuted(Context c, boolean muted) {
        sp(c).edit().putBoolean("autostart_alert_muted", muted).putInt("autostart_muted_opens", 0).apply();
    }

    /** True when this opening, the n-th since the count started, is the one that reminds again. */
    static boolean mutedReminderDue(int openingsBefore) {
        return openingsBefore + 1 >= MUTED_REMINDER_EVERY;
    }

    /**
     * Counts one opening of the app while the reminder is muted. Returns true on every 15th opening
     * (the count then starts again), when the reminder must be shown after all.
     */
    static boolean countMutedOpening(Context c) {
        int before = sp(c).getInt("autostart_muted_opens", 0);
        boolean due = mutedReminderDue(before);
        sp(c).edit().putInt("autostart_muted_opens", due ? 0 : before + 1).apply();
        return due;
    }

    /** Empty text clears a price, which then falls back to the default. */
    static void setPrices(Context c, String elec, String fuel, String currency) {
        sp(c).edit().putString("price_elec", elec).putString("price_fuel", fuel)
                .putString("price_currency", currency.isEmpty() ? "OMR" : currency).apply();
    }
}
