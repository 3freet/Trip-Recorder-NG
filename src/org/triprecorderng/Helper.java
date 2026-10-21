package org.triprecorderng;

import android.content.Context;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * Runs as a separate process under the shell user (started through ADB with app_process), which is
 * what gives it permission to call BYD's vehicle API. It reads values and serves them to the Trip Recorder NG
 * app over a loopback socket protected by a shared token. The one thing it writes is the instrument-projection
 * flag, and only when the app's blind-spot option asks for a picture on the instrument cluster.
 *
 * Protocol (one request per line): "get" -> "v3 speed power soc elec drive odo fuel ev hev fuelPct
 * elecRange fuelRange journey error";
 * "chg" -> "c1 bms gun work power kwh" (charging device, nan where unavailable);
 * "net" -> "n1 apn2real apn2state apn3real apn3state networkType simState" (the car's two mobile connections
 * and the radio, read from system properties; "-" where empty);
 * "turn" -> "t1 state lamps stalk gear" (the turn signal: state 1 off, 2 and 3 left, 4 and 5 right, nan when unreadable;
 * lamps and stalk are the two readings it comes from, see turnSnapshot; gear is the gearbox mode, 1 = P, 4 = D);
 * "proj 0", "proj 1" or "proj 2" -> "p1 state" (read, start or stop showing a head-unit window on the instrument
 * cluster; the state read back, nan when unreadable).
 */
public final class Helper {
    static final String VERSION = "8";
    private static BydFrameworkVehicle vehicle;
    private static String bindError;
    private static String token;
    private static Context sysCtx;
    private static Object light;
    private static Object gearbox;
    private static Object panorama;

    private Helper() {}

    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        token = args[1];
        System.out.println("triprec helper starting, port " + port);
        try {
            HiddenApi.exempt();
            Looper.prepareMainLooper();
            Context ctx = systemContext();
            sysCtx = ctx;
            vehicle = new BydFrameworkVehicle(ctx);
            bindError = vehicle.bind();
            System.out.println("bind: " + (bindError == null ? "ok" : bindError));
            final ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    acceptLoop(server);
                }
            }, "helper-accept");
            t.setDaemon(true);
            t.start();
            Looper.loop();
        } catch (Throwable e) {
            System.out.println("helper failed: " + e);
            e.printStackTrace();
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> cls = Class.forName("android.app.ActivityThread");
        Object thread = cls.getMethod("systemMain").invoke(null);
        return (Context) cls.getMethod("getSystemContext").invoke(thread);
    }

    private static void acceptLoop(ServerSocket server) {
        while (true) {
            try {
                final Socket s = server.accept();
                Thread c = new Thread(new Runnable() {
                    @Override public void run() {
                        serve(s);
                    }
                }, "helper-client");
                c.setDaemon(true);
                c.start();
            } catch (IOException e) {
                System.out.println("accept failed: " + e);
                return;
            }
        }
    }

    private static void serve(Socket s) {
        try {
            s.setSoTimeout(120_000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            PrintWriter w = new PrintWriter(s.getOutputStream(), true);
            String first = r.readLine();
            if (first == null || !first.equals(token)) {
                w.println("denied");
                s.close();
                return;
            }
            w.println("ok " + VERSION + " " + (bindError == null ? "bound" : "unbound"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals("get")) {
                    w.println(snapshot());
                } else if (line.equals("chg")) {
                    w.println(chargeSnapshot());
                } else if (line.equals("net")) {
                    w.println(netSnapshot());
                } else if (line.equals("turn")) {
                    w.println(turnSnapshot());
                } else if (line.equals("proj 0") || line.equals("proj 1") || line.equals("proj 2")) {
                    w.println(projection(line.charAt(5) - '0'));
                } else if (line.equals("exit")) {
                    w.println("bye");
                    System.exit(0);
                } else {
                    w.println("unknown");
                }
            }
        } catch (Throwable t) {
            // client went away
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /** One system property, "-" when it is empty or cannot be read; spaces would break the line. */
    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            String v = (String) c.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v == null || v.isEmpty() ? "-" : v.replace(' ', '_');
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String netSnapshot() {
        return "n1 " + prop("net.lte.apn2.real_state") + " " + prop("net.lte.apn2.state") + " "
                + prop("net.lte.apn3.real_state") + " " + prop("net.lte.apn3.state") + " "
                + prop("gsm.network.type") + " " + prop("gsm.sim.state");
    }

    /**
     * The turn signal, the way the car's own blind-spot window reads it: the lamps' own output
     * (LIGHT_TURN_SIGNAL_LIGHT: 2 and 3 left, 4 and 5 right while they are on, whatever else when they are not),
     * which keeps its value for as long as the lamps are in use, three blinks of a flick of the stalk included. The
     * stalk's position (getTurnLightState: 3 left, 5 right) only reads "on" for as long as the stalk is held there, a
     * flick of it for a fraction of a second. The signal counts as on when either says so.
     */
    private static synchronized String turnSnapshot() {
        try {
            if (light == null) {
                light = Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice")
                        .getMethod("getInstance", Context.class).invoke(null, sysCtx);
            }
            int stalk = ((Number) light.getClass().getMethod("getTurnLightState").invoke(light)).intValue();
            int lamps = -1;
            try {
                java.lang.reflect.Field f = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$Light").getDeclaredField("LIGHT_TURN_SIGNAL_LIGHT");
                f.setAccessible(true);
                Object r = light.getClass().getMethod("get", int[].class, Class.class).invoke(light, new int[]{f.getInt(null)}, Integer.TYPE);
                lamps = r.getClass().getField("intValue").getInt(r);
            } catch (Throwable t) {
                // an older unit without the lamp output: the stalk alone has to do
            }
            int state = lamps >= 2 && lamps <= 5 ? lamps : stalk == 3 || stalk == 5 ? stalk : 1;
            return "t1 " + state + " " + lamps + " " + stalk + " " + gear();
        } catch (Throwable t) {
            return "t1 nan";
        }
    }

    /** The gearbox mode (1 = P, 4 = D), -1 when it cannot be read. */
    private static int gear() {
        try {
            if (gearbox == null) {
                gearbox = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice")
                        .getMethod("getInstance", Context.class).invoke(null, sysCtx);
            }
            return ((Number) gearbox.getClass().getMethod("getGearboxAutoModeType").invoke(gearbox)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Writes the panorama device's "project the head unit's picture to the instrument" state (1 on, 2 off; 0 only
     * reads it), which
     * the car needs before it shows a window of ours on the cluster. The device's own getInstance() fails for the
     * shell user (its settings lookup names the wrong package), so it is made through its constructor.
     */
    private static synchronized String projection(int value) {
        try {
            String ids = "android.hardware.bydauto.BYDAutoFeatureIds$Panorama";
            java.lang.reflect.Field f = Class.forName(ids).getDeclaredField("PANORAMA_SCREEN_PROJECTION_STATUS_IVI_TO_INS_SET");
            f.setAccessible(true);
            int id = f.getInt(null);
            if (panorama == null) {
                java.lang.reflect.Constructor<?> k = Class.forName("android.hardware.bydauto.panorama.BYDAutoPanoramaDevice")
                        .getDeclaredConstructor(Context.class);
                k.setAccessible(true);
                panorama = k.newInstance(sysCtx);
            }
            if (value != 0) {
                Class<?> ev = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
                Object e = ev.getConstructor().newInstance();
                ev.getField("intValue").setInt(e, value);
                panorama.getClass().getMethod("set", int[].class, ev).invoke(panorama, new int[]{id}, e);
            }
            Object r = panorama.getClass().getMethod("get", int[].class, Class.class).invoke(panorama, new int[]{id}, Integer.TYPE);
            return "p1 " + r.getClass().getField("intValue").getInt(r);
        } catch (Throwable t) {
            System.out.println("projection failed: " + t + (t.getCause() != null ? " / " + t.getCause() : ""));
            return "p1 nan";
        }
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "nan" : String.format(Locale.US, "%.4f", v);
    }

    private static String chargeSnapshot() {
        double[] c = vehicle == null || bindError != null ? null : vehicle.charging();
        if (c == null) return "c1 nan nan nan nan nan";
        return "c1 " + num(c[0]) + " " + num(c[1]) + " " + num(c[2]) + " " + num(c[3]) + " " + num(c[4]);
    }

    private static String snapshot() {
        if (vehicle == null || bindError != null) {
            return "v3 nan nan nan nan nan nan nan nan nan nan nan nan nan " + String.valueOf(bindError).replace(' ', '_');
        }
        String err = "";
        double[] v = {vehicle.speedKmh(), vehicle.powerLevel(), vehicle.socPercent(),
                vehicle.totalElecConsumption(), vehicle.totalDrivingTime(), vehicle.odometerKm(),
                vehicle.totalFuelLitres(), vehicle.evMileageKm(), vehicle.hevMileageKm(),
                vehicle.fuelPercent(), vehicle.elecRangeKm(), vehicle.fuelRangeKm(),
                vehicle.journeyKm()};
        String e = vehicle.lastError();
        if (e != null && e.length() > 0) err = e.replace(' ', '_');
        StringBuilder sb = new StringBuilder("v3");
        for (double x : v) sb.append(' ').append(num(x));
        sb.append(' ').append(err.length() == 0 ? "-" : err);
        return sb.toString();
    }
}
