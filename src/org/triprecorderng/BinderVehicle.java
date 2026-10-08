package org.triprecorderng;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * Fallback path: talks to the "autoservice" system service directly with the same read call the
 * ADB shell uses ("service call autoservice 7 i32 <device> i32 <feature>"), which returns a
 * 32-bit float. Only reads are ever issued (transaction code 7).
 *
 * The feature IDs below are the CAN-FD values read from this head unit's framework; other
 * platform variants use different IDs, in which case reads come back as "no data".
 */
final class BinderVehicle implements Vehicle {
    private static final int TX_GET_FLOAT = 7;

    private static final int DEV_SPEED = 1013;
    private static final int DEV_STATISTIC = 1014;
    private static final int FID_SPEED = -1807745016;
    private static final int FID_SOC = 1246777400;
    private static final int FID_ELEC_TOTAL = 1032871984;
    private static final int FID_DRIVE_TIME = 1246789668;

    private IBinder binder;
    private String error = "";

    String bind() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getMethod("getService", String.class);
            binder = (IBinder) get.invoke(null, "autoservice");
            if (binder == null) {
                error = "autoservice not found";
                return error;
            }
            return null;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return error;
        }
    }

    private double read(int device, int feature) {
        if (binder == null) return Double.NaN;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(device);
            data.writeInt(feature);
            boolean ok = binder.transact(TX_GET_FLOAT, data, reply, 0);
            if (!ok) {
                error = "transact returned false";
                return Double.NaN;
            }
            int status = reply.readInt();
            if (status != 0) {
                error = "service status " + status;
                return Double.NaN;
            }
            double v = Float.intBitsToFloat(reply.readInt());
            return Vehicle.valid(v) ? v : Double.NaN;
        } catch (SecurityException se) {
            error = "permission denied: " + se.getMessage();
            return Double.NaN;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return Double.NaN;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override public String name() { return "autoservice binder"; }

    @Override public double speedKmh() {
        double v = read(DEV_SPEED, FID_SPEED);
        return Double.isNaN(v) ? Double.NaN : Math.max(0, v);
    }

    /** Not readable through this path (integer read), so ignition is inferred from motion. */
    @Override public double powerLevel() { return Double.NaN; }

    @Override public double socPercent() { return read(DEV_STATISTIC, FID_SOC); }

    @Override public double totalElecConsumption() { return read(DEV_STATISTIC, FID_ELEC_TOTAL); }

    @Override public double totalDrivingTime() { return read(DEV_STATISTIC, FID_DRIVE_TIME); }

    /** Not readable through this path (integer read). */
    @Override public double odometerKm() { return Double.NaN; }

    @Override public String lastError() { return error; }
}
