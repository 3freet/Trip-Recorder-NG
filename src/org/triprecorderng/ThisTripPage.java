package org.triprecorderng;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The This Trip screen: on the left what the car is doing now (recording state, a gauge that shows the speed while
 * driving and the battery otherwise, GPS and vehicle data state), on the right the current trip, or the last one:
 * distance, duration and score, then speed, energy and behavior, and what the trip has used (electricity, fuel) and
 * cost so far. MainActivity keeps the data and the screens behind it and is reached through Host.
 */
final class ThisTripPage {
    interface Host {
        /** The recorded trips, newest first. */
        List<Trip> trips();

        void openTrip(Trip t);

        void openSetting();

        /** Sets the code that is run about once a second while the page is shown. */
        void refresher(Runnable r);
    }

    /** Design pixels are scaled a little more than on the Tweaks and Setting screens: this one is read at a glance. */
    private static final float SCALE = 1.35f;

    private static final int RED = 0xFFEB5757;
    private static final int RED_TEXT = 0xFFFF8D8D;
    private static final int RED_SOFT = 0x26EB5757;
    private static final int BLUE_TEXT = 0xFF8FB2FF;
    private static final int BLUE_SOFT = 0x2B5B8DEF;
    private static final int AMBER_SOFT = 0x21F2B24C;
    private static final int CHIP_BG = 0x12FFFFFF;

    private static final int PILL_IDLE = 0;
    private static final int PILL_REC = 1;
    private static final int PILL_CHARGING = 2;
    private static final int PILL_BAD = 3;

    private final Context c;
    private final Host host;
    private final Kit k;

    // left card
    private LinearLayout leftCard;
    private int glowShown;
    private Pill pill;
    private GaugeView gauge;
    private StatusRow gpsRow;
    private StatusRow dataRow;
    private Banner hint;
    private Banner warn;

    // right card
    private LinearLayout rightCard;
    private Trip shownTrip;
    private TextView title;
    private TextView when;
    private LinearLayout liveTag;
    private View content;
    private View emptyNote;
    private TextView distValue;
    private TextView durValue;
    private RingView score;
    private TextView avgValue;
    private TextView maxValue;
    private View energyBlocks;
    private View energyNote;
    private TextView energyNoteText;
    private TextView socValue;
    private TextView usedChip;
    private BarView socBar;
    private TextView kwhValue;
    private LinearLayout deltaChip;
    private Kit.IconView deltaArrow;
    private TextView deltaText;
    private TextView accelValue;
    private TextView brakeValue;
    private View accelDot;
    private View brakeDot;
    private View footer;
    private TextView evValue;
    private TextView fuelValue;
    private TextView costValue;

    static View build(Context c, Host host) {
        return new ThisTripPage(c, host).create();
    }

    private ThisTripPage(Context c, Host host) {
        this.c = c;
        this.host = host;
        this.k = new Kit(c, SCALE);
    }

    private View create() {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(buildLeft(), new LinearLayout.LayoutParams(0, -1, 214f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 400f);
        rlp.setMarginStart(k.d(10));
        row.addView(buildRight(), rlp);
        host.refresher(new Runnable() {
            @Override public void run() {
                refresh();
            }
        });
        refresh();
        return row;
    }

    // ---- small pieces ----------------------------------------------------------------------------
    private static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** {@code over} laid on {@code base} with the given strength, as an opaque color. */
    private static int mix(int base, int over, float a) {
        int r = Math.round(((base >> 16) & 0xFF) * (1 - a) + ((over >> 16) & 0xFF) * a);
        int g = Math.round(((base >> 8) & 0xFF) * (1 - a) + ((over >> 8) & 0xFF) * a);
        int b = Math.round((base & 0xFF) * (1 - a) + (over & 0xFF) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void setText(TextView tv, CharSequence s) {
        if (!s.toString().contentEquals(tv.getText())) tv.setText(s);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
    }

    private LinearLayout.LayoutParams wrap(int topDesign) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = k.d(topDesign);
        return lp;
    }

    /** The recording state at the top of the left card. */
    private final class Pill {
        final LinearLayout view = k.row();
        final View dot = new View(c);
        final Kit.IconView bolt = new Kit.IconView(c, Kit.IconView.BOLT, BLUE_TEXT);
        final TextView text = k.label("", 14, Kit.SOFT, Kit.BOLD);
        final TextView extra = k.label("", 14, Kit.SOFT, Kit.MEDIUM);
        private int kind = -1;

        Pill() {
            view.setPaddingRelative(k.d(12), 0, k.d(12), 0);
            view.addView(dot, new LinearLayout.LayoutParams(k.d(8), k.d(8)));
            view.addView(bolt, new LinearLayout.LayoutParams(k.d(16), k.d(16)));
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPaddingRelative(k.d(7), 0, 0, 0);
            view.addView(text, new LinearLayout.LayoutParams(-2, -2));
            extra.setSingleLine(true);
            extra.setPaddingRelative(k.d(6), 0, 0, 0);
            view.addView(extra, new LinearLayout.LayoutParams(-2, -2));
        }

        void set(int newKind, String label, String more) {
            setText(text, label);
            setText(extra, more);
            extra.setVisibility(more.isEmpty() ? View.GONE : View.VISIBLE);
            if (newKind == kind) return;
            kind = newKind;
            int bg, fg;
            switch (newKind) {
                case PILL_REC:
                    bg = Kit.ACCENT_SOFT;
                    fg = Kit.ACCENT;
                    break;
                case PILL_CHARGING:
                    bg = BLUE_SOFT;
                    fg = BLUE_TEXT;
                    break;
                case PILL_BAD:
                    bg = RED_SOFT;
                    fg = RED_TEXT;
                    break;
                default:
                    bg = Kit.NEUTRAL_BG;
                    fg = Kit.SOFT;
            }
            view.setBackground(Ui.round(bg, 14 * k.scale, c));
            text.setTextColor(fg);
            extra.setTextColor(fg);
            bolt.setVisibility(newKind == PILL_CHARGING ? View.VISIBLE : View.GONE);
            dot.setVisibility(newKind == PILL_CHARGING ? View.GONE : View.VISIBLE);
            dot.setBackground(oval(newKind == PILL_IDLE ? Kit.DOT_OFF : fg));
            dot.clearAnimation();
            if (newKind == PILL_REC) {
                AlphaAnimation pulse = new AlphaAnimation(1f, 0.3f);
                pulse.setDuration(750);
                pulse.setRepeatMode(Animation.REVERSE);
                pulse.setRepeatCount(Animation.INFINITE);
                dot.startAnimation(pulse);
            }
        }
    }

    /** A label with a state at its end: "GPS" and "Fix" with a check mark, or "Vehicle data" and "Connected". */
    private final class StatusRow {
        final LinearLayout view = k.row();
        final View dot = new View(c);
        final Kit.IconView check = new Kit.IconView(c, Kit.IconView.CHECK, Kit.ACCENT);
        final TextView value = k.label("", 13, Kit.SOFT, Kit.BOLD);
        private int color;

        StatusRow(String label) {
            view.addView(k.label(label, 13, Kit.MUTED, Kit.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1f));
            view.addView(check, new LinearLayout.LayoutParams(k.d(14), k.d(14)));
            view.addView(dot, new LinearLayout.LayoutParams(k.d(8), k.d(8)));
            value.setPaddingRelative(k.d(6), 0, 0, 0);
            view.addView(value, new LinearLayout.LayoutParams(-2, -2));
        }

        void set(String text, int col, boolean checkMark) {
            setText(value, text);
            if (col != color) {
                color = col;
                value.setTextColor(col);
                check.setColor(col);
                dot.setBackground(oval(col));
            }
            check.setVisibility(checkMark ? View.VISIBLE : View.GONE);
            dot.setVisibility(checkMark ? View.GONE : View.VISIBLE);
        }
    }

    /** A tappable note under the status rows: amber for something to turn on, red for something that is wrong. */
    private final class Banner {
        final LinearLayout view = k.row();
        final Kit.IconView icon;
        final TextView text = k.label("", 12.5f, Kit.AMBER, Kit.BOLD);
        final Kit.IconView chevron;
        private final boolean red;

        Banner(int iconKind, final boolean red) {
            this.red = red;
            int fg = red ? RED_TEXT : Kit.AMBER;
            view.setPaddingRelative(k.d(10), k.d(8), k.d(10), k.d(8));
            view.setBackground(Ui.round(red ? RED_SOFT : AMBER_SOFT, 14 * k.scale, c));
            icon = new Kit.IconView(c, iconKind, fg);
            view.addView(icon, new LinearLayout.LayoutParams(k.d(18), k.d(18)));
            text.setTextColor(fg);
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPaddingRelative(k.d(9), 0, k.d(6), 0);
            view.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            chevron = new Kit.IconView(c, Kit.IconView.CHEVRON, fg);
            view.addView(chevron, new LinearLayout.LayoutParams(k.d(16), k.d(16)));
            view.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    host.openSetting();
                }
            });
            view.setVisibility(View.GONE);
        }

        void set(boolean visible, String s) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) setText(text, s);
        }
    }

    /** The battery bar of the Energy tile: what is left, and in amber what this trip took. */
    private static final class BarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private float fill;
        private float usedTo;
        private int color = Kit.ACCENT;

        BarView(Context c) {
            super(c);
        }

        void set(double fillPct, double fromPct, int fillColor) {
            float f = (float) Math.max(0, Math.min(100, Double.isNaN(fillPct) ? 0 : fillPct)) / 100f;
            float u = (float) Math.max(f * 100, Math.min(100, Double.isNaN(fromPct) ? f * 100 : fromPct)) / 100f;
            if (f == fill && u == usedTo && fillColor == color) return;
            fill = f;
            usedTo = u;
            color = fillColor;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) cv.scale(-1f, 1f, w / 2f, h / 2f);
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            p.setColor(0x1AFFFFFF);
            cv.drawRect(0, 0, w, h, p);
            p.setColor(color);
            cv.drawRect(0, 0, w * fill, h, p);
            if (usedTo > fill) {
                p.setColor(0x8CF2B24C);
                cv.drawRect(w * fill, 0, w * usedTo, h, p);
            }
            cv.restore();
        }
    }

    // ---- the left card -----------------------------------------------------------------------------
    private View buildLeft() {
        leftCard = k.column();
        leftCard.setPaddingRelative(k.d(14), k.d(13), k.d(14), k.d(14));
        glowShown = Kit.ACCENT;
        leftCard.setBackground(glowBackground(Kit.ACCENT));

        pill = new Pill();
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, k.d(28));
        leftCard.addView(pill.view, plp);

        gauge = new GaugeView(c, k.d(200));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = k.d(6);
        glp.bottomMargin = k.d(4);
        leftCard.addView(gauge, glp);

        gpsRow = new StatusRow(L.t("GPS"));
        leftCard.addView(gpsRow.view, wrap(2));
        dataRow = new StatusRow(L.t("Vehicle data"));
        leftCard.addView(dataRow.view, wrap(6));

        hint = new Banner(Kit.IconView.LINK, false);
        leftCard.addView(hint.view, wrap(9));
        warn = new Banner(Kit.IconView.INFO, true);
        leftCard.addView(warn.view, wrap(7));
        return leftCard;
    }

    private GradientDrawable glowBackground(int glow) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{mix(Kit.CARD, glow, 0.12f), Kit.CARD});
        g.setGradientCenter(0.5f, 0.40f);
        g.setGradientRadius(k.d(230));
        g.setCornerRadius(Ui.dp(c, 18 * k.scale));
        return g;
    }

    // ---- the right card ----------------------------------------------------------------------------
    private View buildRight() {
        rightCard = k.card();
        rightCard.setPaddingRelative(k.d(16), k.d(14), k.d(16), k.d(14));
        rightCard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (shownTrip != null) host.openTrip(shownTrip);
            }
        });

        LinearLayout head = k.row();
        title = k.label("", 17, Kit.TEXT, Kit.BOLD);
        head.addView(title);
        when = k.label("", 13, Kit.MUTED, Kit.MEDIUM);
        when.setPaddingRelative(k.d(10), 0, 0, 0);
        head.addView(when);
        liveTag = k.row();
        liveTag.setPaddingRelative(k.d(8), k.d(3), k.d(8), k.d(3));
        liveTag.setBackground(Ui.round(Kit.ACCENT_SOFT, 9 * k.scale, c));
        View liveDot = new View(c);
        liveDot.setBackground(oval(Kit.ACCENT));
        liveTag.addView(liveDot, new LinearLayout.LayoutParams(k.d(6), k.d(6)));
        TextView liveText = k.label(L.t("LIVE"), 11, Kit.ACCENT, Kit.BOLD);
        liveText.setPaddingRelative(k.d(6), 0, 0, 0);
        if (!L.isArabic()) liveText.setLetterSpacing(0.08f);
        liveTag.addView(liveText);
        AlphaAnimation pulse = new AlphaAnimation(1f, 0.3f);
        pulse.setDuration(750);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        liveDot.startAnimation(pulse);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.setMarginStart(k.d(10));
        head.addView(liveTag, llp);
        head.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView more = k.label(L.t("Details"), 13.5f, Kit.ACCENT, Kit.BOLD);
        head.addView(more);
        head.addView(new Kit.IconView(c, Kit.IconView.CHEVRON, Kit.ACCENT), new LinearLayout.LayoutParams(k.d(16), k.d(16)));
        rightCard.addView(head, new LinearLayout.LayoutParams(-1, k.d(28)));

        LinearLayout body = k.column();
        body.addView(hero(), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tiles = k.row();
        tiles.setGravity(Gravity.TOP);
        tiles.addView(speedTile(), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, -1, 1f);
        t2.setMarginStart(k.d(8));
        tiles.addView(energyTile(), t2);
        LinearLayout.LayoutParams t3 = new LinearLayout.LayoutParams(0, -1, 1f);
        t3.setMarginStart(k.d(8));
        tiles.addView(behaviorTile(), t3);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        tlp.topMargin = k.d(10);
        body.addView(tiles, tlp);
        footer = footer();
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, k.d(38));
        flp.topMargin = k.d(9);
        body.addView(footer, flp);
        content = body;
        rightCard.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout empty = k.column();
        empty.setGravity(Gravity.CENTER);
        TextView a = k.label(L.t("No trip recorded yet"), 19, Kit.SOFT, Kit.BOLD);
        a.setGravity(Gravity.CENTER);
        empty.addView(a);
        TextView b = k.label(L.t("Your first drive will appear here"), 13.5f, Kit.MUTED, Kit.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, k.d(6), 0, 0);
        empty.addView(b);
        empty.setVisibility(View.GONE);
        emptyNote = empty;
        rightCard.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1f));
        return rightCard;
    }

    private View hero() {
        LinearLayout row = k.row();
        row.addView(bigCell(true), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(bigCell(false), new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout s = k.row();
        s.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        TextView l = k.label(L.t("Drive score"), 12.5f, Kit.MUTED, Kit.MEDIUM);
        l.setMaxLines(2);
        score = new RingView(c, 6 * k.scale, 23 * k.scale, 12 * k.scale);
        score.setTrackColor(0x17FFFFFF);
        s.addView(score, new LinearLayout.LayoutParams(k.d(60), k.d(60)));
        l.setPaddingRelative(k.d(10), 0, 0, 0);
        s.addView(l, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1.1f);
        row.addView(s, slp);
        row.setPadding(0, k.d(8), 0, 0);
        return row;
    }

    private View bigCell(boolean distance) {
        LinearLayout cell = k.column();
        TextView v = k.label("--", 36, Kit.TEXT, Kit.BOLD);
        v.setSingleLine(true);
        TextView cap = k.label(distance ? L.t("Mileage") : L.t("Duration"), 12.5f, Kit.MUTED, Kit.MEDIUM);
        cap.setPadding(0, k.d(2), 0, 0);
        cell.addView(v);
        cell.addView(cap);
        if (distance) distValue = v; else durValue = v;
        return cell;
    }

    private LinearLayout tile(int icon, String label) {
        LinearLayout t = k.column();
        t.setBackground(Ui.round(Kit.SHEET, 16 * k.scale, c));
        t.setPaddingRelative(k.d(12), k.d(10), k.d(12), k.d(10));
        LinearLayout head = k.row();
        head.addView(new Kit.IconView(c, icon, Kit.MUTED), new LinearLayout.LayoutParams(k.d(14), k.d(14)));
        TextView l = k.label(L.isArabic() ? label : label.toUpperCase(Locale.US), 11, Kit.MUTED, Kit.BOLD);
        if (!L.isArabic()) l.setLetterSpacing(0.08f);
        l.setPaddingRelative(k.d(6), 0, 0, 0);
        head.addView(l);
        t.addView(head, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private LinearLayout.LayoutParams blockLp() {
        return new LinearLayout.LayoutParams(-1, 0, 1f);
    }

    private TextView caption(String s) {
        TextView t = k.label(s, 12, Kit.MUTED, Kit.MEDIUM);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, k.d(1), 0, 0);
        return t;
    }

    private TextView value(float design) {
        TextView t = k.label("--", design, Kit.TEXT, Kit.BOLD);
        t.setSingleLine(true);
        return t;
    }

    private View speedTile() {
        LinearLayout t = tile(Kit.IconView.SPEED, L.t("Speed"));
        LinearLayout b1 = k.column();
        b1.setGravity(Gravity.CENTER_VERTICAL);
        avgValue = value(28);
        b1.addView(avgValue);
        b1.addView(caption(L.t("Average")));
        t.addView(b1, blockLp());
        LinearLayout b2 = k.column();
        b2.setGravity(Gravity.CENTER_VERTICAL);
        maxValue = value(28);
        b2.addView(maxValue);
        b2.addView(caption(L.t("Max")));
        t.addView(b2, blockLp());
        return t;
    }

    private View energyTile() {
        LinearLayout t = tile(Kit.IconView.BATTERY, L.t("Energy"));
        LinearLayout blocks = k.column();

        LinearLayout b1 = k.column();
        b1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout r1 = k.row();
        socValue = value(28);
        r1.addView(socValue);
        usedChip = k.label("", 11.5f, 0xFFAEB7C0, Kit.BOLD);
        usedChip.setBackground(Ui.round(CHIP_BG, 8 * k.scale, c));
        usedChip.setPadding(k.d(6), k.d(2), k.d(6), k.d(2));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.setMarginStart(k.d(6));
        r1.addView(usedChip, ulp);
        b1.addView(r1);
        socBar = new BarView(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, k.d(6));
        blp.topMargin = k.d(6);
        b1.addView(socBar, blp);
        b1.addView(caption(L.t("Battery")));
        blocks.addView(b1, blockLp());

        LinearLayout b2 = k.column();
        b2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout r2 = k.row();
        kwhValue = value(24);
        r2.addView(kwhValue);
        deltaChip = k.row();
        deltaChip.setPaddingRelative(k.d(6), k.d(2), k.d(6), k.d(2));
        deltaArrow = new Kit.IconView(c, Kit.IconView.ARROW_DOWN, Kit.ACCENT);
        deltaChip.addView(deltaArrow, new LinearLayout.LayoutParams(k.d(11), k.d(11)));
        deltaText = k.label("", 11.5f, Kit.ACCENT, Kit.BOLD);
        deltaText.setPaddingRelative(k.d(3), 0, 0, 0);
        deltaChip.addView(deltaText);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.setMarginStart(k.d(6));
        r2.addView(deltaChip, dlp);
        b2.addView(r2);
        b2.addView(caption(L.t("kWh/100km")));
        blocks.addView(b2, blockLp());
        energyBlocks = blocks;
        t.addView(blocks, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout note = k.column();
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.addView(new Kit.IconView(c, Kit.IconView.LINK, Kit.DOT_OFF), new LinearLayout.LayoutParams(k.d(22), k.d(22)));
        energyNoteText = k.label("", 12.5f, Kit.MUTED, Kit.MEDIUM);
        energyNoteText.setPadding(0, k.d(8), 0, 0);
        note.addView(energyNoteText);
        note.setVisibility(View.GONE);
        energyNote = note;
        t.addView(note, new LinearLayout.LayoutParams(-1, 0, 1f));
        return t;
    }

    private View behaviorTile() {
        LinearLayout t = tile(Kit.IconView.PULSE, L.t("Behavior"));
        for (int i = 0; i < 2; i++) {
            LinearLayout b = k.column();
            b.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout r = k.row();
            TextView n = value(28);
            r.addView(n);
            View dot = new View(c);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(k.d(10), k.d(10));
            dlp.setMarginStart(k.d(9));
            r.addView(dot, dlp);
            b.addView(r);
            b.addView(caption(i == 0 ? L.t("Hard acceleration") : L.t("Hard braking")));
            t.addView(b, blockLp());
            if (i == 0) {
                accelValue = n;
                accelDot = dot;
            } else {
                brakeValue = n;
                brakeDot = dot;
            }
        }
        return t;
    }

    private View footer() {
        LinearLayout row = k.row();
        row.addView(chip(Kit.IconView.BOLT, Kit.ACCENT, L.t("EV"), 0), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, -1, 1f);
        m1.setMarginStart(k.d(8));
        row.addView(chip(Kit.IconView.PUMP, Kit.AMBER, L.t("Fuel"), 1), m1);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, -1, 1.15f);
        m2.setMarginStart(k.d(8));
        row.addView(chip(Kit.IconView.COIN, Kit.MUTED, L.t("Cost"), 2), m2);
        return row;
    }

    /** An outlined chip: icon, what it is, and what this trip has used or cost so far. */
    private View chip(int icon, int color, String label, int which) {
        LinearLayout ch = k.row();
        ch.setGravity(Gravity.CENTER_VERTICAL);
        ch.setPaddingRelative(k.d(10), 0, k.d(10), 0);
        ch.setBackground(Ui.stroke(Kit.LINE, 0x00000000, 19 * k.scale, c));
        ch.addView(new Kit.IconView(c, icon, color), new LinearLayout.LayoutParams(k.d(17), k.d(17)));
        TextView l = k.label(label, 12.5f, Kit.MUTED, Kit.MEDIUM);
        l.setPaddingRelative(k.d(8), 0, 0, 0);
        l.setSingleLine(true);
        ch.addView(l);
        TextView v = k.label("--", 15, Kit.TEXT, Kit.BOLD);
        v.setPaddingRelative(k.d(8), 0, 0, 0);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        ch.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        if (which == 0) evValue = v;
        else if (which == 1) fuelValue = v;
        else costValue = v;
        return ch;
    }

    // ---- keeping the page current --------------------------------------------------------------------
    private void refresh() {
        RecorderService.Live l = RecorderService.live;
        Trip cur = RecorderService.current;
        boolean gpsOnly = "GPS only".equals(l.vehicle);
        double speed = l.speed;
        boolean moving = !Double.isNaN(speed) && speed >= 3;
        String chargingText = ChargeRecorder.status;
        boolean charging = chargingText != null && !chargingText.isEmpty();

        // the recording state
        if (!l.running) {
            pill.set(PILL_BAD, L.t("Service stopped"), "");
        } else if (charging) {
            Charge ch = ChargeRecorder.current;
            double gain = ch == null ? Double.NaN : ch.socGained();
            pill.set(PILL_CHARGING, chargingText,
                    !Double.isNaN(gain) && gain >= 1 ? L.ltr("+" + Ui.f(gain, "%.0f") + "%") : "");
        } else if (cur != null) {
            pill.set(PILL_REC, L.t("Recording"), "");
        } else if (!Double.isNaN(speed) && speed >= 15) {
            pill.set(PILL_BAD, L.t("Not recording"), "");
        } else {
            pill.set(PILL_IDLE, L.t("Not recording"), "");
        }

        // the gauge: the battery when parked or charging (a trip can stay open while the car charges), the speed while driving
        boolean energyMode = !Double.isNaN(l.soc) && !gpsOnly && !moving && (cur == null || charging);
        int glow = Kit.ACCENT;
        if (energyMode) {
            double soc = l.soc;
            int from = Kit.ACCENT, to = Kit.BLUE;
            if (soc < 15) {
                from = to = RED;
                glow = RED;
            } else if (soc < 30) {
                from = to = Kit.AMBER;
                glow = Kit.AMBER;
            }
            if (charging) glow = Kit.BLUE;
            gauge.set(soc / 100.0, from, to, Ui.f(soc, "%.0f"), "%", L.t("Battery"));
        } else {
            double v = Double.isNaN(speed) ? 0 : speed;
            int from = Kit.ACCENT, to = Kit.BLUE;
            if (v > 130) {
                from = to = RED;
                glow = RED;
            } else if (v > 100) {
                from = to = Kit.AMBER;
                glow = Kit.AMBER;
            }
            gauge.set(v / 180.0, from, to, Double.isNaN(speed) ? "--" : Ui.f(speed, "%.0f"), "", L.t("km/h"));
        }
        if (glow != glowShown) {
            glowShown = glow;
            leftCard.setBackground(glowBackground(glow));
        }

        if (!l.running) gpsRow.set("--", Kit.MUTED, false);
        else if (!l.gps) gpsRow.set(cap(L.t("off")), Kit.MUTED, false);
        else if (l.gpsFix) gpsRow.set(cap(L.t("fix")), Kit.ACCENT, true);
        else gpsRow.set(cap(L.t("searching")), Kit.AMBER, false);

        if (!l.running || "none".equals(l.vehicle)) dataRow.set("--", Kit.MUTED, false);
        else if (gpsOnly) dataRow.set(L.t("GPS only"), Kit.AMBER, false);
        else dataRow.set(L.t("Connected"), Kit.ACCENT, false);

        warn.set(Prefs.autoStartHealth(c) == Prefs.AUTOSTART_BLOCKED,
                L.t("Auto-start is blocked by BYD - see Setting"));
        if (gpsOnly) {
            hint.set(true, Prefs.linkEnabled(c)
                    ? L.f("Data link: %s", HelperLauncher.statusText(c))
                    : L.t("Turn on Vehicle data link in Setting for battery and energy"));
        } else {
            hint.set(false, "");
        }

        refreshTrip(l, cur);
    }

    private void refreshTrip(RecorderService.Live l, Trip cur) {
        List<Trip> trips = host.trips();
        Trip show = cur;
        if (show == null && !trips.isEmpty()) show = trips.get(0);
        shownTrip = show;
        if (show == null) {
            setText(title, L.t("Last trip"));
            setText(when, "");
            liveTag.setVisibility(View.GONE);
            content.setVisibility(View.GONE);
            emptyNote.setVisibility(View.VISIBLE);
            return;
        }
        content.setVisibility(View.VISIBLE);
        emptyNote.setVisibility(View.GONE);
        boolean live = cur != null;
        setText(title, live ? L.t("Current trip") : L.t("Last trip"));
        if (live) {
            setText(when, "");
        } else {
            SimpleDateFormat day = new SimpleDateFormat("d MMM", L.dateLocale());
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", L.dateLocale());
            Date d = new Date(show.startMs);
            setText(when, day.format(d) + " · " + hm.format(d));
        }
        liveTag.setVisibility(live ? View.VISIBLE : View.GONE);

        setText(distValue, Kit.numUnit(L.f("%s km", Ui.f(show.distanceKm, "%.1f")), 0.42f));
        setText(durValue, Kit.numUnit(Ui.duration(show.durationSec()), 0.42f));
        double sc = Stats.score(show);
        score.set(Double.isNaN(sc) ? 0 : sc, 100, Ui.f(sc, "%.0f"), "", Kit.scoreColor(sc));

        setText(avgValue, Kit.numUnit(L.f("%s km/h", Ui.f(show.avgSpeedKmh(), "%.0f")), 0.5f));
        setText(maxValue, Kit.numUnit(L.f("%s km/h", Ui.f(show.maxSpeedKmh, "%.0f")), 0.5f));

        // energy: what is left, what the trip took, and how it compares with the usual
        double socNow = live && !Double.isNaN(l.soc) ? l.soc : show.socEnd;
        double per100 = show.energyPer100Km();
        if (Double.isNaN(socNow) && Double.isNaN(per100)) {
            energyBlocks.setVisibility(View.GONE);
            energyNote.setVisibility(View.VISIBLE);
            setText(energyNoteText, Prefs.linkEnabled(c) ? L.t("No battery data for this trip")
                    : L.t("Battery and energy need the Vehicle data link"));
        } else {
            energyNote.setVisibility(View.GONE);
            energyBlocks.setVisibility(View.VISIBLE);
            setText(socValue, Kit.numUnit(Double.isNaN(socNow) ? "--" : Ui.f(socNow, "%.0f") + "%", 0.5f));
            double used = Double.isNaN(socNow) || Double.isNaN(show.socStart) ? Double.NaN : show.socStart - socNow;
            if (!Double.isNaN(used) && used >= 1) {
                usedChip.setVisibility(View.VISIBLE);
                setText(usedChip, L.ltr("−" + Ui.f(used, "%.0f") + "%"));
            } else {
                usedChip.setVisibility(View.GONE);
            }
            int barColor = socNow < 15 ? RED : socNow < 30 ? Kit.AMBER : Kit.ACCENT;
            socBar.set(socNow, Double.isNaN(used) ? socNow : socNow + Math.max(0, used), barColor);
            setText(kwhValue, Ui.f(per100, "%.1f"));
            showDelta(show, per100, trips);
        }

        setText(accelValue, String.valueOf(show.hardAccel));
        accelDot.setBackground(oval(show.hardAccel == 0 ? Kit.ACCENT : Kit.AMBER));
        setText(brakeValue, String.valueOf(show.hardBrake));
        brakeDot.setBackground(oval(show.hardBrake == 0 ? Kit.ACCENT : RED));

        // what the trip has used and cost so far
        double e = show.energyUsed();
        double f = show.fuelUsed();
        double cost = Cost.total(show, c);
        boolean any = !Double.isNaN(e) || !Double.isNaN(f) || !Double.isNaN(cost);
        footer.setVisibility(any ? View.VISIBLE : View.GONE);
        setText(evValue, Double.isNaN(e) ? "--" : L.f("%s kWh", Ui.f(e, "%.1f")));
        setText(fuelValue, Double.isNaN(f) ? "--" : L.f("%s L", Ui.f(f, "%.1f")));
        setText(costValue, Cost.money(c, cost));
    }

    /**
     * The chip next to the consumption: how far this trip is from the average of the earlier all-electric ones
     * (green and down when it used less). Not shown without a few earlier trips to compare with, or when the
     * trip burned fuel, which makes the electric figure incomparable.
     */
    private void showDelta(Trip show, double per100, List<Trip> trips) {
        deltaChip.setVisibility(View.GONE);
        if (Double.isNaN(per100) || show.distanceKm < 2) return;
        double f = show.fuelUsed();
        if (!Double.isNaN(f) && f >= 0.05) return;
        double sum = 0;
        int n = 0;
        for (Trip t : trips) {
            if (t == show || !t.ended || t.distanceKm < 3) continue;
            double p = t.energyPer100Km();
            double tf = t.fuelUsed();
            if (Double.isNaN(p) || (!Double.isNaN(tf) && tf >= 0.05)) continue;
            sum += p;
            if (++n >= 10) break;
        }
        if (n < 3) return;
        double avg = sum / n;
        if (avg <= 0) return;
        double pct = (per100 - avg) / avg * 100.0;
        if (Math.abs(pct) < 2) return;
        boolean better = pct < 0;
        int col = better ? Kit.ACCENT : Kit.AMBER;
        deltaChip.setBackground(Ui.round(better ? Kit.ACCENT_SOFT : AMBER_SOFT, 8 * k.scale, c));
        deltaArrow.setColor(col);
        deltaArrow.setKind(better ? Kit.IconView.ARROW_DOWN : Kit.IconView.ARROW_UP);
        deltaText.setTextColor(col);
        setText(deltaText, L.ltr(Ui.f(Math.abs(pct), "%.0f") + "%"));
        deltaChip.setVisibility(View.VISIBLE);
    }
}
