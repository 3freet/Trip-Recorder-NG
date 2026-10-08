package org.triprecorderng;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Derived numbers: a simple driving score, chart series and personal records. */
final class Stats {
    private Stats() {}

    static final int MILEAGE = 0;
    static final int CONSUMPTION = 1;
    static final int SCORE = 2;
    static final int TRIPS = 3;
    static final int COST = 4;
    static final int FUEL = 5;

    /**
     * Simplified driving score: 100 minus 5 points per hard acceleration or hard braking event.
     * (The stock app uses a weighted formula with settings it downloads from BYD's server.)
     */
    static double score(Trip t) {
        if (t.distanceKm < 0.5) return Double.NaN;
        double s = 100 - 5.0 * (t.hardAccel + t.hardBrake);
        return Math.max(0, Math.min(100, s));
    }

    static final class Series {
        double[] values;
        String[] labels;
    }

    private static Calendar startOf(long ms, boolean monthly) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (monthly) c.set(Calendar.DAY_OF_MONTH, 1);
        return c;
    }

    static Series series(android.content.Context ctx, List<Trip> trips, boolean monthly, int metric) {
        int n = monthly ? 12 : 14;
        Series s = new Series();
        s.values = new double[n];
        s.labels = new String[n];
        SimpleDateFormat fmt = new SimpleDateFormat(monthly ? "MMM" : "d/M", L.dateLocale());
        Calendar cur = startOf(System.currentTimeMillis(), monthly);
        for (int i = n - 1; i >= 0; i--) {
            Calendar from = (Calendar) cur.clone();
            Calendar to = (Calendar) cur.clone();
            if (monthly) to.add(Calendar.MONTH, 1); else to.add(Calendar.DAY_OF_MONTH, 1);
            double km = 0, energy = 0, energyKm = 0, scoreSum = 0, cost = 0, fuel = 0;
            int scored = 0, count = 0;
            for (Trip t : trips) {
                if (t.startMs < from.getTimeInMillis() || t.startMs >= to.getTimeInMillis()) continue;
                count++;
                km += t.distanceKm;
                double e = t.energyUsed();
                if (!Double.isNaN(e) && t.distanceKm >= 0.5) {
                    energy += e;
                    energyKm += t.distanceKm;
                }
                double sc = score(t);
                if (!Double.isNaN(sc)) {
                    scoreSum += sc;
                    scored++;
                }
                double c = Cost.total(t, ctx);
                if (!Double.isNaN(c)) cost += c;
                double f = t.fuelUsed();
                if (!Double.isNaN(f)) fuel += f;
            }
            double v;
            switch (metric) {
                case MILEAGE: v = km; break;
                case CONSUMPTION: v = energyKm > 0 ? energy / energyKm * 100.0 : 0; break;
                case SCORE: v = scored > 0 ? scoreSum / scored : 0; break;
                case COST: v = cost; break;
                case FUEL: v = fuel; break;
                default: v = count; break;
            }
            s.values[i] = v;
            s.labels[i] = fmt.format(from.getTime());
            if (monthly) cur.add(Calendar.MONTH, -1); else cur.add(Calendar.DAY_OF_MONTH, -1);
        }
        return s;
    }

    static final class Records {
        Trip longest;
        Trip longestTime;
        Trip fastest;
        Trip mostEfficient;
        Trip bestScore;
        double totalKm;
        long totalSec;
        int count;
    }

    static Records records(List<Trip> trips) {
        Records r = new Records();
        for (Trip t : trips) {
            r.count++;
            r.totalKm += t.distanceKm;
            r.totalSec += t.durationSec();
            if (r.longest == null || t.distanceKm > r.longest.distanceKm) r.longest = t;
            if (r.longestTime == null || t.durationSec() > r.longestTime.durationSec()) r.longestTime = t;
            if (r.fastest == null || t.maxSpeedKmh > r.fastest.maxSpeedKmh) r.fastest = t;
            double eff = t.energyPer100Km();
            if (!Double.isNaN(eff) && eff > 0 && t.distanceKm >= 5
                    && (r.mostEfficient == null || eff < r.mostEfficient.energyPer100Km())) {
                r.mostEfficient = t;
            }
            double sc = score(t);
            if (!Double.isNaN(sc) && t.distanceKm >= 2
                    && (r.bestScore == null || sc > score(r.bestScore))) {
                r.bestScore = t;
            }
        }
        return r;
    }

    // ---- environmental contribution (stock app: CO2 reduced, trees) ----
    /** A typical petrol car: 8 L per 100 km at 2.31 kg CO2 per litre. */
    private static final double PETROL_KG_PER_KM = 0.08 * 2.31;
    private static final double FUEL_KG_PER_LITRE = 2.31;
    /** Rough emissions of grid electricity; a documented assumption, not a measurement. */
    private static final double GRID_KG_PER_KWH = 0.40;
    private static final double KG_PER_TREE_YEAR = 21.77;

    static final class Co2 {
        double kg;
        double trees;
        int trips;
    }

    /** CO2 avoided compared with a petrol car, over the trips that recorded both fuel and energy. */
    static Co2 co2Avoided(List<Trip> trips) {
        Co2 r = new Co2();
        for (Trip t : trips) {
            double fuel = t.fuelUsed(), energy = t.energyUsed();
            if (Double.isNaN(fuel) || Double.isNaN(energy) || t.distanceKm < 0.5) continue;
            r.kg += t.distanceKm * PETROL_KG_PER_KM - (fuel * FUEL_KG_PER_LITRE + energy * GRID_KG_PER_KWH);
            r.trips++;
        }
        r.kg = Math.max(0, r.kg);
        r.trees = r.kg / KG_PER_TREE_YEAR;
        return r;
    }

    // ---- driving level (names from the stock app's "My achievement"; steps by distance driven) ----
    static final double[] LEVEL_KM = {0, 100, 300, 600, 1000, 2000, 3500, 5000, 7500, 10000, 15000, 20000,
            30000, 40000, 60000, 100000};
    static String levelName(int level) {
        switch (level) {
            case 0: return L.t("Novice on the road");
            case 1: return L.t("Familiar with the road");
            case 2: return L.t("Handling it with ease");
            case 3: return L.t("In command");
            case 4: return L.t("A cut above");
            case 5: return L.t("Fluent driver");
            case 6: return L.t("Skilled driver");
            case 7: return L.t("Veteran driver");
            case 8: return L.t("Steady as a mountain");
            case 9: return L.t("Solid as a rock");
            case 10: return L.t("Outdrives everyone");
            case 11: return L.t("Mastery");
            case 12: return L.t("Wizard of the wheel");
            case 13: return L.t("Peak performance");
            case 14: return L.t("One with the car");
            default: return L.t("Mountain-pass legend");
        }
    }

    static int level(double totalKm) {
        int lv = 0;
        for (int i = 0; i < LEVEL_KM.length; i++) if (totalKm >= LEVEL_KM[i]) lv = i;
        return lv;
    }
}
