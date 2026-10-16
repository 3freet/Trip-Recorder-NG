package org.triprecorderng;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Charging screen: what has been added and spent on top, then one card per day with that day's sessions as rows
 * inside it (time, what was gained, the battery before and after, energy, cost, charger). The column captions are
 * given once above the list. MainActivity reaches the data and the detail screen through Host.
 */
final class ChargingPage {
    interface Host {
        List<Charge> charges();

        /** The trips: the energy of a session is estimated from how much a battery percent has taken so far. */
        List<Trip> trips();

        void openCharge(Charge ch, double kwhPerPercent);

        /** Resource id of the picture shown when there are no sessions. */
        int emptyImage();
    }

    private static final int BLUE_TEXT = 0xFF8FB2FF;
    private static final int BLUE_SOFT = 0x2E5B8DEF;
    private static final int AMBER_SOFT = 0x29F2B24C;

    // column widths (dp) of a session row, shared with the caption row above the list
    private static final int W_BADGE = 48;
    private static final int W_TIME = 228;
    private static final int W_GAIN = 110;
    private static final int W_BATTERY = 190;
    private static final int W_ENERGY = 150;
    private static final int W_COST = 150;
    private static final int W_CHEVRON = 22;

    private final Context c;
    private final Host host;
    private final Kit k;
    private final double kwhPerPct;

    static View build(Context c, Host host) {
        return new ChargingPage(c, host).create();
    }

    private ChargingPage(Context c, Host host) {
        this.c = c;
        this.host = host;
        this.k = new Kit(c, 1f);
        this.kwhPerPct = Charge.kwhPerPercent(host.trips());
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }

    private View create() {
        LinearLayout col = k.column();
        List<Charge> charges = host.charges();
        col.addView(header(charges), new LinearLayout.LayoutParams(-1, dp(56)));
        if (charges.isEmpty()) {
            col.addView(empty(), new LinearLayout.LayoutParams(-1, 0, 1f));
            return col;
        }
        col.addView(captions(), new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, 0, 1f);
        body.topMargin = dp(4);
        col.addView(list(charges), body);
        return col;
    }

    private View header(List<Charge> charges) {
        LinearLayout head = k.row();
        if (!charges.isEmpty()) {
            double kwh = 0;
            double cost = 0;
            boolean anyKwh = false;
            boolean anyCost = false;
            for (Charge ch : charges) {
                double e = ch.energyKwh(kwhPerPct);
                if (!Double.isNaN(e)) {
                    kwh += e;
                    anyKwh = true;
                }
                double v = Cost.charge(ch, kwhPerPct, c);
                if (!Double.isNaN(v)) {
                    cost += v;
                    anyCost = true;
                }
            }
            head.addView(stat(Kit.IconView.LIST, BLUE_TEXT, L.f("%d session(s)", charges.size())));
            if (anyKwh) {
                head.addView(stat(Kit.IconView.BOLT, BLUE_TEXT, L.f("about %s kWh added", Ui.f(kwh, "%.0f"))), gapped());
            }
            if (anyCost) {
                head.addView(stat(Kit.IconView.COIN, Kit.MUTED, L.f("about %s", Cost.money(c, cost))), gapped());
            }
        }
        return head;
    }

    private LinearLayout.LayoutParams gapped() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(22));
        return lp;
    }

    private View stat(int icon, int color, String text) {
        LinearLayout s = k.row();
        s.addView(new Kit.IconView(c, icon, color), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = k.t(text, Kit.BODY, Kit.SOFT, Kit.MEDIUM);
        t.setSingleLine(true);
        t.setPaddingRelative(dp(8), 0, 0, 0);
        s.addView(t);
        return s;
    }

    private View captions() {
        LinearLayout r = k.row();
        r.setPaddingRelative(dp(16), 0, dp(16), 0);
        r.addView(cap("", W_BADGE));
        TextView time = cap(L.t("Time"), W_TIME);
        time.setPaddingRelative(dp(14), 0, 0, 0);
        r.addView(time);
        r.addView(cap(L.t("Gained"), W_GAIN));
        r.addView(cap(L.t("Battery"), W_BATTERY));
        r.addView(cap(L.t("Energy added"), W_ENERGY));
        r.addView(cap(L.t("Cost"), W_COST));
        r.addView(cap(L.t("Type"), 0), new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private TextView cap(String text, int widthDp) {
        TextView t = k.t(text, Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        t.setSingleLine(true);
        if (widthDp > 0) t.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), -2));
        return t;
    }

    private View empty() {
        LinearLayout card = k.bigCard();
        card.setGravity(Gravity.CENTER);
        ImageView img = new ImageView(c);
        img.setImageResource(host.emptyImage());
        card.addView(img, new LinearLayout.LayoutParams(dp(260), dp(173)));
        TextView t = k.t(L.t("No charging sessions yet"), Kit.LABEL, Kit.SOFT, Kit.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, 0);
        card.addView(t, new LinearLayout.LayoutParams(-2, -2));
        TextView hint = k.t(Prefs.linkEnabled(c)
                ? L.t("A session is recorded automatically while the car charges")
                : L.t("Turn on Vehicle data link in Setting: charging is read from the car through it"),
                Kit.BODY, Kit.MUTED, Kit.REGULAR);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(8), dp(24), 0);
        card.addView(hint, new LinearLayout.LayoutParams(-2, -2));
        return card;
    }

    // ---- the list: one card per day -----------------------------------------------------------------
    private static final class Day {
        final long startMs;
        final List<Charge> sessions = new ArrayList<>();

        Day(long startMs) {
            this.startMs = startMs;
        }
    }

    private View list(List<Charge> charges) {
        final List<Day> days = new ArrayList<>();
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String last = "";
        for (Charge ch : charges) {
            String d = key.format(new Date(ch.startMs));
            if (!d.equals(last)) {
                days.add(new Day(ch.startMs));
                last = d;
            }
            days.get(days.size() - 1).sessions.add(ch);
        }
        final SimpleDateFormat name = new SimpleDateFormat("EEEE'" + L.sep() + "'d MMM", L.dateLocale());
        ListView list = new ListView(c);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(8));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return days.size(); }
            @Override public Object getItem(int i) { return days.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public boolean isEnabled(int i) { return false; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                return dayCard(days.get(i), name);
            }
        });
        return list;
    }

    private View dayCard(Day day, SimpleDateFormat name) {
        double gain = 0;
        double kwh = 0;
        boolean anyKwh = false;
        for (Charge ch : day.sessions) {
            double g = ch.socGained();
            if (!Double.isNaN(g)) gain += g;
            double e = ch.energyKwh(kwhPerPct);
            if (!Double.isNaN(e)) {
                kwh += e;
                anyKwh = true;
            }
        }
        String sum = (day.sessions.size() == 1 ? "" : L.f("%d session(s)", day.sessions.size()) + L.sep())
                + L.ltr("+" + Ui.f(gain, "%.0f") + "%")
                + (anyKwh ? L.sep() + L.f("about %s kWh", Ui.f(kwh, "%.1f")) : "");

        LinearLayout outer = new LinearLayout(c);
        outer.setPadding(0, 0, 0, dp(12));
        LinearLayout card = k.bigCard();
        card.setPaddingRelative(dp(14), dp(4), dp(14), dp(6));
        LinearLayout head = k.row();
        head.setPaddingRelative(dp(2), 0, dp(2), 0);
        TextView date = k.t(name.format(new Date(day.startMs)), Kit.LABEL, Kit.TEXT, Kit.MEDIUM);
        date.setSingleLine(true);
        head.addView(date, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView total = k.t(sum, Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        total.setSingleLine(true);
        head.addView(total);
        card.addView(head, new LinearLayout.LayoutParams(-1, dp(44)));
        for (Charge ch : day.sessions) card.addView(sessionRow(ch), new LinearLayout.LayoutParams(-1, dp(64)));
        outer.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return outer;
    }

    private static StateListDrawable pressable() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x14FFFFFF));
        d.addState(new int[]{}, new ColorDrawable(0x00000000));
        return d;
    }

    private static String kwhText(Charge ch, double kwhPerPct) {
        double e = ch.energyKwh(kwhPerPct);
        return Double.isNaN(e) ? "--" : L.ltr((ch.energyIsEstimate() ? "~" : "") + Ui.f(e, "%.1f"));
    }

    /** One session: a bolt badge, time and duration, the battery before and after, energy, cost and charger type. */
    private View sessionRow(final Charge ch) {
        LinearLayout row = k.row();
        row.setPaddingRelative(dp(2), 0, dp(2), 0);
        row.setBackground(pressable());
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.openCharge(ch, kwhPerPct);
            }
        });
        float unit = Kit.CAPTION / Kit.TITLE;

        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.round(ch.partial ? AMBER_SOFT : BLUE_SOFT, 24, c));
        Kit.IconView bolt = new Kit.IconView(c, Kit.IconView.BOLT, ch.partial ? Kit.AMBER : BLUE_TEXT);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(26), dp(26));
        blp.gravity = Gravity.CENTER;
        badge.addView(bolt, blp);
        row.addView(badge, new LinearLayout.LayoutParams(dp(W_BADGE), dp(W_BADGE)));

        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        LinearLayout when = k.column();
        when.setPaddingRelative(dp(14), 0, 0, 0);
        TextView range = k.t(tf.format(new Date(ch.startMs)) + " – "
                + (ch.ended ? tf.format(new Date(ch.endMs)) : "..."), Kit.LABEL, Kit.TEXT, Kit.MEDIUM);
        range.setSingleLine(true);
        when.addView(range);
        LinearLayout second = k.row();
        TextView dur = k.t(Ui.duration(ch.durationSec()), Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        dur.setSingleLine(true);
        second.addView(dur);
        if (!ch.ended) second.addView(tag(L.t("Charging"), BLUE_TEXT, BLUE_SOFT));
        if (ch.partial) second.addView(tag(L.t("Partial"), Kit.AMBER, AMBER_SOFT));
        when.addView(second);
        row.addView(when, new LinearLayout.LayoutParams(dp(W_TIME), -2));

        double gained = ch.socGained();
        TextView gain = k.t("", Kit.TITLE, Kit.TEXT, Kit.BOLD);
        gain.setText(Double.isNaN(gained) ? "--"
                : Kit.numUnit(L.ltr((gained >= 0 ? "+" : "") + Ui.f(gained, "%.0f") + "%"), unit));
        gain.setSingleLine(true);
        row.addView(gain, new LinearLayout.LayoutParams(dp(W_GAIN), -2));

        LinearLayout bat = k.column();
        TextView range2 = k.t(L.ltr(Ui.f(ch.socStart, "%.0f") + " → " + Ui.f(ch.socEnd, "%.0f")), Kit.LABEL, Kit.TEXT, Kit.BOLD);
        range2.setSingleLine(true);
        bat.addView(range2);
        Kit.RangeBar bar = new Kit.RangeBar(c);
        bar.set(ch.socStart, ch.socEnd, Kit.BLUE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(140), dp(6));
        barLp.topMargin = dp(5);
        bat.addView(bar, barLp);
        row.addView(bat, new LinearLayout.LayoutParams(dp(W_BATTERY), -2));

        double e = ch.energyKwh(kwhPerPct);
        TextView energy = k.t("", Kit.TITLE, Kit.TEXT, Kit.BOLD);
        energy.setText(Double.isNaN(e) ? "--" : Kit.valueUnit(kwhText(ch, kwhPerPct), "kWh", unit));
        energy.setSingleLine(true);
        row.addView(energy, new LinearLayout.LayoutParams(dp(W_ENERGY), -2));

        double cost = Cost.charge(ch, kwhPerPct, c);
        TextView costView = k.t("", Kit.TITLE, Kit.TEXT, Kit.BOLD);
        costView.setText(Double.isNaN(cost) ? "--" : Kit.numUnit(Cost.money(c, cost), unit));
        costView.setSingleLine(true);
        row.addView(costView, new LinearLayout.LayoutParams(dp(W_COST), -2));

        TextView type = k.t(ch.typeName().isEmpty() ? "--" : ch.typeName(), Kit.LABEL, Kit.TEXT, Kit.MEDIUM);
        type.setSingleLine(true);
        row.addView(type, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(new Kit.IconView(c, Kit.IconView.CHEVRON, Kit.MUTED), new LinearLayout.LayoutParams(dp(W_CHEVRON), dp(W_CHEVRON)));

        View hairline = new View(c);
        hairline.setBackgroundColor(Kit.LINE);
        LinearLayout wrapper = k.column();
        wrapper.addView(hairline, new LinearLayout.LayoutParams(-1, 1));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        return wrapper;
    }

    private TextView tag(String text, int fg, int bg) {
        TextView t = k.t(text, Kit.CAPTION, fg, Kit.MEDIUM);
        t.setBackground(Ui.round(bg, 13, c));
        t.setPadding(dp(10), dp(1), dp(10), dp(1));
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(10));
        t.setLayoutParams(lp);
        return t;
    }
}
