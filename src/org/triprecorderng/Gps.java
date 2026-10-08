package org.triprecorderng;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

/** Keeps the most recent GPS fix from Android's location service. */
final class Gps implements LocationListener {
    private final Context ctx;
    private LocationManager lm;
    private volatile Location last;
    private volatile long lastElapsedMs;
    private boolean started;

    Gps(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean hasPermission() {
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    synchronized void start(Looper looper) {
        if (started || !hasPermission()) return;
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Diag.log("gps: provider unavailable or disabled");
                return;
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, looper);
            started = true;
            Diag.log("gps: updates requested");
        } catch (SecurityException se) {
            Diag.log("gps: permission problem", se);
        } catch (Throwable t) {
            Diag.log("gps: start failed", t);
        }
    }

    synchronized void stop() {
        if (lm != null && started) {
            try {
                lm.removeUpdates(this);
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
        started = false;
    }

    /** Latest fix if it is younger than maxAgeMs, otherwise null. */
    Location fresh(long maxAgeMs) {
        Location l = last;
        if (l == null) return null;
        if (android.os.SystemClock.elapsedRealtime() - lastElapsedMs > maxAgeMs) return null;
        return l;
    }

    boolean isStarted() {
        return started;
    }

    @Override public void onLocationChanged(Location location) {
        last = location;
        lastElapsedMs = android.os.SystemClock.elapsedRealtime();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override public void onProviderEnabled(String provider) {}

    @Override public void onProviderDisabled(String provider) {}
}
