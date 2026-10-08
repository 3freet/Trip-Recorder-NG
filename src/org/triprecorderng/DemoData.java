package org.triprecorderng;

import java.util.ArrayList;
import java.util.List;

/** Sample trips for checking the screens. Only created when the app is started with an extra. */
final class DemoData {
    private DemoData() {}

    static void insert(android.content.Context ctx) {
        TripDb db = TripDb.get(ctx);
        long now = System.currentTimeMillis();
        double[][] spec = {
                // distance km, minutes, hours ago, max speed, soc start, soc end, kWh, accel, brake
                {12.4, 34, 3, 92, 61, 56, 2.1, 1, 0},
                {3.2, 9, 27, 58, 56, 55, 0.6, 0, 1},
                {48.7, 62, 52, 118, 90, 72, 8.6, 4, 3}};
        for (int i = 0; i < spec.length; i++) {
            double[] s = spec[i];
            Trip t = new Trip();
            t.id = "demo-" + (i + 1);
            t.mode = "demo";
            t.startMs = now - (long) (s[2] * 3600_000);
            t.endMs = t.startMs + (long) (s[1] * 60_000);
            t.lastUpdateMs = t.endMs;
            t.ended = true;
            t.distanceKm = s[0];
            t.movingSec = s[1] * 60 * 0.85;
            t.idleSec = s[1] * 60 * 0.15;
            t.maxSpeedKmh = s[3];
            t.socStart = s[4];
            t.socEnd = s[5];
            t.elecStart = 4000;
            t.elecEnd = 4000 + s[6];
            t.odoStartKm = 12000;
            t.odoEndKm = 12000 + Math.round(s[0]);
            // plug-in hybrid counters: the long trip burned some fuel, the short ones were all electric
            double fuelL = i == 2 ? 3.1 : 0;
            double hevKm = i == 2 ? 21 : 0;
            t.fuelStart = 1400;
            t.fuelEnd = 1400 + fuelL;
            t.evStart = 17000;
            t.evEnd = 17000 + Math.round(s[0] - hevKm);
            t.hevStart = 13000;
            t.hevEnd = 13000 + hevKm;
            t.hardAccel = (int) s[7];
            t.hardBrake = (int) s[8];
            t.startLat = 51.50;
            t.startLon = -0.12;
            db.upsert(t);
            List<double[]> pts = new ArrayList<>();
            int n = Math.max(100, (int) (s[1] * 6) + 1); // a sample every ~10 s, like a real recording
            long step = (t.endMs - t.startMs) / (n - 1);
            double[] speed = new double[n];
            double weight = 0;
            for (int k = 0; k < n; k++) {
                double a = k / (n - 1.0);
                double ramp = Math.min(1, Math.min(a, 1 - a) * 8);
                speed[k] = Math.max(0, s[3] * (0.62 + 0.38 * Math.sin(a * 9.0 + i)) * ramp);
                weight += Math.pow(speed[k] / 100.0, 1.4) + 0.02;
            }
            double elec = t.elecStart;
            for (int k = 0; k < n; k++) {
                double a = k / (n - 1.0);
                double lat = 51.50 + 0.04 * Math.sin(a * 3.0 + i) * a + 0.03 * a;
                double lon = -0.12 + 0.06 * a + 0.015 * Math.cos(a * 5.0 + i);
                if (k > 0) elec += (s[6] * (Math.pow(speed[k] / 100.0, 1.4) + 0.02) / weight);
                double soc = s[4] + (s[5] - s[4]) * a;
                double alt = 10 + 40 * Math.sin(a * 4.0 + i) + (k % 3);
                pts.add(new double[]{t.startMs + k * step, lat, lon, alt, speed[k], speed[k], 90,
                        Math.round(soc), Math.round(elec * 10) / 10.0});
            }
            db.addPoints(t.id, pts);
            List<double[]> ev = new ArrayList<>();
            for (int e = 0; e < (int) s[7]; e++) {
                double[] p = pts.get(15 + e * (n - 30) / Math.max(1, (int) s[7]));
                ev.add(new double[]{p[0], 0, p[1], p[2], 3.1});
            }
            for (int e = 0; e < (int) s[8]; e++) {
                double[] p = pts.get(25 + e * (n - 40) / Math.max(1, (int) s[8]));
                ev.add(new double[]{p[0], 1, p[1], p[2], -3.6});
            }
            db.addEvents(t.id, ev);
        }
    }

    /** Two finished charging sessions: a slow AC one (energy estimated) and a DC one with the car's own kWh. */
    static void insertCharges(android.content.Context ctx) {
        TripDb db = TripDb.get(ctx);
        long now = System.currentTimeMillis();
        double[][] spec = {
                // hours ago, minutes, soc start, soc end, gun, kWh from the car (NaN = none), max kW
                {30, 140, 38, 82, 2, Double.NaN, Double.NaN},
                {8, 36, 20, 80, 3, 19.2, 86.0}};
        for (int i = 0; i < spec.length; i++) {
            double[] s = spec[i];
            Charge c = new Charge();
            c.id = "demo-charge-" + (i + 1);
            c.startMs = now - (long) (s[0] * 3600_000);
            c.endMs = c.startMs + (long) (s[1] * 60_000);
            c.lastUpdateMs = c.endMs;
            c.ended = true;
            c.socStart = s[2];
            c.socEnd = s[3];
            c.gun = (int) s[4];
            c.kwhSignal = s[5];
            c.maxPowerKw = s[6];
            c.lat = 51.51;
            c.lon = -0.10;
            c.partial = i == 0; // the slow AC one stands for a session the unit slept through
            db.upsertCharge(c);
            List<double[]> pts = new ArrayList<>();
            int n = (int) s[1];
            for (int k = 0; k <= n; k++) {
                double a = k / (double) n;
                // DC charging slows down above ~60 %
                double f = s[4] == 3 ? (a < 0.6 ? a * 0.85 / 0.6 : 0.85 + (a - 0.6) * 0.15 / 0.4) : a;
                pts.add(new double[]{c.startMs + k * 60_000L, Math.round(s[2] + (s[3] - s[2]) * f),
                        s[4] == 3 ? 86 - 40 * Math.max(0, a - 0.55) : Double.NaN});
            }
            db.addChargePoints(c.id, pts);
        }
    }

    static void clear(android.content.Context ctx) {
        TripDb db = TripDb.get(ctx);
        for (int i = 1; i <= 3; i++) db.delete("demo-" + i);
        for (int i = 1; i <= 2; i++) db.deleteCharge("demo-charge-" + i);
    }
}
