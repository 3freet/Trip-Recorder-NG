package org.triprecorderng;

import java.util.List;

/** Derived series for the trip details screen. Points are {ts, lat, lon, alt, gps km/h, car km/h, bearing, soc, kWh}. */
final class TripAnalysis {
    private TripAnalysis() {}

    static final double[] BAND_LIMITS = {30, 60, 90, 120};
    static final String[] BAND_NAMES = {"0-30", "30-60", "60-90", "90-120", "120+"};

    static double speed(double[] p) {
        double v = p.length > 5 && !Double.isNaN(p[5]) ? p[5] : p[4];
        return Double.isNaN(v) ? 0 : v;
    }

    static double[] times(List<double[]> pts) {
        double[] t = new double[pts.size()];
        for (int i = 0; i < t.length; i++) t[i] = pts.get(i)[0];
        return t;
    }

    static double[] speeds(List<double[]> pts) {
        double[] v = new double[pts.size()];
        for (int i = 0; i < v.length; i++) v[i] = speed(pts.get(i));
        return v;
    }

    static double[] soc(List<double[]> pts) {
        double[] v = new double[pts.size()];
        for (int i = 0; i < v.length; i++) v[i] = pts.get(i).length > 7 ? pts.get(i)[7] : Double.NaN;
        return v;
    }

    /** GPS altitude, smoothed with a moving average because the raw values jump by several metres. */
    static double[] altitude(List<double[]> pts) {
        int n = pts.size();
        double[] out = new double[n];
        int half = 4;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int cnt = 0;
            for (int k = Math.max(0, i - half); k <= Math.min(n - 1, i + half); k++) {
                sum += pts.get(k)[3];
                cnt++;
            }
            out[i] = sum / cnt;
        }
        // an all-zero series means the receiver gave no altitude
        boolean any = false;
        for (double v : out) if (Math.abs(v) > 0.01) any = true;
        if (!any) java.util.Arrays.fill(out, Double.NaN);
        return out;
    }

    /**
     * Energy per 100 km for each speed band, from the energy counter along the track.
     * Entries are NaN where a band has under half a kilometre. Returns null if the trip has no
     * energy readings along the track (trips recorded by older builds).
     */
    static double[] consumptionByBand(List<double[]> pts) {
        int bands = BAND_NAMES.length;
        double[] dist = new double[bands];
        double[] energy = new double[bands];
        int withElec = 0;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            if (a.length < 9 || b.length < 9 || Double.isNaN(a[8]) || Double.isNaN(b[8])) continue;
            double dt = (b[0] - a[0]) / 1000.0;
            if (dt <= 0 || dt > 15) continue;
            if (speed(a) < 1 && speed(b) < 1) continue;   // standing still: no distance, would only inflate the low-speed band
            withElec++;
            double v = (speed(a) + speed(b)) / 2.0;
            int band = 0;
            while (band < BAND_LIMITS.length && v >= BAND_LIMITS[band]) band++;
            dist[band] += v * dt / 3600.0;
            energy[band] += Math.max(0, b[8] - a[8]);
        }
        if (withElec < 5) return null;
        double[] out = new double[bands];
        for (int i = 0; i < bands; i++) {
            out[i] = dist[i] >= 0.5 ? energy[i] / dist[i] * 100.0 : Double.NaN;
        }
        return out;
    }

    /** Seconds spent above a speed, from the recorded points (gaps over 15 s are not counted). */
    static double secondsAbove(List<double[]> pts, double kmh) {
        double sec = 0;
        for (int i = 1; i < pts.size(); i++) {
            double dt = (pts.get(i)[0] - pts.get(i - 1)[0]) / 1000.0;
            if (dt <= 0 || dt > 15) continue;
            if ((speed(pts.get(i - 1)) + speed(pts.get(i))) / 2.0 >= kmh) sec += dt;
        }
        return sec;
    }

    /**
     * Whether a chart line is broken between two samples. A gap longer than gapMs breaks it, unless both
     * samples are at or below bridgeMax (a car standing still: the line runs flat through the gap).
     * bridgeMax NaN = never bridge.
     */
    static boolean breaksLine(double prevT, double t, double prevY, double y, double gapMs, double bridgeMax) {
        if (t - prevT <= gapMs) return false;
        return Double.isNaN(bridgeMax) || !(prevY <= bridgeMax && y <= bridgeMax);
    }

    /** Indices {lo, hi} of the nearest known samples at or before / at or after a time; -1 where there is none. */
    static int[] bracket(double[] t, double[] y, double want) {
        int lo = -1, hi = -1;
        for (int i = 0; i < t.length; i++) {
            if (Double.isNaN(y[i])) continue;
            if (t[i] <= want) lo = i;
            if (t[i] >= want) {
                hi = i;
                break;
            }
        }
        return new int[]{lo, hi};
    }

    /** The value at a time, interpolated in a straight line between the samples on both sides (NaN if none). */
    static double valueAt(double[] t, double[] y, double want) {
        int[] b = bracket(t, y, want);
        if (b[0] < 0 && b[1] < 0) return Double.NaN;
        if (b[0] < 0) return y[b[1]];
        if (b[1] < 0) return y[b[0]];
        if (b[0] == b[1] || t[b[1]] == t[b[0]]) return y[b[0]];
        return y[b[0]] + (y[b[1]] - y[b[0]]) * (want - t[b[0]]) / (t[b[1]] - t[b[0]]);
    }

    /**
     * Where a chart cursor goes for a touch at time want: {nearest sample index, cursor time, cursor value}.
     * On the drawn line the cursor follows the touch exactly and the value is interpolated; across a real
     * hole (line broken) it snaps to the nearest sample.
     */
    static double[] cursorAt(double[] t, double[] y, double want, double gapMs, double bridgeMax) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < t.length; i++) {
            double dd = Math.abs(t[i] - want);
            if (dd < bestD) {
                bestD = dd;
                best = i;
            }
        }
        int[] br = bracket(t, y, want);
        if (br[0] >= 0 && br[1] >= 0 && !breaksLine(t[br[0]], t[br[1]], y[br[0]], y[br[1]], gapMs, bridgeMax)) {
            return new double[]{best, want, valueAt(t, y, want)};
        }
        return new double[]{best, t[best], y[best]};
    }
}
