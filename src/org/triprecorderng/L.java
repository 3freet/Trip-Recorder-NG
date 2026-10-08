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
 * Language support: the app's texts are written in English in the code and looked up in assets/ar.tsv when
 * the app language is Arabic (a missing entry simply stays English). The language is chosen in Setting
 * (Automatic follows the head unit's language). Digits are always Latin so numbers read the same everywhere.
 */
final class L {
    private L() {}

    static final String AUTO = "auto";
    static final String EN = "en";
    static final String AR = "ar";

    private static volatile boolean arabic;
    private static Map<String, String> table;

    /** Loads the translation table and reads the language setting. Safe to call more than once. */
    static synchronized void init(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        arabic = resolve(setting(app));
        if (table == null) table = load(app);
    }

    /** The stored choice: "auto", "en" or "ar". */
    static String setting(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext().getSharedPreferences("triprecng", Context.MODE_PRIVATE);
        return p.getString("lang", AUTO);
    }

    static void setSetting(Context ctx, String value) {
        ctx.getApplicationContext().getSharedPreferences("triprecng", Context.MODE_PRIVATE)
                .edit().putString("lang", value).apply();
        init(ctx);
    }

    private static boolean resolve(String setting) {
        if (AR.equals(setting)) return true;
        if (EN.equals(setting)) return false;
        return "ar".equals(Locale.getDefault().getLanguage());
    }

    static boolean isArabic() {
        return arabic;
    }

    /** The English text, or its Arabic translation when the app language is Arabic. */
    static String t(String en) {
        if (!arabic || table == null) return en;
        String v = table.get(en);
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

    /** Locale for dates: the head unit's own, or Arabic month and day names with Latin digits. */
    static Locale dateLocale() {
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

    private static Map<String, String> load(Context ctx) {
        Map<String, String> m = new HashMap<>();
        try {
            InputStream in = ctx.getAssets().open("ar.tsv");
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
            Diag.log("could not load the Arabic texts", t);
        }
        return m;
    }
}
