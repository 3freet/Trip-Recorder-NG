package org.triprecorderng;

import android.os.Build;

import java.lang.reflect.Method;

/**
 * BYD's vehicle API lives in framework classes that are not part of the public SDK, so Android's
 * hidden-API checks apply to them. This asks the runtime to exempt them, using the well-known
 * "reflection through the boot classpath" approach (works on Android 9 to 13).
 */
final class HiddenApi {
    private static boolean done;
    private static boolean ok;

    private HiddenApi() {}

    static synchronized boolean exempt() {
        if (done) return ok;
        done = true;
        if (Build.VERSION.SDK_INT < 28) {
            ok = true;
            return true;
        }
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
            ok = true;
            Diag.log("hidden-api exemption applied");
        } catch (Throwable t) {
            Diag.log("hidden-api exemption failed", t);
            ok = false;
        }
        return ok;
    }
}
