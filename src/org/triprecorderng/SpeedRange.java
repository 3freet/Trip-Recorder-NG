package org.triprecorderng;

/**
 * The speeds at which the blind-spot cameras show up (Tweaks), kept free of Android so it can be tried on its own:
 * the two knobs of the slider and the test of a speed against them.
 */
final class SpeedRange {
    /** The slider moves in steps of this many km/h, and its scale ends at {@link #TOP}, where there is no upper limit. */
    static final int STEP = 5;
    static final int TOP = 150;
    static final int DEFAULT_MIN = 0;
    static final int DEFAULT_MAX = 60;
    /** Once a picture is up, the speed may stray this far outside the range before it counts as outside (km/h). */
    static final double SLACK = 2;

    private SpeedRange() {
    }

    /**
     * Whether {@code kmh} is inside the range. An unknown speed is inside, so a missing reading never hides a picture.
     * {@code max} at {@link #TOP} means no upper limit. With a picture already {@code showing}, the edges are
     * {@link #SLACK} wider, so a speed hovering at an edge does not make it flicker.
     */
    static boolean allows(double kmh, int min, int max, boolean showing) {
        if (Double.isNaN(kmh)) return true;
        double v = Math.abs(kmh);
        double slack = showing ? SLACK : 0;
        if (v < min - slack) return false;
        return max >= TOP || v <= max + slack;
    }

    /** Puts a pair of knob positions on the scale, in whole steps, with at least one step between them. */
    static int[] clamp(int min, int max) {
        int lo = Math.max(0, Math.min(TOP - STEP, Math.round(min / (float) STEP) * STEP));
        int hi = Math.max(STEP, Math.min(TOP, Math.round(max / (float) STEP) * STEP));
        if (hi - lo < STEP) {
            if (hi >= TOP) lo = hi - STEP;
            else hi = lo + STEP;
        }
        return new int[]{lo, hi};
    }
}
