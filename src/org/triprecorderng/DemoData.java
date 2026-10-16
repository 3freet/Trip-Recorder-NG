package org.triprecorderng;

import java.util.ArrayList;
import java.util.List;

/** Sample trips for checking the screens. Only created when the app is started with an extra. */
final class DemoData {
    private DemoData() {}

    private static final double[][] TRIPS = {
            // distance km, minutes, hours ago, max speed, soc start, soc end, kWh (net), accel, brake,
            // fuel L, hybrid km, battery points the engine put back
            {12.4, 34, 3, 92, 61, 55, 1.9, 1, 0, 0, 0, 0},
            {3.2, 9, 27, 58, 55, 54, 0.5, 0, 1, 0, 0, 0},
            {48.7, 62, 52, 118, 90, 76, 4.6, 4, 3, 2.4, 19, 5},
            {9.8, 22, 78, 76, 80, 77, 1.5, 0, 0, 0, 0, 0},
            {15.1, 28, 100, 88, 85, 80, 2.4, 1, 1, 0, 0, 0},
            {6.4, 15, 124, 64, 79, 77, 0.9, 0, 0, 0, 0, 0},
            {22.3, 37, 150, 102, 100, 93, 3.4, 2, 1, 0, 0, 0}};

    static void insert(android.content.Context ctx) {
        TripDb db = TripDb.get(ctx);
        long now = System.currentTimeMillis();
        double[][] spec = TRIPS;
        for (int i = 0; i < spec.length; i++) {
            double[] s = spec[i];
            Trip t = new Trip();
            t.id = "demo-" + (i + 1);
            t.mode = "demo";
            t.startMs = now - (long) (s[2] * 3600_000);
            // one trip stayed open after the drive: the car stood parked with the air conditioning on
            double parkedMin = i == 5 ? 95 : 0;
            double parkedKwh = i == 5 ? 1.3 : 0;
            long driveEndMs = t.startMs + (long) (s[1] * 60_000);
            t.endMs = driveEndMs + (long) (parkedMin * 60_000);
            t.lastUpdateMs = t.endMs;
            t.ended = true;
            t.distanceKm = s[0];
            t.movingSec = s[1] * 60 * 0.85;
            t.idleSec = s[1] * 60 * 0.15 + parkedMin * 60;
            t.maxSpeedKmh = s[3];
            t.socStart = s[4];
            t.socEnd = s[5];
            t.elecStart = 4000;
            t.elecEnd = 4000 + s[6] + parkedKwh;
            t.driveKwh = s[6];
            t.driveSec = s[1] * 60;
            t.odoStartKm = 12000;
            t.odoEndKm = 12000 + Math.round(s[0]);
            // plug-in hybrid counters: the long trip burned some fuel, the others were all electric
            double fuelL = s[9];
            double hevKm = s[10];
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
            long step = (driveEndMs - t.startMs) / (n - 1);
            double[] speed = new double[n];
            double weight = 0;
            for (int k = 0; k < n; k++) {
                double a = k / (n - 1.0);
                double ramp = Math.min(1, Math.min(a, 1 - a) * 8);
                speed[k] = Math.max(0, s[3] * (0.62 + 0.38 * Math.sin(a * 9.0 + i)) * ramp);
                weight += Math.pow(speed[k] / 100.0, 1.4) + 0.02;
            }
            double elec = t.elecStart;
            // the battery level falls, rises while the engine charges it (45 to 60 % of the way), then falls again
            double charged = s[11];
            double fall = s[4] - s[5] + charged;
            for (int k = 0; k < n; k++) {
                double a = k / (n - 1.0);
                double lat = 51.50 + 0.04 * Math.sin(a * 3.0 + i) * a + 0.03 * a;
                double lon = -0.12 + 0.06 * a + 0.015 * Math.cos(a * 5.0 + i);
                if (k > 0) elec += (s[6] * (Math.pow(speed[k] / 100.0, 1.4) + 0.02) / weight);
                double soc = charged > 0
                        ? s[4] - fall * 0.5 * Math.min(a, 0.45) / 0.45 + charged * Math.max(0, Math.min(1, (a - 0.45) / 0.15))
                        - fall * 0.5 * Math.max(0, a - 0.6) / 0.4
                        : s[4] + (s[5] - s[4]) * a;
                double alt = 10 + 40 * Math.sin(a * 4.0 + i) + (k % 3);
                pts.add(new double[]{t.startMs + k * step, lat, lon, alt, speed[k], speed[k], 90,
                        Math.round(soc), Math.round(elec * 10) / 10.0});
            }
            if (parkedMin > 0) {
                double[] end = pts.get(pts.size() - 1);
                for (int m = 5; m <= parkedMin; m += 5) {
                    pts.add(new double[]{driveEndMs + m * 60_000L, end[1], end[2], end[3], 0, 0, 90, end[7],
                            Math.round((end[8] + parkedKwh * m / parkedMin) * 10) / 10.0});
                }
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
        for (int i = 1; i <= TRIPS.length; i++) db.delete("demo-" + i);
        for (int i = 1; i <= 2; i++) db.deleteCharge("demo-charge-" + i);
    }
}
