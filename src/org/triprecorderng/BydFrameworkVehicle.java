package org.triprecorderng;

import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reads vehicle data through BYD's own framework classes (android.hardware.bydauto.*), the same
 * API the stock My Car app uses. Everything is done by reflection so the app compiles against the
 * plain Android SDK and degrades cleanly on units that do not have these classes.
 */
final class BydFrameworkVehicle implements Vehicle {
    private static final String P = "android.hardware.bydauto.";

    private final Context ctx;
    private Object speed, statistic, bodywork, charging, instrument;
    private Method[] mCharging;
    private Method mFuel, mEvKm, mHevKm, mFuelPct, mElecRange, mFuelRange, mJourney;
    private Method mSpeed, mTotalMileage, mTotalElec, mDriveTime, mSoc, mPower;
    private String error = "";

    BydFrameworkVehicle(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Resolves the device objects and methods. Returns null on success or an error text. */
    String bind() {
        try {
            HiddenApi.exempt();
            speed = device(P + "speed.BYDAutoSpeedDevice");
            statistic = device(P + "statistic.BYDAutoStatisticDevice");
            bodywork = device(P + "bodywork.BYDAutoBodyworkDevice");
            mSpeed = speed.getClass().getMethod("getCurrentSpeed");
            mTotalMileage = statistic.getClass().getMethod("getTotalMileageValue");
            mTotalElec = statistic.getClass().getMethod("getTotalElecConValue");
            mDriveTime = statistic.getClass().getMethod("getDrivingTimeValue");
            mSoc = statistic.getClass().getMethod("getElecPercentageValue");
            mPower = bodywork.getClass().getMethod("getPowerLevel");
            mFuel = optional(statistic, "getTotalFuelConValue");
            mEvKm = optional(statistic, "getEVMileageValue");
            mHevKm = optional(statistic, "getHEVMileageValue");
            mFuelPct = optional(statistic, "getFuelPercentageValue");
            mElecRange = optional(statistic, "getElecDrivingRangeValue");
            mFuelRange = optional(statistic, "getFuelDrivingRangeValue");
            bindInstrument();
            bindCharging();
            return null;
        } catch (Throwable t) {
            error = describe(t);
            return error;
        }
    }

    /** A statistic getter that a unit may not have (a pure EV has no fuel, for example). */
    private static Method optional(Object target, String name) {
        try {
            return target.getClass().getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The instrument device gives the cluster's journey counter; optional like the charging device. */
    private void bindInstrument() {
        try {
            instrument = device(P + "instrument.BYDAutoInstrumentDevice");
            mJourney = optional(instrument, "getCurrentJourneyDriveMileage");
        } catch (Throwable t) {
            instrument = null;
            mJourney = null;
        }
    }

    /** The charging device is optional: a unit without it still records trips. */
    private void bindCharging() {
        try {
            charging = device(P + "charging.BYDAutoChargingDevice");
            String[] names = {"getBatteryManagementDeviceState", "getChargingGunState",
                    "getChargerWorkState", "getChargingPower", "getChargingCapacity"};
            mCharging = new Method[names.length];
            for (int i = 0; i < names.length; i++) mCharging[i] = charging.getClass().getMethod(names[i]);
        } catch (Throwable t) {
            charging = null;
            mCharging = null;
        }
    }

    private Object device(String cls) throws Exception {
        Class<?> c = Class.forName(cls);
        Method gi = c.getMethod("getInstance", Context.class);
        Object o = gi.invoke(null, ctx);
        if (o == null) throw new IllegalStateException(cls + ".getInstance returned null");
        return o;
    }

    private static String describe(Throwable t) {
        Throwable c = t;
        if (t instanceof InvocationTargetException && t.getCause() != null) c = t.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private double num(Object target, Method m) {
        if (target == null || m == null) return Double.NaN;
        try {
            Object r = m.invoke(target);
            if (r instanceof Number) return ((Number) r).doubleValue();
            return Double.NaN;
        } catch (Throwable t) {
            error = m.getName() + ": " + describe(t);
            return Double.NaN;
        }
    }

    @Override public String name() { return "BYD framework API"; }

    @Override public double speedKmh() { return clean(num(speed, mSpeed)); }

    @Override public double powerLevel() { return clean(num(bodywork, mPower)); }

    @Override public double socPercent() { return clean(num(statistic, mSoc)); }

    @Override public double totalElecConsumption() { return clean(num(statistic, mTotalElec)); }

    @Override public double totalDrivingTime() { return clean(num(statistic, mDriveTime)); }

    /** BYD returns the odometer in 0.1 km units on CAN-FD cars, already scaled by the API. */
    @Override public double odometerKm() { return clean(num(statistic, mTotalMileage)); }

    @Override public double journeyKm() { return clean(num(instrument, mJourney)); }

    @Override public double totalFuelLitres() { return clean(num(statistic, mFuel)); }

    @Override public double evMileageKm() { return clean(num(statistic, mEvKm)); }

    @Override public double hevMileageKm() { return clean(num(statistic, mHevKm)); }

    @Override public double fuelPercent() { return clean(num(statistic, mFuelPct)); }

    @Override public double elecRangeKm() { return clean(num(statistic, mElecRange)); }

    @Override public double fuelRangeKm() { return clean(num(statistic, mFuelRange)); }

    @Override public double[] charging() {
        if (charging == null || mCharging == null) return null;
        double[] r = new double[mCharging.length];
        for (int i = 0; i < r.length; i++) {
            double v = num(charging, mCharging[i]);
            r[i] = Double.isNaN(v) || v >= 65535 || v <= -2e9 ? Double.NaN : v;
        }
        return r;
    }

    @Override public String lastError() { return error; }

    private static double clean(double v) {
        return Vehicle.valid(v) ? v : Double.NaN;
    }
}
