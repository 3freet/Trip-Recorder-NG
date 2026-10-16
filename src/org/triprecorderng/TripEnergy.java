package org.triprecorderng;

/**
 * What a trip used, split the way a plug-in hybrid needs.
 *
 * <p>The car's cumulative energy counter is <b>net</b>: it already subtracts what the engine (and regeneration) puts
 * back into the battery. So kWh divided by the electric kilometres looks better than the driving really was, and the
 * litres of the hybrid part include the fuel that charged the battery. The whole-trip figures (kWh, litres, cost) are
 * exact; the electric figure here adds back what the engine charged, which is estimated from the rises of the battery
 * percentage while the car was moving.
 */
final class TripEnergy {
    /** Per-mode figures need at least this many km of that mode (the car's km counters step 1 km). */
    static final double MIN_MODE_KM = 5;
    /** The electric estimate needs the battery to have dropped at least this many points. */
    static final double MIN_DROP_PCT = 8;
    /** Less fuel than this counts as none (the fuel counter steps 0.1 L). */
    static final double FUEL_EPS = 0.05;
    /** A swing of the battery percentage must be at least this many points to count (it moves in 1 point steps). */
    static final double SWING_PCT = 2;

    /** The trip burned fuel: its kWh per 100 km mixes two ways of driving. */
    final boolean hybrid;
    /** Net battery energy per 100 km over the whole trip, NaN when unknown. */
    final double kwhPer100;
    /** Fuel per 100 km over the whole trip, NaN when none was burned. */
    final double lPer100;
    /** Electric driving, kWh per 100 km, with what the engine charged added back. NaN when it cannot be estimated. */
    final double electricKwhPer100;
    /** Hybrid driving, litres per 100 km (includes the fuel that charged the battery). NaN when too short. */
    final double hybridLPer100;
    /** What the engine and regeneration put into the battery while driving: battery points and kWh. NaN if unknown. */
    final double chargedPct;
    final double chargedKwh;
    /** The battery trace was looked at (the per-mode figures above are final), not only the counters. */
    final boolean traced;

    private TripEnergy(boolean hybrid, double kwhPer100, double lPer100, double electric, double hybridL,
                       double chargedPct, double chargedKwh, boolean traced) {
        this.hybrid = hybrid;
        this.kwhPer100 = kwhPer100;
        this.lPer100 = lPer100;
        this.electricKwhPer100 = electric;
        this.hybridLPer100 = hybridL;
        this.chargedPct = chargedPct;
        this.chargedKwh = chargedKwh;
        this.traced = traced;
    }

    /** The figures that need only the trip's own counters (no battery trace). */
    static TripEnergy basic(Trip t) {
        return of(t.distanceKm, t.energyUsed(), t.fuelUsed(), t.evKm(), t.hevKm(), t.socStart, t.socEnd, null, false);
    }

    /**
     * @param socMoving battery percentage samples taken while the car was moving, in time order; null when the
     *                  trace was not read
     * @param pluggedIn the car was charging from a cable during the trip (the battery rose for another reason)
     */
    static TripEnergy of(double km, double netKwh, double fuelL, double evKm, double hevKm, double socStart,
                         double socEnd, double[] socMoving, boolean pluggedIn) {
        boolean hybrid = !Double.isNaN(fuelL) && fuelL >= FUEL_EPS;
        double kwh100 = !Double.isNaN(netKwh) && km >= 0.5 ? netKwh / km * 100.0 : Double.NaN;
        double l100 = hybrid && km >= 0.5 ? fuelL / km * 100.0 : Double.NaN;
        double hybridL = hybrid && hevKm >= MIN_MODE_KM ? fuelL / hevKm * 100.0 : Double.NaN;
        double electric = Double.NaN;
        double chargedPct = Double.NaN;
        double chargedKwh = Double.NaN;
        if (hybrid && socMoving != null && socMoving.length >= 10 && !pluggedIn && evKm >= MIN_MODE_KM
                && netKwh > 0) {
            double drop = socStart - socEnd;
            if (!Double.isNaN(drop) && drop >= MIN_DROP_PCT) {
                double kwhPerPoint = netKwh / drop;
                chargedPct = swings(socMoving, SWING_PCT)[0];
                chargedKwh = chargedPct * kwhPerPoint;
                electric = (netKwh + chargedKwh) / evKm * 100.0;
            }
        }
        return new TripEnergy(hybrid, kwh100, l100, electric, hybridL, chargedPct, chargedKwh, socMoving != null);
    }

    /**
     * Total rise and total fall of a noisy series, counting only swings of at least {@code threshold} (a zig-zag):
     * the 1 point steps of the battery percentage flicker by a point or two without meaning anything.
     *
     * @return {rises, falls}, both positive; rises - falls is the net change
     */
    static double[] swings(double[] v, double threshold) {
        double rises = 0;
        double falls = 0;
        if (v == null || v.length == 0) return new double[]{0, 0};
        double pivot = v[0];
        double ext = v[0];
        int dir = 0; // 1 going up, -1 going down, 0 not decided yet
        for (int i = 1; i < v.length; i++) {
            double x = v[i];
            if (dir >= 0 && x > ext) ext = x;
            else if (dir <= 0 && x < ext) ext = x;
            if (dir == 1 && ext - x >= threshold) {
                rises += ext - pivot;
                pivot = ext;
                dir = -1;
                ext = x;
            } else if (dir == -1 && x - ext >= threshold) {
                falls += pivot - ext;
                pivot = ext;
                dir = 1;
                ext = x;
            } else if (dir == 0) {
                if (x - pivot >= threshold) {
                    dir = 1;
                    ext = x;
                } else if (pivot - x >= threshold) {
                    dir = -1;
                    ext = x;
                }
            }
        }
        if (dir == 1) rises += ext - pivot;
        else if (dir == -1) falls += pivot - ext;
        return new double[]{rises, falls};
    }
}
