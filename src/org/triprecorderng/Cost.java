package org.triprecorderng;

import android.content.Context;

import java.util.Locale;

/**
 * Cost calculator like the stock app's: energy used times the electricity price, plus fuel used times
 * the fuel price. Prices the user did not set fall back to the defaults below, so a cost is always
 * calculated. It uses the energy taken from the battery (charging losses are not added). Trips
 * recorded before fuel was recorded only get their electricity part.
 */
final class Cost {
    private Cost() {}

    /** Default prices (OMR) used until the user enters their own. */
    static final double DEFAULT_ELEC_PRICE = 0.010;
    static final double DEFAULT_FUEL_PRICE = 0.239;

    /** Always true now that default prices exist; kept so a cost display can still be switched off in one place. */
    static boolean enabled(Context c) {
        return true;
    }

    static double elecPrice(Context c) {
        double p = Prefs.elecPriceSet(c);
        return Double.isNaN(p) ? DEFAULT_ELEC_PRICE : p;
    }

    static double fuelPrice(Context c) {
        double p = Prefs.fuelPriceSet(c);
        return Double.isNaN(p) ? DEFAULT_FUEL_PRICE : p;
    }

    static boolean elecIsDefault(Context c) {
        return Double.isNaN(Prefs.elecPriceSet(c));
    }

    static boolean fuelIsDefault(Context c) {
        return Double.isNaN(Prefs.fuelPriceSet(c));
    }

    static double elec(Trip t, Context c) {
        double e = t.energyUsed();
        return Double.isNaN(e) ? Double.NaN : e * elecPrice(c);
    }

    static double fuel(Trip t, Context c) {
        double f = t.fuelUsed();
        return Double.isNaN(f) ? Double.NaN : f * fuelPrice(c);
    }

    /** Sum of the parts that are known, or NaN if none is. */
    static double total(Trip t, Context c) {
        double e = elec(t, c), f = fuel(t, c);
        if (Double.isNaN(e) && Double.isNaN(f)) return Double.NaN;
        return (Double.isNaN(e) ? 0 : e) + (Double.isNaN(f) ? 0 : f);
    }

    /** What the energy added by a charging session cost, at the electricity price. */
    static double charge(Charge ch, double kwhPerPercent, Context c) {
        double e = ch.energyKwh(kwhPerPercent);
        return Double.isNaN(e) ? Double.NaN : e * elecPrice(c);
    }

    static int decimalsFor(String currency) {
        return decimals(currency);
    }

    private static int decimals(String currency) {
        switch (currency.toUpperCase(Locale.US)) {
            case "OMR": case "KWD": case "BHD": case "JOD": case "TND": case "LYD": return 3;
            case "JPY": case "KRW": case "IQD": case "VND": return 0;
            default: return 2;
        }
    }

    /** "1.234 OMR", or "--" when unknown. */
    static String money(Context c, double v) {
        if (Double.isNaN(v)) return "--";
        String cur = Prefs.currency(c);
        return String.format(Locale.US, "%." + decimals(cur) + "f", v) + " " + cur;
    }

    /** The amount without the currency, for a narrow cell. */
    static String amount(Context c, double v) {
        if (Double.isNaN(v)) return "--";
        return String.format(Locale.US, "%." + decimals(Prefs.currency(c)) + "f", v);
    }
}
