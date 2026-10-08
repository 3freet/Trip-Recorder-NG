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
 * what gives it permission to call BYD's vehicle API. It only ever READS values and serves them to
 * the Trip Recorder NG app over a loopback socket protected by a shared token.
 *
 * Protocol (one request per line): "get" -> "v3 speed power soc elec drive odo fuel ev hev fuelPct
 * elecRange fuelRange journey error";
 * "chg" -> "c1 bms gun work power kwh" (charging device, nan where unavailable).
 */
public final class Helper {
    static final String VERSION = "4";
    private static BydFrameworkVehicle vehicle;
    private static String bindError;
    private static String token;

    private Helper() {}

    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        token = args[1];
        System.out.println("triprec helper starting, port " + port);
        try {
            HiddenApi.exempt();
            Looper.prepareMainLooper();
            Context ctx = systemContext();
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
