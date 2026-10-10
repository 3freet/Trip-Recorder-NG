package org.triprecorderng;

import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * Runs as the shell user (started through ADB with app_process, like the data-link helper): only that user is
 * allowed to set this car setting. It turns off BYD's "engine voice simulator", the switch the car's control
 * center shows as AVAS (the external EV sound), and can keep checking for a while afterwards because the car
 * brings it back on a little after it starts.
 *
 * Argument: how many seconds to keep checking after the first attempt (0 = once). It prints "result=<word>" as
 * soon as the first attempt is done: turned_off, already_off, still_on, unavailable or failed:<reason>.
 */
public final class AvasTool {
    private static final int OFF = 0;
    private static final int ON = 1;

    private AvasTool() {}

    public static void main(String[] args) {
        int keepSeconds = 0;
        try {
            if (args.length > 0) keepSeconds = Math.max(0, Math.min(600, Integer.parseInt(args[0])));
        } catch (NumberFormatException ignored) {
            // a single attempt
        }
        try {
            HiddenApi.exempt();
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
            Class<?> c = Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice");
            Object dev = c.getMethod("getInstance", Context.class).invoke(null, ctx);
            Method get = c.getMethod("getEngineVoiceSimulatorState");
            Method set = c.getMethod("setEngineVoiceSimulatorState", int.class);
            System.out.println("result=" + turnOff(dev, get, set));
            System.out.flush();
            long end = System.currentTimeMillis() + keepSeconds * 1000L;
            while (System.currentTimeMillis() < end) {
                Thread.sleep(2000);
                if (state(dev, get) == ON) System.out.println("again=" + turnOff(dev, get, set));
            }
        } catch (Throwable t) {
            Throwable x = t.getCause() != null ? t.getCause() : t;
            System.out.println("result=failed:" + x.getClass().getSimpleName());
        }
        System.out.flush();
        System.exit(0);
    }

    private static int state(Object dev, Method get) throws Exception {
        return ((Number) get.invoke(dev)).intValue();
    }

    private static String turnOff(Object dev, Method get, Method set) throws Exception {
        int s = state(dev, get);
        System.out.println("before=" + s);
        if (s == OFF) return "already_off";
        if (s != ON) return "unavailable";
        System.out.println("set=" + set.invoke(dev, OFF));
        Thread.sleep(700);
        return state(dev, get) == OFF ? "turned_off" : "still_on";
    }
}
