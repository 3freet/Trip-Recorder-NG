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
    final float scale = 1.2f; // dp (and sp) per design pixel

    Kit(Context c) {
        this.c = c;
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
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconView(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            p.setStrokeWidth(s * 0.11f);
            float x0 = (w - s) / 2f;
            float y0 = (h - s) / 2f;
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
                default:
                    cv.drawLine(x0 + s * 0.25f, y0 + s * 0.25f, x0 + s * 0.75f, y0 + s * 0.75f, p);
                    cv.drawLine(x0 + s * 0.75f, y0 + s * 0.25f, x0 + s * 0.25f, y0 + s * 0.75f, p);
            }
        }
    }
}
