package org.triprecorderng;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Vehicle data served by the shell-user helper process (BYD API with full permissions). */
final class HelperVehicle implements Vehicle {
    static final int PORT = 38417;

    private final String token;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private static final int FIELDS = 13;
    private double[] snap = new double[FIELDS];
    private long snapAt;
    private double[] chg;
    private long chgAt;
    private String error = "";

    HelperVehicle(String token) {
        this.token = token;
        java.util.Arrays.fill(snap, Double.NaN);
    }

    /** Opens the connection and checks the helper answers. Returns null on success. */
    synchronized String connect() {
        close();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
            s.setSoTimeout(2500);
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out = new PrintWriter(s.getOutputStream(), true);
            out.println(token);
            String hello = in.readLine();
            if (hello == null || !hello.startsWith("ok")) {
                close();
                return "helper refused: " + hello;
            }
            if (!hello.contains(Helper.VERSION)) {
                close();
                return "helper version mismatch: " + hello;
            }
            return refresh() ? null : error;
        } catch (IOException e) {
            close();
            error = "helper not reachable";
            return error;
        }
    }

    private synchronized boolean refresh() {
        if (socket == null) return false;
        try {
            out.println("get");
            String line = in.readLine();
            if (line == null) throw new IOException("helper closed the connection");
            String[] p = line.split(" ");
            if (p.length < FIELDS + 2 || !p[0].equals("v3")) throw new IOException("bad reply: " + line);
            for (int i = 0; i < FIELDS; i++) snap[i] = parse(p[i + 1]);
            error = p[FIELDS + 1].equals("-") ? "" : p[FIELDS + 1].replace('_', ' ');
            snapAt = SystemClock.elapsedRealtime();
            return true;
        } catch (IOException e) {
            error = "helper link lost (" + e.getMessage() + ")";
            close();
            return false;
        }
    }

    private static double parse(String s) {
        if (s.equals("nan")) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private synchronized double get(int i) {
        if (socket == null) return Double.NaN;
        if (SystemClock.elapsedRealtime() - snapAt > 150) refresh();
        return snap[i];
    }

    /** Charging snapshot from the helper (cached for a couple of seconds), or null if unavailable. */
    @Override public synchronized double[] charging() {
        if (socket == null) return null;
        long now = SystemClock.elapsedRealtime();
        if (chg != null && now - chgAt < 2000) return chg;
        try {
            out.println("chg");
            String line = in.readLine();
            if (line == null) throw new IOException("helper closed the connection");
            String[] p = line.split(" ");
            if (p.length < 6 || !p[0].equals("c1")) throw new IOException("bad reply: " + line);
            double[] r = new double[5];
            boolean any = false;
            for (int i = 0; i < 5; i++) {
                r[i] = parse(p[i + 1]);
                if (!Double.isNaN(r[i])) any = true;
            }
            chg = any ? r : null;
            chgAt = now;
            return chg;
        } catch (IOException e) {
            error = "helper link lost (" + e.getMessage() + ")";
            close();
            return null;
        }
    }

    /** Asks the helper process to stop. */
    synchronized void requestExit() {
        if (out != null) out.println("exit");
    }

    synchronized boolean connected() {
        return socket != null;
    }

    synchronized void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
        socket = null;
        in = null;
        out = null;
    }

    @Override public String name() { return "BYD API via helper"; }

    @Override public double speedKmh() { return get(0); }

    @Override public double powerLevel() { return get(1); }

    @Override public double socPercent() { return get(2); }

    @Override public double totalElecConsumption() { return get(3); }

    @Override public double totalDrivingTime() { return get(4); }

    @Override public double odometerKm() { return get(5); }

    @Override public double journeyKm() { return get(12); }

    @Override public double totalFuelLitres() { return get(6); }

    @Override public double evMileageKm() { return get(7); }

    @Override public double hevMileageKm() { return get(8); }

    @Override public double fuelPercent() { return get(9); }

    @Override public double elecRangeKm() { return get(10); }

    @Override public double fuelRangeKm() { return get(11); }

    @Override public String lastError() { return error; }
}
