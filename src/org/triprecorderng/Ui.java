package org.triprecorderng;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colors, sizes and small view factories that follow the look of the stock Driving behavior app. */
final class Ui {
    private Ui() {}

    // Values taken from the stock app's Fang Cheng Bao theme.
    static final int BG = 0xFF0A0B0D;
    static final int BG_CENTER = 0xFF16181C;
    static final int CARD = 0x1AFFFFFF;
    static final int TEXT_SELECTED = 0xFFFFFFFF;
    static final int TEXT_VALUE = 0xCCFFFFFF;
    static final int TEXT_DATE = 0x99FFFFFF;
    static final int TEXT_LABEL = 0x66FFFFFF;
    static final int DIVIDER = 0x1AFFFFFF;
    static final int RING_TRACK = 0x33FFFFFF;
    static final int GREEN = 0xFF3FD18B;
    static final int AMBER = 0xFFF0B33A;
    static final int RED = 0xFFEB5757;
    static final int BLUE = 0xFF4AA3FF;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        // Arabic reads right to left; the lines then start at the right edge of the view (centered text stays centered)
        if (L.isArabic()) t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }

    /** Layout direction for the content area: right to left when the app language is Arabic. */
    static int contentDirection() {
        return L.isArabic() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
    }

    /** Shows a dialog laid out in the app language's direction (buttons and text on the right side). */
    static android.app.AlertDialog show(android.app.AlertDialog.Builder b) {
        android.app.AlertDialog d = b.create();
        d.show();
        try {
            d.getWindow().getDecorView().setLayoutDirection(contentDirection());
            // the stock dialog title follows the device locale, so give it the same direction as the rest
            android.view.View title = d.findViewById(
                    d.getContext().getResources().getIdentifier("alertTitle", "id", "android"));
            if (title != null) {
                title.setLayoutDirection(contentDirection());
                if (title.getParent() instanceof android.view.View) {
                    ((android.view.View) title.getParent()).setLayoutDirection(contentDirection());
                }
            }
        } catch (Throwable ignored) {
            // cosmetic only
        }
        return d;
    }

    static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable stroke(int color, int fill, float radiusDp, Context c) {
        GradientDrawable d = round(fill, radiusDp, c);
        d.setStroke(dp(c, 1), color);
        return d;
    }

    static int scoreColor(double score) {
        if (Double.isNaN(score)) return Color.GRAY;
        if (score >= 80) return GREEN;
        if (score >= 60) return AMBER;
        return RED;
    }

    /** A value over a small label, as used in the stock app's trip rows. */
    static LinearLayout stat(Context c, String value, String label, float valueSp, float labelSp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.START);
        TextView v = text(c, value, valueSp, TEXT_VALUE);
        TextView t = text(c, label, labelSp, TEXT_LABEL);
        t.setPadding(0, dp(c, 4), 0, 0);
        l.addView(v);
        l.addView(t);
        return l;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(DIVIDER);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static String f(double v, String fmt) {
        return Double.isNaN(v) ? "--" : String.format(java.util.Locale.US, fmt, v);
    }

    static String duration(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        if (h > 0) return L.f("%1$dh %2$dm", h, m);
        return L.f("%d min", m);
    }
}
