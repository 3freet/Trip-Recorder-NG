package org.triprecorderng;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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
 * The My Trips screen: a summary of all trips and the export button on top, then one card per day (its date, how many
 * trips, how far and what they cost) with that day's trips as rows inside it. The column captions are given once, above
 * the list. MainActivity reaches the data and the other screens through Host.
 */
final class MyTripsPage {
    interface Host {
        /** The recorded trips, newest first. */
        List<Trip> trips();

        void openTrip(Trip t);

        void exportCsv();

        void confirmRestore();

        /** Resource id of the picture shown when there are no trips. */
        int emptyImage();

        /** Hands over the list, so the activity can keep where it was scrolled to while a detail screen is open. */
        void listBuilt(ListView list);

        int scrollFirst();

        int scrollTop();
    }

    // column widths (dp) of a trip row, shared with the caption row above the list
    private static final int W_RING = 48;
    private static final int W_TIME = 228;
    private static final int W_KM = 128;
    private static final int W_PER100 = 190;
    private static final int W_BATTERY = 122;
    private static final int W_CHEVRON = 22;

    private final Context c;
    private final Host host;
    private final Kit k;

    static View build(Context c, Host host) {
        return new MyTripsPage(c, host).create();
    }

    private MyTripsPage(Context c, Host host) {
        this.c = c;
        this.host = host;
        this.k = new Kit(c, 1f);
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }

    private View create() {
        LinearLayout col = k.column();
        List<Trip> trips = host.trips();
        col.addView(header(trips), new LinearLayout.LayoutParams(-1, dp(56)));
        if (trips.isEmpty()) {
            col.addView(empty(), new LinearLayout.LayoutParams(-1, 0, 1f));
            return col;
        }
        col.addView(captions(), new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, 0, 1f);
        body.topMargin = dp(4);
        col.addView(list(trips), body);
        return col;
    }

    // ---- header ----------------------------------------------------------------------------------
    private View header(List<Trip> trips) {
        LinearLayout head = k.row();
        if (!trips.isEmpty()) {
            double km = 0;
            double cost = 0;
            boolean anyCost = false;
            for (Trip t : trips) {
                km += t.distanceKm;
                double v = Cost.total(t, c);
                if (!Double.isNaN(v)) {
                    cost += v;
                    anyCost = true;
                }
            }
            head.addView(stat(Kit.IconView.ROUTE, Kit.ACCENT,
                    L.f("%1$d trips, %2$s km", trips.size(), String.format(Locale.US, "%,.0f", km))));
            if (anyCost) {
                View s = stat(Kit.IconView.COIN, Kit.MUTED, Cost.money(c, cost));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMarginStart(dp(22));
                head.addView(s, lp);
            }
        }
        head.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView export = k.action(L.t("Export CSV"), false);
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.exportCsv();
            }
        });
        head.addView(export, new LinearLayout.LayoutParams(-2, dp(48)));
        return head;
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
        r.addView(cap("", W_RING));
        TextView time = cap(L.t("Time"), W_TIME);
        time.setPaddingRelative(dp(14), 0, 0, 0);
        r.addView(time);
        r.addView(cap(L.t("Mileage"), W_KM));
        r.addView(cap(L.t("Per 100 km"), W_PER100));
        r.addView(cap(L.t("Battery used"), W_BATTERY));
        TextView cost = cap(L.t("Cost"), 0);
        r.addView(cost, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private TextView cap(String text, int widthDp) {
        TextView t = k.t(text, Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        t.setSingleLine(true);
        if (widthDp > 0) t.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), -2));
        return t;
    }

    // ---- nothing recorded yet ---------------------------------------------------------------------
    private View empty() {
        LinearLayout card = k.bigCard();
        card.setGravity(Gravity.CENTER);
        ImageView img = new ImageView(c);
        img.setImageResource(host.emptyImage());
        card.addView(img, new LinearLayout.LayoutParams(dp(260), dp(173)));
        TextView t = k.t(L.t("No trips yet"), Kit.LABEL, Kit.SOFT, Kit.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, 0);
        card.addView(t, new LinearLayout.LayoutParams(-2, -2));
        final TextView restore = k.action(L.t("Restore trips from backup"), true);
        restore.setVisibility(View.GONE);
        restore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.confirmRestore();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, dp(48));
        rlp.topMargin = dp(16);
        card.addView(restore, rlp);
        new Thread(new Runnable() {
            @Override public void run() {
                final int files = Backup.status(c).files;
                restore.post(new Runnable() {
                    @Override public void run() {
                        if (files > 0) {
                            restore.setText(L.f("Restore %d trip(s) from backup", files));
                            restore.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }).start();
        return card;
    }

    // ---- the list: one card per day -----------------------------------------------------------------
    private static final class Day {
        final long startMs;
        final List<Trip> trips = new ArrayList<>();

        Day(long startMs) {
            this.startMs = startMs;
        }
    }

    private View list(List<Trip> trips) {
        final List<Day> days = new ArrayList<>();
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String last = "";
        for (Trip t : trips) {
            String d = key.format(new Date(t.startMs));
            if (!d.equals(last)) {
                days.add(new Day(t.startMs));
                last = d;
            }
            days.get(days.size() - 1).trips.add(t);
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
        host.listBuilt(list);
        if (host.scrollFirst() > 0 || host.scrollTop() != 0) {
            list.setSelectionFromTop(host.scrollFirst(), host.scrollTop());
        }
        return list;
    }

    private View dayCard(Day day, SimpleDateFormat name) {
        double km = 0;
        double cost = 0;
        boolean anyCost = false;
        for (Trip t : day.trips) {
            km += t.distanceKm;
            double v = Cost.total(t, c);
            if (!Double.isNaN(v)) {
                cost += v;
                anyCost = true;
            }
        }
        String sum = (day.trips.size() == 1 ? L.f("%s km", Ui.f(km, "%.1f"))
                : L.f("%1$d trips, %2$s km", day.trips.size(), Ui.f(km, "%.0f")))
                + (anyCost ? L.sep() + Cost.money(c, cost) : "");

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
        for (Trip t : day.trips) card.addView(tripRow(t), new LinearLayout.LayoutParams(-1, dp(64)));
        outer.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return outer;
    }

    private static StateListDrawable pressable() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x14FFFFFF));
        d.addState(new int[]{}, new ColorDrawable(0x00000000));
        return d;
    }

    /** One trip: score ring, time and duration, then mileage, consumption, battery used and cost. */
    private View tripRow(final Trip t) {
        LinearLayout row = k.row();
        row.setPaddingRelative(dp(2), 0, dp(2), 0);
        row.setBackground(pressable());
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.openTrip(t);
            }
        });
        View hairline = new View(c);
        hairline.setBackgroundColor(Kit.LINE);

        double sc = Stats.score(t);
        RingView ring = new RingView(c, 4.8f, 18, 10);
        ring.setTrackColor(0x17FFFFFF);
        ring.set(Double.isNaN(sc) ? 0 : sc, 100, Ui.f(sc, "%.0f"), "", Kit.scoreColor(sc));
        row.addView(ring, new LinearLayout.LayoutParams(dp(W_RING), dp(W_RING)));

        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        LinearLayout when = k.column();
        when.setPaddingRelative(dp(14), 0, 0, 0);
        TextView range = k.t(tf.format(new Date(t.startMs)) + " – " + (t.ended ? tf.format(new Date(t.endMs)) : "..."),
                Kit.LABEL, Kit.TEXT, Kit.MEDIUM);
        range.setSingleLine(true);
        when.addView(range);
        LinearLayout second = k.row();
        TextView dur = k.t(Ui.duration(t.durationSec()), Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        dur.setSingleLine(true);
        second.addView(dur);
        if (!t.ended) {
            TextView tag = k.t(L.t("Recording"), Kit.CAPTION, Kit.ACCENT, Kit.MEDIUM);
            tag.setBackground(Ui.round(Kit.ACCENT_SOFT, 13, c));
            tag.setPadding(dp(10), dp(1), dp(10), dp(1));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.setMarginStart(dp(10));
            second.addView(tag, tlp);
        }
        when.addView(second);
        row.addView(when, new LinearLayout.LayoutParams(dp(W_TIME), -2));

        float unit = Kit.CAPTION / Kit.TITLE;
        row.addView(figure(Kit.numUnit(L.f("%s km", Ui.f(t.distanceKm, "%.1f")), unit), W_KM));

        // consumption per 100 km: kWh, and litres too on a trip that burned fuel (the whole trip, no split)
        double kwh = t.energyPer100Km();
        double lit = Stats.burnedFuel(t) ? t.fuelPer100Km() : Double.NaN;
        if (Double.isNaN(lit)) {
            row.addView(figure(Double.isNaN(kwh) ? "--"
                    : Kit.numUnit(Ui.f(kwh, "%.1f") + " kWh", unit), W_PER100));
        } else {
            LinearLayout two = k.column();
            TextView a = k.t("", Kit.BODY, Kit.TEXT, Kit.BOLD);
            a.setText(Double.isNaN(kwh) ? "--" : Kit.numUnit(Ui.f(kwh, "%.1f") + " kWh", Kit.CAPTION / Kit.BODY));
            a.setSingleLine(true);
            TextView b = k.t("", Kit.BODY, Kit.TEXT, Kit.BOLD);
            b.setText(Kit.numUnit(L.f("%s L", Ui.f(lit, "%.1f")), Kit.CAPTION / Kit.BODY));
            b.setSingleLine(true);
            two.addView(a);
            two.addView(b);
            row.addView(two, new LinearLayout.LayoutParams(dp(W_PER100), -2));
        }

        double used = t.socUsed();
        row.addView(figure(Double.isNaN(used) ? "--" : Kit.numUnit(Ui.f(used, "%.0f") + "%", unit), W_BATTERY));
        double cost = Cost.total(t, c);
        TextView costView = k.t("", Kit.TITLE, Kit.TEXT, Kit.BOLD);
        costView.setText(Double.isNaN(cost) ? "--" : Kit.numUnit(Cost.money(c, cost), unit));
        costView.setSingleLine(true);
        row.addView(costView, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(new Kit.IconView(c, Kit.IconView.CHEVRON, Kit.MUTED), new LinearLayout.LayoutParams(dp(W_CHEVRON), dp(W_CHEVRON)));

        // the rows are separated by a thin line above each one
        LinearLayout wrapper = k.column();
        wrapper.addView(hairline, new LinearLayout.LayoutParams(-1, 1));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        return wrapper;
    }

    private TextView figure(CharSequence value, int widthDp) {
        TextView v = k.t("", Kit.TITLE, Kit.TEXT, Kit.BOLD);
        v.setText(value);
        v.setSingleLine(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), -2));
        return v;
    }
}
