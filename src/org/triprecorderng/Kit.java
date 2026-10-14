package org.triprecorderng;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The look shared by the Tweaks and Setting screens: colors, sizes and the small views they are built from.
 * Sizes follow the 800x450 design scaled to sit with the rest of the app (1.2 dp or sp per design pixel).
 */
final class Kit {
    static final int CARD = 0xFF161B21;
    static final int SHEET = 0xFF1B2128;
    static final int CODE = 0xFF0F1318;
    static final int TEXT = 0xFFEEF1F4;
    static final int SOFT = 0xFFD5DBE1;
    static final int MUTED = 0xFF9AA4AE;
    static final int ACCENT = 0xFF35D0BA;
    static final int ACCENT_SOFT = 0x2935D0BA;
    static final int ON_ACCENT = 0xFF04201C;
    static final int OFF_TRACK = 0xFF3A434D;
    static final int OFF_THUMB = 0xFFAEB7C0;
    static final int BUSY_BG = 0xFF2A323B;
    static final int NEUTRAL_BG = 0xFF252C34;
    static final int AMBER = 0xFFF2B24C;
    static final int BLUE = 0xFF5B8DEF;
    static final int DOT_OFF = 0xFF6B7682;
    static final int LINE = 0x12FFFFFF;
    static final int SCRIM = 0x9E040608;

    static final int REGULAR = 0;
    static final int MEDIUM = 1;
    static final int BOLD = 2;

    final Context c;
    final float scale; // dp (and sp) per design pixel

    Kit(Context c) {
        this(c, 1.2f);
    }

    Kit(Context c, float scale) {
        this.c = c;
        this.scale = scale;
    }

    /**
     * A number with its unit: digits (with their point and sign) keep their size, every other character shrinks
     * to {@code smallFraction} of it ("34 min" gets a big 34, "1h 12m" a big 1 and 12).
     */
    static CharSequence numUnit(String s, float smallFraction) {
        boolean anyDigit = false;
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) anyDigit = true;
        if (!anyDigit) return s;
        android.text.SpannableString sp = new android.text.SpannableString(s);
        int i = 0;
        while (i < s.length()) {
            if (isNumberChar(s, i)) {
                i++;
                continue;
            }
            int from = i;
            while (i < s.length() && !isNumberChar(s, i)) i++;
            sp.setSpan(new android.text.style.RelativeSizeSpan(smallFraction), from, i,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    /** A value followed by its unit, the unit shrunk as a whole (so "kWh/100km" keeps no big digits). */
    static CharSequence valueUnit(String value, String unit, float smallFraction) {
        android.text.SpannableString sp = new android.text.SpannableString(value + " " + unit);
        sp.setSpan(new android.text.style.RelativeSizeSpan(smallFraction), value.length(), sp.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** The color of a drive score: teal when good, amber when so-so, red when poor. */
    static int scoreColor(double score) {
        if (Double.isNaN(score) || score >= 80) return ACCENT;
        return score >= 60 ? AMBER : 0xFFEB5757;
    }

    private static boolean isNumberChar(String s, int i) {
        char ch = s.charAt(i);
        if (Character.isDigit(ch)) return true;
        boolean prevDigit = i > 0 && Character.isDigit(s.charAt(i - 1));
        boolean nextDigit = i + 1 < s.length() && Character.isDigit(s.charAt(i + 1));
        if ((ch == '.' || ch == ',') && prevDigit && nextDigit) return true;
        return (ch == '-' || ch == '−' || ch == '+') && nextDigit;
    }

    int d(float designPx) {
        return Ui.dp(c, designPx * scale);
    }

    TextView label(String s, float designPx, int color, int weight) {
        TextView t = Ui.text(c, s, designPx * scale, color);
        t.setTypeface(weight == BOLD ? Typeface.DEFAULT_BOLD
                : weight == MEDIUM ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        return t;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(Ui.round(CARD, 18 * scale, c));
        return l;
    }

    View line(int insetStartDesign) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMarginStart(d(insetStartDesign));
        lp.setMarginEnd(d(16));
        v.setLayoutParams(lp);
        return v;
    }

    LinearLayout.LayoutParams gap(int w, int h, float weight, float topDesign) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.topMargin = d(topDesign);
        return lp;
    }

    /** A small heading above a card. */
    TextView header(String text) {
        TextView t = label(text, 13, MUTED, BOLD);
        t.setPadding(d(6), 0, d(6), d(6));
        return t;
    }

    /** A rounded button: the main action of a screen in the accent color, the others quiet. */
    TextView pill(String text, boolean primary) {
        TextView t = label(text, 14, primary ? ON_ACCENT : SOFT, BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(d(96));
        t.setPadding(d(16), 0, d(16), 0);
        t.setBackground(Ui.round(primary ? ACCENT : NEUTRAL_BG, 20 * scale, c));
        return t;
    }

    /** One row of a card: a title with a description under it, and a control (switch or button) at the end. */
    final class Row {
        final LinearLayout view = column();
        final TextView title;
        final TextView desc;
        final View control;
        private final LinearLayout body = row();

        Row(String titleText, String descText, View control, boolean sub) {
            this.control = control;
            body.setPadding(d(sub ? 30 : 16), d(10), d(16), d(10));
            LinearLayout text = column();
            title = label(titleText, sub ? 15 : 16, TEXT, BOLD);
            text.addView(title);
            desc = label(descText, 12.5f, MUTED, MEDIUM);
            desc.setPadding(0, d(3), 0, 0);
            desc.setVisibility(descText.isEmpty() ? View.GONE : View.VISIBLE);
            text.addView(desc);
            body.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            if (control != null) {
                LinearLayout.LayoutParams lp = control instanceof SwitchView
                        ? new LinearLayout.LayoutParams(d(52), d(30))
                        : new LinearLayout.LayoutParams(-2, d(40));
                lp.setMarginStart(d(14));
                body.addView(control, lp);
            }
            view.addView(body, new LinearLayout.LayoutParams(-1, -2));
        }

        void setDesc(String s) {
            if (!s.contentEquals(desc.getText())) desc.setText(s);
            desc.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        }

        void setVisible(boolean v) {
            view.setVisibility(v ? View.VISIBLE : View.GONE);
        }

        void onClick(View.OnClickListener l) {
            body.setOnClickListener(l);
        }

        /** The thin line above this row, used for every row but the first of a card. */
        void divider() {
            view.addView(line(16), 0);
        }
    }

    Row switchRow(String title, String desc, SwitchView sw, boolean sub) {
        return new Row(title, desc, sw, sub);
    }

    Row buttonRow(String title, String desc, String button, boolean primary) {
        return new Row(title, desc, pill(button, primary), false);
    }

    /** One choice of a segmented control: the selected one carries the accent tint. */
    TextView segment(String text, boolean selected, View.OnClickListener l) {
        TextView t = label(text, 14, selected ? ACCENT : SOFT, BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(d(16), 0, d(16), 0);
        t.setMinHeight(d(36));
        t.setBackground(Ui.round(selected ? ACCENT_SOFT : 0x00000000, 18 * scale, c));
        t.setOnClickListener(l);
        return t;
    }

    /** The rounded track that holds segments. */
    LinearLayout segmented() {
        LinearLayout l = row();
        l.setBackground(Ui.round(SHEET, 22 * scale, c));
        l.setPadding(d(4), d(4), d(4), d(4));
        return l;
    }

    /** An outlined chip: an icon, what it is and its value (the value view is kept for updating). */
    final class Chip {
        final LinearLayout view = row();
        final TextView value;

        Chip(int icon, int iconColor, String label, String text) {
            view.setPadding(d(10), 0, d(12), 0);
            view.setBackground(Ui.stroke(LINE, 0x00000000, 19 * scale, c));
            view.addView(new IconView(c, icon, iconColor), new LinearLayout.LayoutParams(d(17), d(17)));
            if (!label.isEmpty()) {
                TextView l = label(label, 12.5f, MUTED, MEDIUM);
                l.setPaddingRelative(d(8), 0, 0, 0);
                l.setSingleLine(true);
                view.addView(l);
            }
            value = label(text, 15, TEXT, BOLD);
            value.setPaddingRelative(d(8), 0, 0, 0);
            value.setSingleLine(true);
            view.addView(value);
        }
    }

    /** A thin bar with a lit stretch: a battery going from one level to another (or just what is left). */
    static final class RangeBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float from;
        private float to;
        private int color = ACCENT;

        RangeBar(Context c) {
            super(c);
        }

        /** Levels in percent; {@code from} may be NaN to light the bar from its start up to {@code to}. */
        void set(double fromPct, double toPct, int barColor) {
            float t = (float) Math.max(0, Math.min(100, Double.isNaN(toPct) ? 0 : toPct)) / 100f;
            float f = Double.isNaN(fromPct) ? 0 : (float) Math.max(0, Math.min(100, fromPct)) / 100f;
            if (f > t) {
                float x = f;
                f = t;
                t = x;
            }
            if (f == from && t == to && barColor == color) return;
            from = f;
            to = t;
            color = barColor;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) cv.scale(-1f, 1f, w / 2f, h / 2f);
            p.setColor(0x1AFFFFFF);
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, h / 2f, h / 2f, p);
            if (to > from) {
                p.setColor(color);
                r.set(w * from, 0, w * to, h);
                cv.drawRoundRect(r, h / 2f, h / 2f, p);
            }
        }
    }

    /** Wraps a scroll view so its bottom fades into the card while there is more to scroll to. */
    static android.widget.FrameLayout fadedScroll(Context c, final android.widget.ScrollView sv, int fadePx,
                                                  int cardColor) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(c);
        box.addView(sv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        final View fade = new View(c);
        fade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{cardColor & 0x00FFFFFF, cardColor}));
        box.addView(fade, new android.widget.FrameLayout.LayoutParams(-1, fadePx, Gravity.BOTTOM));
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                View child = sv.getChildAt(0);
                boolean more = child != null && child.getBottom() - (sv.getHeight() + y) > 4;
                fade.setVisibility(more ? View.VISIBLE : View.GONE);
            }
        });
        sv.post(new Runnable() {
            @Override public void run() {
                View child = sv.getChildAt(0);
                boolean more = child != null && child.getBottom() - (sv.getHeight() + sv.getScrollY()) > 4;
                fade.setVisibility(more ? View.VISIBLE : View.GONE);
            }
        });
        return box;
    }

    /** A list of items grouped under headers: a String item is a header, anything else a row. */
    abstract static class GroupedAdapter extends android.widget.BaseAdapter {
        final java.util.List<Object> items;

        GroupedAdapter(java.util.List<Object> items) {
            this.items = items;
        }

        abstract View header(String text);

        abstract View row(Object item);

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return items.get(i) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int i) { return !(items.get(i) instanceof String); }
        @Override public View getView(int i, View convert, android.view.ViewGroup parent) {
            Object o = items.get(i);
            return o instanceof String ? header((String) o) : row(o);
        }
    }

    /** Puts rows into a card, with a thin line between them. */
    void fill(LinearLayout card, Row... rows) {
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) rows[i].divider();
            card.addView(rows[i].view, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** A switch like the design's: a rounded track with a round thumb that sits at the end when on. */
    static final class SwitchView extends View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        SwitchView(Context c) {
            super(c);
        }

        void setOn(boolean v) {
            if (on != v) {
                on = v;
                invalidate();
            }
        }

        boolean isOn() {
            return on;
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            p.setColor(on ? ACCENT : OFF_TRACK);
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, h / 2f, h / 2f, p);
            float pad = h * 0.1f;
            float rad = h / 2f - pad;
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean atRight = on != rtl;
            float cx = atRight ? w - pad - rad : pad + rad;
            p.setColor(on ? 0xFFFFFFFF : OFF_THUMB);
            cv.drawCircle(cx, h / 2f, rad, p);
        }
    }

    /** The few small icons the pages use, drawn with Canvas strokes. */
    static final class IconView extends View {
        static final int CODE_ICON = 0;
        static final int INFO = 1;
        static final int CHECK = 2;
        static final int CLOSE = 3;
        static final int BOLT = 4;
        static final int SPEED = 5;
        static final int BATTERY = 6;
        static final int PULSE = 7;
        static final int PUMP = 8;
        static final int COIN = 9;
        static final int CHEVRON = 10;
        static final int LINK = 11;
        static final int ARROW_DOWN = 12;
        static final int ARROW_UP = 13;
        static final int ROUTE = 14;
        static final int LIST = 15;
        static final int CLOCK = 16;
        static final int LEAF = 17;
        static final int STAR = 18;
        static final int TROPHY = 19;
        private int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final RectF box = new RectF();
        private float x0, y0, u;

        IconView(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
        }

        void setColor(int color) {
            if (p.getColor() != color) {
                p.setColor(color);
                invalidate();
            }
        }

        void setKind(int k) {
            if (kind != k) {
                kind = k;
                invalidate();
            }
        }

        private float X(float v) {
            return x0 + v * u;
        }

        private float Y(float v) {
            return y0 + v * u;
        }

        private void line(float ax, float ay, float bx, float by, Canvas cv) {
            cv.drawLine(X(ax), Y(ay), X(bx), Y(by), p);
        }

        /** A line through the given points (pairs of x, y in the 24 unit box). */
        private void poly(Canvas cv, boolean close, float... pts) {
            path.reset();
            path.moveTo(X(pts[0]), Y(pts[1]));
            for (int i = 2; i < pts.length; i += 2) path.lineTo(X(pts[i]), Y(pts[i + 1]));
            if (close) path.close();
            cv.drawPath(path, p);
        }

        private void rrect(Canvas cv, float l, float t, float r, float b, float rad) {
            box.set(X(l), Y(t), X(r), Y(b));
            cv.drawRoundRect(box, rad * u, rad * u, p);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            p.setStrokeWidth(s * 0.11f);
            x0 = (w - s) / 2f;
            y0 = (h - s) / 2f;
            u = s / 24f;
            switch (kind) {
                case CODE_ICON:
                    cv.drawLine(x0 + s * 0.33f, y0 + s * 0.33f, x0 + s * 0.12f, y0 + s * 0.5f, p);
                    cv.drawLine(x0 + s * 0.12f, y0 + s * 0.5f, x0 + s * 0.33f, y0 + s * 0.67f, p);
                    cv.drawLine(x0 + s * 0.67f, y0 + s * 0.33f, x0 + s * 0.88f, y0 + s * 0.5f, p);
                    cv.drawLine(x0 + s * 0.88f, y0 + s * 0.5f, x0 + s * 0.67f, y0 + s * 0.67f, p);
                    cv.drawLine(x0 + s * 0.56f, y0 + s * 0.2f, x0 + s * 0.44f, y0 + s * 0.8f, p);
                    break;
                case INFO:
                    cv.drawCircle(x0 + s / 2f, y0 + s / 2f, s * 0.4f, p);
                    cv.drawLine(x0 + s / 2f, y0 + s * 0.46f, x0 + s / 2f, y0 + s * 0.68f, p);
                    cv.drawPoint(x0 + s / 2f, y0 + s * 0.33f, p);
                    break;
                case CHECK:
                    p.setStrokeWidth(s * 0.14f);
                    cv.drawLine(x0 + s * 0.2f, y0 + s * 0.52f, x0 + s * 0.42f, y0 + s * 0.72f, p);
                    cv.drawLine(x0 + s * 0.42f, y0 + s * 0.72f, x0 + s * 0.8f, y0 + s * 0.3f, p);
                    break;
                case BOLT:
                    p.setStrokeWidth(s * 0.1f);
                    poly(cv, true, 13, 2, 4.5f, 13.5f, 11, 13.5f, 10, 22, 19.5f, 10.5f, 12.5f, 10.5f);
                    break;
                case SPEED:
                    p.setStrokeWidth(s * 0.1f);
                    box.set(X(3.5f), Y(4.5f), X(20.5f), Y(21.5f));
                    cv.drawArc(box, 152f, 236f, false, p);
                    line(12, 14, 15.5f, 8, cv);
                    break;
                case BATTERY:
                    p.setStrokeWidth(s * 0.1f);
                    rrect(cv, 2.5f, 7, 18.5f, 17, 2.5f);
                    line(21.5f, 10.5f, 21.5f, 13.5f, cv);
                    break;
                case PULSE:
                    p.setStrokeWidth(s * 0.1f);
                    poly(cv, false, 3, 12, 7, 12, 9.2f, 6, 13.3f, 18, 15.5f, 12, 21, 12);
                    break;
                case PUMP:
                    p.setStrokeWidth(s * 0.095f);
                    rrect(cv, 5, 3, 14, 21, 2);
                    line(4, 21, 15, 21, cv);
                    line(8, 8, 11, 8, cv);
                    poly(cv, false, 14, 10, 16.8f, 10, 18.8f, 12, 18.8f, 16.4f, 21, 16.4f, 21, 8.5f, 18.5f, 6);
                    break;
                case COIN:
                    p.setStrokeWidth(s * 0.095f);
                    cv.drawCircle(X(12), Y(12), 9 * u, p);
                    cv.drawCircle(X(12), Y(12), 4.6f * u, p);
                    break;
                case CHEVRON:
                    p.setStrokeWidth(s * 0.12f);
                    if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                        poly(cv, false, 15, 6, 9, 12, 15, 18);
                    } else {
                        poly(cv, false, 9, 6, 15, 12, 9, 18);
                    }
                    break;
                case LINK:
                    p.setStrokeWidth(s * 0.1f);
                    cv.save();
                    cv.rotate(-45f, X(12), Y(12));
                    rrect(cv, 1.5f, 8.8f, 13.5f, 15.2f, 3.2f);
                    rrect(cv, 10.5f, 8.8f, 22.5f, 15.2f, 3.2f);
                    cv.restore();
                    break;
                case ARROW_DOWN:
                    p.setStrokeWidth(s * 0.14f);
                    line(12, 5, 12, 19, cv);
                    poly(cv, false, 6, 13, 12, 19, 18, 13);
                    break;
                case ARROW_UP:
                    p.setStrokeWidth(s * 0.14f);
                    line(12, 19, 12, 5, cv);
                    poly(cv, false, 6, 11, 12, 5, 18, 11);
                    break;
                case ROUTE:
                    p.setStrokeWidth(s * 0.1f);
                    cv.drawCircle(X(6), Y(18), 2.6f * u, p);
                    cv.drawCircle(X(18), Y(6), 2.6f * u, p);
                    path.reset();
                    path.moveTo(X(8.8f), Y(18));
                    path.cubicTo(X(15), Y(18), X(9), Y(6), X(15.2f), Y(6));
                    cv.drawPath(path, p);
                    break;
                case LIST:
                    p.setStrokeWidth(s * 0.1f);
                    line(9.5f, 7, 20.5f, 7, cv);
                    line(9.5f, 12, 20.5f, 12, cv);
                    line(9.5f, 17, 20.5f, 17, cv);
                    cv.drawPoint(X(4.5f), Y(7), p);
                    cv.drawPoint(X(4.5f), Y(12), p);
                    cv.drawPoint(X(4.5f), Y(17), p);
                    break;
                case CLOCK:
                    p.setStrokeWidth(s * 0.1f);
                    cv.drawCircle(X(12), Y(12), 9 * u, p);
                    poly(cv, false, 12, 6.5f, 12, 12, 15.8f, 14.2f);
                    break;
                case LEAF:
                    p.setStrokeWidth(s * 0.1f);
                    path.reset();
                    path.moveTo(X(5), Y(19));
                    path.cubicTo(X(5), Y(10), X(11), Y(5), X(20), Y(5));
                    path.cubicTo(X(20), Y(14), X(15), Y(20), X(6), Y(20));
                    cv.drawPath(path, p);
                    path.reset();
                    path.moveTo(X(5), Y(19));
                    path.cubicTo(X(8), Y(14), X(11), Y(11), X(15), Y(9));
                    cv.drawPath(path, p);
                    break;
                case STAR: {
                    p.setStrokeWidth(s * 0.09f);
                    path.reset();
                    for (int i = 0; i < 10; i++) {
                        double a = -Math.PI / 2 + i * Math.PI / 5;
                        float r = (i % 2 == 0 ? 9.5f : 4.3f);
                        float px = X(12 + (float) (r * Math.cos(a)));
                        float py = Y(12.8f + (float) (r * Math.sin(a)));
                        if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                    }
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case TROPHY:
                    p.setStrokeWidth(s * 0.09f);
                    poly(cv, false, 7, 4, 17, 4, 17, 9);
                    line(7, 4, 7, 9, cv);
                    box.set(X(7), Y(4.5f), X(17), Y(14.5f));
                    cv.drawArc(box, 0, 180, false, p);
                    line(12, 14.5f, 12, 18, cv);
                    line(8, 20, 16, 20, cv);
                    break;
                default:
                    cv.drawLine(x0 + s * 0.25f, y0 + s * 0.25f, x0 + s * 0.75f, y0 + s * 0.75f, p);
                    cv.drawLine(x0 + s * 0.75f, y0 + s * 0.25f, x0 + s * 0.25f, y0 + s * 0.75f, p);
            }
        }
    }
}
