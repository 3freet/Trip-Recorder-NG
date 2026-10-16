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
 * cost so far. On a trip that burned fuel the Energy tile shows electric and hybrid driving apart (see TripEnergy).
 * MainActivity keeps the data and the screens behind it and is reached through Host.
 */
final class ThisTripPage {
    interface Host {
        /** The recorded trips, newest first. */
        List<Trip> trips();

        List<Charge> charges();

        void openTrip(Trip t);

        void openSetting();

        /** Sets the code that is run about once a second while the page is shown. */
        void refresher(Runnable r);
    }

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
    private View pureBlock;
    private View hybridBlock;
    private TextView kwhValue;
    private LinearLayout deltaChip;
    private Kit.IconView deltaArrow;
    private TextView deltaText;
    private TextView electricCaption;
    private TextView electricValue;
    private TextView hybridCaption;
    private TextView hybridValue;
    private TextView accelValue;
    private TextView brakeValue;
    private View accelDot;
    private View brakeDot;
    private View footer;
    private TextView evValue;
    private TextView fuelValue;
    private TextView costValue;
    /** What the whole trip used per 100 km, under the footer figures of a trip that burned fuel. */
    private TextView evPer100;
    private TextView fuelPer100;

    static View build(Context c, Host host) {
        return new ThisTripPage(c, host).create();
    }

    private ThisTripPage(Context c, Host host) {
        this.c = c;
        this.host = host;
        this.k = new Kit(c, 1f);
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }

    private View create() {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(buildLeft(), new LinearLayout.LayoutParams(dp(342), -1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1f);
        rlp.setMarginStart(dp(12));
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

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        return lp;
    }

    /** The recording state at the top of the left card. */
    private final class Pill {
        final LinearLayout view = k.row();
        final View dot = new View(c);
        final Kit.IconView bolt = new Kit.IconView(c, Kit.IconView.BOLT, BLUE_TEXT);
        final TextView text = k.t("", Kit.LABEL, Kit.SOFT, Kit.MEDIUM);
        final TextView extra = k.t("", Kit.LABEL, Kit.SOFT, Kit.MEDIUM);
        private int kind = -1;

        Pill() {
            view.setPaddingRelative(dp(16), 0, dp(16), 0);
            view.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            view.addView(bolt, new LinearLayout.LayoutParams(dp(20), dp(20)));
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPaddingRelative(dp(10), 0, 0, 0);
            view.addView(text, new LinearLayout.LayoutParams(-2, -2));
            extra.setSingleLine(true);
            extra.setPaddingRelative(dp(8), 0, 0, 0);
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
            view.setBackground(Ui.round(bg, 20, c));
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
        final TextView value = k.t("", Kit.BODY, Kit.SOFT, Kit.MEDIUM);
        private int color;

        StatusRow(String label) {
            view.addView(k.t(label, Kit.BODY, Kit.MUTED, Kit.REGULAR), new LinearLayout.LayoutParams(0, -2, 1f));
            view.addView(check, new LinearLayout.LayoutParams(dp(20), dp(20)));
            view.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            value.setPaddingRelative(dp(8), 0, 0, 0);
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
        final TextView text = k.t("", Kit.BODY, Kit.AMBER, Kit.MEDIUM);
        final Kit.IconView chevron;

        Banner(int iconKind, final boolean red) {
            int fg = red ? RED_TEXT : Kit.AMBER;
            view.setPaddingRelative(dp(14), dp(12), dp(14), dp(12));
            view.setBackground(Ui.round(red ? RED_SOFT : AMBER_SOFT, 16, c));
            icon = new Kit.IconView(c, iconKind, fg);
            view.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            text.setTextColor(fg);
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPaddingRelative(dp(12), 0, dp(8), 0);
            view.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            chevron = new Kit.IconView(c, Kit.IconView.CHEVRON, fg);
            view.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
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
        leftCard.setPaddingRelative(dp(16), dp(16), dp(16), dp(16));
        glowShown = Kit.ACCENT;
        leftCard.setBackground(glowBackground(Kit.ACCENT));

        pill = new Pill();
        leftCard.addView(pill.view, new LinearLayout.LayoutParams(-2, dp(40)));

        gauge = new GaugeView(c, dp(250));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = dp(6);
        glp.bottomMargin = dp(4);
        leftCard.addView(gauge, glp);

        gpsRow = new StatusRow(L.t("GPS"));
        leftCard.addView(gpsRow.view, wrap(2));
        dataRow = new StatusRow(L.t("Vehicle data"));
        leftCard.addView(dataRow.view, wrap(8));

        hint = new Banner(Kit.IconView.LINK, false);
        leftCard.addView(hint.view, wrap(10));
        warn = new Banner(Kit.IconView.INFO, true);
        leftCard.addView(warn.view, wrap(8));
        return leftCard;
    }

    private GradientDrawable glowBackground(int glow) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{mix(Kit.CARD, glow, 0.12f), Kit.CARD});
        g.setGradientCenter(0.5f, 0.40f);
        g.setGradientRadius(dp(230));
        g.setCornerRadius(dp(24));
        return g;
    }

    // ---- the right card ----------------------------------------------------------------------------
    private View buildRight() {
        LinearLayout card = k.bigCard();
        card.setPaddingRelative(dp(18), dp(16), dp(18), dp(16));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (shownTrip != null) host.openTrip(shownTrip);
            }
        });

        LinearLayout head = k.row();
        title = k.t("", Kit.TITLE, Kit.TEXT, Kit.MEDIUM);
        head.addView(title);
        when = k.t("", Kit.BODY, Kit.MUTED, Kit.REGULAR);
        when.setPaddingRelative(dp(14), 0, 0, 0);
        head.addView(when);
        liveTag = k.row();
        liveTag.setPaddingRelative(dp(12), 0, dp(12), 0);
        liveTag.setBackground(Ui.round(Kit.ACCENT_SOFT, 14, c));
        View liveDot = new View(c);
        liveDot.setBackground(oval(Kit.ACCENT));
        liveTag.addView(liveDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView liveText = k.t(L.t("LIVE"), Kit.CAPTION, Kit.ACCENT, Kit.MEDIUM);
        liveText.setPaddingRelative(dp(8), 0, 0, 0);
        liveTag.addView(liveText);
        AlphaAnimation pulse = new AlphaAnimation(1f, 0.3f);
        pulse.setDuration(750);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        liveDot.startAnimation(pulse);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, dp(28));
        llp.setMarginStart(dp(12));
        head.addView(liveTag, llp);
        head.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(k.t(L.t("Details"), Kit.BODY, Kit.ACCENT, Kit.MEDIUM));
        head.addView(new Kit.IconView(c, Kit.IconView.CHEVRON, Kit.ACCENT), new LinearLayout.LayoutParams(dp(18), dp(18)));
        card.addView(head, new LinearLayout.LayoutParams(-1, dp(36)));

        LinearLayout body = k.column();
        body.addView(hero(), new LinearLayout.LayoutParams(-1, dp(84)));
        LinearLayout tiles = new LinearLayout(c);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.addView(speedTile(), new LinearLayout.LayoutParams(0, -1, 0.88f));
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, -1, 1.32f);
        t2.setMarginStart(dp(10));
        tiles.addView(energyTile(), t2);
        LinearLayout.LayoutParams t3 = new LinearLayout.LayoutParams(0, -1, 1.0f);
        t3.setMarginStart(dp(10));
        tiles.addView(behaviorTile(), t3);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        tlp.topMargin = dp(10);
        body.addView(tiles, tlp);

        View line = new View(c);
        line.setBackgroundColor(Kit.LINE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 1);
        ll.topMargin = dp(10);
        body.addView(line, ll);
        footer = footer();
        body.addView(footer, new LinearLayout.LayoutParams(-1, dp(56)));
        content = body;
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(8);
        card.addView(body, blp);

        LinearLayout empty = k.column();
        empty.setGravity(Gravity.CENTER);
        TextView a = k.t(L.t("No trip recorded yet"), Kit.TITLE, Kit.SOFT, Kit.MEDIUM);
        a.setGravity(Gravity.CENTER);
        empty.addView(a);
        TextView b = k.t(L.t("Your first drive will appear here"), Kit.BODY, Kit.MUTED, Kit.REGULAR);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(8), 0, 0);
        empty.addView(b);
        empty.setVisibility(View.GONE);
        emptyNote = empty;
        card.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1f));
        return card;
    }

    private View hero() {
        LinearLayout row = k.row();
        row.addView(bigCell(true), new LinearLayout.LayoutParams(dp(230), -2));
        row.addView(bigCell(false), new LinearLayout.LayoutParams(dp(190), -2));

        LinearLayout s = k.row();
        s.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        score = new RingView(c, 6.8f, 24, 12);
        score.setTrackColor(0x17FFFFFF);
        s.addView(score, new LinearLayout.LayoutParams(dp(68), dp(68)));
        TextView l = k.t(L.t("Drive score"), Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        l.setMaxLines(2);
        l.setPaddingRelative(dp(10), 0, 0, 0);
        s.addView(l, new LinearLayout.LayoutParams(dp(78), -2));
        row.addView(s, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private View bigCell(boolean distance) {
        LinearLayout cell = k.column();
        TextView v = k.t("--", Kit.DISPLAY, Kit.TEXT, Kit.BOLD);
        v.setSingleLine(true);
        TextView cp = k.t(distance ? L.t("Mileage") : L.t("Duration"), Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        cell.addView(v);
        cell.addView(cp);
        if (distance) distValue = v; else durValue = v;
        return cell;
    }

    private LinearLayout tile(int icon, String label) {
        LinearLayout t = k.column();
        t.setBackground(Ui.round(Kit.SHEET, 18, c));
        t.setPaddingRelative(dp(14), dp(14), dp(14), dp(12));
        LinearLayout head = k.row();
        head.addView(new Kit.IconView(c, icon, Kit.MUTED), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView l = k.t(label, Kit.CAPTION, Kit.MUTED, Kit.MEDIUM);
        l.setSingleLine(true);
        l.setPaddingRelative(dp(8), 0, 0, 0);
        head.addView(l);
        t.addView(head, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private LinearLayout.LayoutParams blockLp() {
        return new LinearLayout.LayoutParams(-1, 0, 1f);
    }

    private TextView caption(String s) {
        TextView t = k.t(s, Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView figure(float sp) {
        TextView t = k.t("--", sp, Kit.TEXT, Kit.BOLD);
        t.setSingleLine(true);
        return t;
    }

    private View speedTile() {
        LinearLayout t = tile(Kit.IconView.SPEED, L.t("Speed"));
        LinearLayout b1 = k.column();
        b1.setGravity(Gravity.CENTER_VERTICAL);
        avgValue = figure(Kit.VALUE_L);
        b1.addView(avgValue);
        b1.addView(caption(L.t("Average")));
        t.addView(b1, blockLp());
        LinearLayout b2 = k.column();
        b2.setGravity(Gravity.CENTER_VERTICAL);
        maxValue = figure(Kit.VALUE_L);
        b2.addView(maxValue);
        b2.addView(caption(L.t("Max")));
        t.addView(b2, blockLp());
        return t;
    }

    private View energyTile() {
        LinearLayout t = tile(Kit.IconView.BATTERY, L.t("Energy"));
        LinearLayout blocks = k.column();
        blocks.setGravity(Gravity.CENTER_VERTICAL);

        // the battery: what is left, what the trip took
        LinearLayout b1 = k.column();
        LinearLayout r1 = k.row();
        socValue = figure(Kit.VALUE_L);
        r1.addView(socValue);
        usedChip = k.t("", Kit.CAPTION, 0xFFC3CAD1, Kit.MEDIUM);
        usedChip.setBackground(Ui.round(CHIP_BG, 14, c));
        usedChip.setPadding(dp(10), dp(2), dp(10), dp(2));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.setMarginStart(dp(8));
        r1.addView(usedChip, ulp);
        b1.addView(r1);
        socBar = new BarView(c);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(8));
        barLp.topMargin = dp(6);
        b1.addView(socBar, barLp);
        b1.addView(caption(L.t("Battery")));
        blocks.addView(b1, new LinearLayout.LayoutParams(-1, -2));

        // an electric trip: one consumption figure and how it compares
        LinearLayout pure = k.column();
        LinearLayout r2 = k.row();
        kwhValue = figure(Kit.VALUE_L);
        r2.addView(kwhValue);
        deltaChip = k.row();
        deltaChip.setPaddingRelative(dp(10), dp(2), dp(10), dp(2));
        deltaArrow = new Kit.IconView(c, Kit.IconView.ARROW_DOWN, Kit.ACCENT);
        deltaChip.addView(deltaArrow, new LinearLayout.LayoutParams(dp(14), dp(14)));
        deltaText = k.t("", Kit.CAPTION, Kit.ACCENT, Kit.MEDIUM);
        deltaText.setPaddingRelative(dp(4), 0, 0, 0);
        deltaChip.addView(deltaText);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.setMarginStart(dp(8));
        r2.addView(deltaChip, dlp);
        pure.addView(r2);
        pure.addView(caption(L.t("kWh per 100 km")));
        pureBlock = pure;
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(12);
        blocks.addView(pure, plp);

        // a trip that burned fuel: electric and hybrid driving apart
        LinearLayout hy = k.column();
        electricCaption = caption("");
        electricValue = figure(Kit.VALUE);
        hybridCaption = caption("");
        hybridValue = figure(Kit.VALUE);
        hy.addView(electricCaption);
        hy.addView(electricValue);
        LinearLayout.LayoutParams hc = new LinearLayout.LayoutParams(-2, -2);
        hc.topMargin = dp(4);
        hy.addView(hybridCaption, hc);
        hy.addView(hybridValue);
        hybridBlock = hy;
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(6);
        blocks.addView(hy, hlp);

        energyBlocks = blocks;
        t.addView(blocks, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout note = k.column();
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.addView(new Kit.IconView(c, Kit.IconView.LINK, Kit.DOT_OFF), new LinearLayout.LayoutParams(dp(24), dp(24)));
        energyNoteText = k.t("", Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        energyNoteText.setPadding(0, dp(8), 0, 0);
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
            TextView n = figure(Kit.VALUE_L);
            r.addView(n);
            View dot = new View(c);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(12));
            dlp.setMarginStart(dp(12));
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
        row.setPadding(0, dp(4), 0, 0);
        TextView used = k.t(L.t("Used"), Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        used.setMaxLines(2);
        row.addView(used, new LinearLayout.LayoutParams(dp(92), -2));
        TextView[] ev = item(row, Kit.IconView.BOLT, Kit.ACCENT);
        evValue = ev[0];
        evPer100 = ev[1];
        TextView[] fuel = item(row, Kit.IconView.PUMP, Kit.AMBER);
        fuelValue = fuel[0];
        fuelPer100 = fuel[1];
        costValue = item(row, Kit.IconView.COIN, Kit.MUTED)[0];
        return row;
    }

    /**
     * An icon and a value in the footer, one third of its width, with a line under the value that is only there
     * when it has something to say. Returns the value and that line.
     */
    private TextView[] item(LinearLayout row, int icon, int color) {
        LinearLayout cell = k.column();
        cell.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout top = k.row();
        top.addView(new Kit.IconView(c, icon, color), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView v = k.t("--", Kit.LABEL, Kit.TEXT, Kit.MEDIUM);
        v.setSingleLine(true);
        v.setPaddingRelative(dp(8), 0, 0, 0);
        top.addView(v);
        cell.addView(top);
        TextView per100 = k.t("", Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        per100.setSingleLine(true);
        per100.setPaddingRelative(dp(30), 0, 0, 0);
        per100.setVisibility(View.GONE);
        cell.addView(per100);
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        return new TextView[]{v, per100};
    }

    /** Shows the line under a footer figure, or takes it away. */
    private static void setLine(TextView line, String text) {
        if (text == null) {
            if (line.getVisibility() != View.GONE) line.setVisibility(View.GONE);
            return;
        }
        setText(line, text);
        if (line.getVisibility() != View.VISIBLE) line.setVisibility(View.VISIBLE);
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
                    : L.t("Turn on Vehicle data link in Setting"));
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

        setText(distValue, Kit.numUnit(L.f("%s km", Ui.f(show.distanceKm, "%.1f")), 22f / 48f));
        setText(durValue, Kit.numUnit(Ui.duration(show.durationSec()), 22f / 48f));
        double sc = Stats.score(show);
        score.set(Double.isNaN(sc) ? 0 : sc, 100, Ui.f(sc, "%.0f"), "", Kit.scoreColor(sc));

        float unit = Kit.CAPTION / Kit.VALUE_L + 0.05f;
        setText(avgValue, Kit.numUnit(L.f("%s km/h", Ui.f(show.avgSpeedKmh(), "%.0f")), unit));
        setText(maxValue, Kit.numUnit(L.f("%s km/h", Ui.f(show.maxSpeedKmh, "%.0f")), unit));

        // energy: what is left, what the trip took and what it cost per km
        double socNow = live && !Double.isNaN(l.soc) ? l.soc : show.socEnd;
        double per100 = show.energyPer100Km();
        boolean burned = Stats.burnedFuel(show);
        if (Double.isNaN(socNow) && Double.isNaN(per100) && !burned) {
            energyBlocks.setVisibility(View.GONE);
            energyNote.setVisibility(View.VISIBLE);
            setText(energyNoteText, Prefs.linkEnabled(c) ? L.t("No battery data for this trip")
                    : L.t("Battery and energy need the Vehicle data link"));
        } else {
            energyNote.setVisibility(View.GONE);
            energyBlocks.setVisibility(View.VISIBLE);
            // on a trip that burned fuel the battery figure is as big as the other two, so all three fit the tile
            float socSp = burned ? Kit.VALUE : Kit.VALUE_L;
            socValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, socSp);
            setText(socValue, Kit.numUnit(Double.isNaN(socNow) ? "--" : Ui.f(socNow, "%.0f") + "%",
                    Kit.CAPTION / socSp + 0.05f));
            double used = Double.isNaN(socNow) || Double.isNaN(show.socStart) ? Double.NaN : show.socStart - socNow;
            if (!Double.isNaN(used) && used >= 1) {
                usedChip.setVisibility(View.VISIBLE);
                setText(usedChip, L.ltr("−" + Ui.f(used, "%.0f") + "%"));
            } else {
                usedChip.setVisibility(View.GONE);
            }
            int barColor = socNow < 15 ? RED : socNow < 30 ? Kit.AMBER : Kit.ACCENT;
            socBar.set(socNow, Double.isNaN(used) ? socNow : socNow + Math.max(0, used), barColor);
            pureBlock.setVisibility(burned ? View.GONE : View.VISIBLE);
            hybridBlock.setVisibility(burned ? View.VISIBLE : View.GONE);
            if (burned) {
                showHybrid(show);
            } else {
                setText(kwhValue, Double.isNaN(per100) ? "--" : Ui.f(per100, "%.1f"));
                showDelta(show, per100, trips);
            }
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
        footer.setVisibility(any ? View.VISIBLE : View.INVISIBLE);
        float fu = Kit.CAPTION / Kit.LABEL;
        setText(evValue, Double.isNaN(e) ? "--" : Kit.numUnit(L.f("%s kWh", Ui.f(e, "%.1f")), fu));
        setText(fuelValue, Double.isNaN(f) ? "--" : Kit.numUnit(L.f("%s L", Ui.f(f, "%.1f")), fu));
        setText(costValue, Double.isNaN(cost) ? "--" : Kit.numUnit(Cost.money(c, cost), fu));
        // a trip that burned fuel: what the whole trip used per 100 km (the Energy tile has the electric and the
        // hybrid figures)
        double kwh100 = show.energyPer100Km();
        double l100 = show.fuelPer100Km();
        setLine(evPer100, burned && !Double.isNaN(kwh100) ? Ui.f(kwh100, "%.1f") + " " + L.t("kWh/100km") : null);
        setLine(fuelPer100, burned && !Double.isNaN(l100) ? Ui.f(l100, "%.1f") + " " + L.t("L/100km") : null);
    }

    /** Electric and hybrid driving apart: the electric one with what the engine charged added back (about). */
    private void showHybrid(Trip show) {
        TripEnergy e = EnergyLoader.get(c, show, host.charges());
        double evKm = show.evKm();
        double hevKm = show.hevKm();
        setText(electricCaption, L.t("Electric") + " · "
                + L.f("%s km", Double.isNaN(evKm) ? "--" : Ui.f(evKm, "%.0f")));
        setText(hybridCaption, L.t("Hybrid") + " · "
                + L.f("%s km", Double.isNaN(hevKm) ? "--" : Ui.f(hevKm, "%.0f")));
        float u = Kit.CAPTION / Kit.VALUE;
        setText(electricValue, Double.isNaN(e.electricKwhPer100)
                ? Kit.valueUnit("--", L.t("kWh/100km"), u)
                : Kit.valueUnit("≈ " + Ui.f(e.electricKwhPer100, "%.0f"), L.t("kWh/100km"), u));
        setText(hybridValue, Double.isNaN(e.hybridLPer100)
                ? Kit.valueUnit("--", L.t("L/100km"), u)
                : Kit.valueUnit(Ui.f(e.hybridLPer100, "%.1f"), L.t("L/100km"), u));
    }

    /**
     * The chip next to the consumption: how far this trip is from the middle of the earlier all-electric ones (green
     * and down when it used less). Not shown without a few earlier trips to compare with.
     */
    private void showDelta(Trip show, double per100, List<Trip> trips) {
        deltaChip.setVisibility(View.GONE);
        if (Double.isNaN(per100) || show.distanceKm < 2 || !Stats.electricOnly(show)) return;
        double typical = Stats.typicalElectric(trips, show, 10, 3);
        if (Double.isNaN(typical) || typical <= 0) return;
        double pct = (per100 - typical) / typical * 100.0;
        if (Math.abs(pct) < 2) return;
        boolean better = pct < 0;
        int col = better ? Kit.ACCENT : Kit.AMBER;
        deltaChip.setBackground(Ui.round(better ? Kit.ACCENT_SOFT : AMBER_SOFT, 14, c));
        deltaArrow.setColor(col);
        deltaArrow.setKind(better ? Kit.IconView.ARROW_DOWN : Kit.IconView.ARROW_UP);
        deltaText.setTextColor(col);
        setText(deltaText, L.ltr(Ui.f(Math.abs(pct), "%.0f") + "%"));
        deltaChip.setVisibility(View.VISIBLE);
    }
}
