package org.triprecorderng;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Language support: the app's texts are written in English in the code and looked up in assets/ar.tsv or
 * assets/ru.tsv when the app language is Arabic or Russian (a missing entry simply stays English). The language is
 * chosen in Setting (Automatic follows the head unit's language). Digits are always Latin so numbers read the same
 * everywhere. Only Arabic changes the direction of the text; Russian reads left to right like English.
 */
final class L {
    private L() {}

    static final String AUTO = "auto";
    static final String EN = "en";
    static final String AR = "ar";
    static final String RU = "ru";

    /** The language in use: "en", "ar" or "ru". */
    private static volatile String lang = EN;
    private static volatile boolean arabic;
    private static Map<String, String> table;
    private static String tableLang = "";

    /** Loads the translation table and reads the language setting. Safe to call more than once. */
    static synchronized void init(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        String next = resolve(setting(app));
        if (!EN.equals(next) && !next.equals(tableLang)) {
            table = load(app, next);
            tableLang = next;
        }
        arabic = AR.equals(next);
        lang = next;
    }

    /** The stored choice: "auto", "en", "ar" or "ru". */
    static String setting(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext().getSharedPreferences("triprecng", Context.MODE_PRIVATE);
        return p.getString("lang", AUTO);
    }

    static void setSetting(Context ctx, String value) {
        ctx.getApplicationContext().getSharedPreferences("triprecng", Context.MODE_PRIVATE)
                .edit().putString("lang", value).apply();
        init(ctx);
    }

    private static String resolve(String setting) {
        if (AR.equals(setting) || RU.equals(setting) || EN.equals(setting)) return setting;
        String d = Locale.getDefault().getLanguage();
        return AR.equals(d) ? AR : RU.equals(d) ? RU : EN;
    }

    static boolean isArabic() {
        return arabic;
    }

    /** The language in use: "en", "ar" or "ru". */
    static String current() {
        return lang;
    }

    /** A language's own name, as it is written in the language dialog. */
    static String nameOf(String code) {
        return AR.equals(code) ? "العربية" : RU.equals(code) ? "Русский" : "English";
    }

    /** The English text, or its translation when the app language is Arabic or Russian. */
    static String t(String en) {
        Map<String, String> m = table;
        if (EN.equals(lang) || m == null) return en;
        String v = m.get(en);
        return v != null ? v : en;
    }

    /** Translated format string filled with the arguments (numbers stay Latin). */
    static String f(String enFormat, Object... args) {
        return String.format(Locale.US, t(enFormat), args);
    }

    /** Separator between parts of a header line: comma and space, Arabic comma in Arabic. */
    static String sep() {
        return arabic ? "\u060C " : ", ";
    }

    /** Text kept left-to-right inside Arabic text (file paths, signed numbers), so "+22" and "/sdcard" keep their front sign. */
    static String ltr(String s) {
        return arabic ? "\u2066" + s + "\u2069" : s;
    }

    /**
     * A phrase kept as one right-to-left unit inside Arabic text ("9.4 kWh"), so that what stands next to it (another
     * figure with a Latin unit) cannot pull its number or its unit to the other side: the number is read first, on
     * the right, then its unit to the left of it.
     */
    static String rtl(String s) {
        return arabic ? "\u2067" + s + "\u2069" : s;
    }

    /** "a per u": "1.272 OMR/100km", and in Arabic "1.272 OMR لكل 100 كم" (a slash between a Latin and an Arabic word reads badly). */
    static String per(String amount, String unit) {
        return arabic ? amount + " \u0644\u0643\u0644 " + unit : amount + "/" + unit;
    }

    /** The arrow of a change "from -> to": it points the way the eye reads, to the left in Arabic. */
    static String arrow() {
        return arabic ? "\u2190" : "\u2192";
    }

    /** Locale for dates: the head unit's own, or Arabic month and day names with Latin digits, or Russian ones. */
    static Locale dateLocale() {
        if (RU.equals(lang)) return new Locale.Builder().setLanguage("ru").setRegion("RU").build();
        if (!arabic) return Locale.getDefault();
        return new Locale.Builder().setLanguage("ar").setRegion("OM").setUnicodeLocaleKeyword("nu", "latn").build();
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, String> load(Context ctx, String code) {
        Map<String, String> m = new HashMap<>();
        try {
            InputStream in = ctx.getAssets().open(code + ".tsv");
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    m.put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)));
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Diag.log("could not load the texts for " + code, t);
        }
        return m;
    }
}
