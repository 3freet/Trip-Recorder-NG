package org.triprecorderng;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
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
 * The My Trips screen: a summary of all trips and the export button on top, then the trips grouped by day, each
 * a tile with its score ring, time and the figures that matter. MainActivity reaches the data and the other
 * screens through Host.
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

    private static final float SCALE = 1.35f;

    private final Context c;
    private final Host host;
    private final Kit k;

    static View build(Context c, Host host) {
        return new MyTripsPage(c, host).create();
    }

    private MyTripsPage(Context c, Host host) {
        this.c = c;
        this.host = host;
        this.k = new Kit(c, SCALE);
    }

    private View create() {
        LinearLayout col = k.column();
        List<Trip> trips = host.trips();
        col.addView(header(trips), new LinearLayout.LayoutParams(-1, k.d(46)));
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, 0, 1f);
        body.topMargin = k.d(6);
        col.addView(trips.isEmpty() ? empty() : list(trips), body);
        return col;
    }

    // ---- header ----------------------------------------------------------------------------------
    private View header(List<Trip> trips) {
        LinearLayout head = k.row();
        head.addView(k.label(L.t("My Trips"), 19, Kit.TEXT, Kit.BOLD));
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
            Kit.Chip a = k.new Chip(Kit.IconView.ROUTE, Kit.ACCENT, "",
                    L.f("%1$d trips, %2$s km", trips.size(), Ui.f(km, "%.0f")));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, k.d(34));
            alp.setMarginStart(k.d(16));
            head.addView(a.view, alp);
            if (anyCost) {
                Kit.Chip b = k.new Chip(Kit.IconView.COIN, Kit.MUTED, "", Cost.money(c, cost));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, k.d(34));
                blp.setMarginStart(k.d(8));
                head.addView(b.view, blp);
            }
        }
        head.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView export = k.pill(L.t("Export CSV"), false);
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.exportCsv();
            }
        });
        head.addView(export, new LinearLayout.LayoutParams(-2, k.d(40)));
        return head;
    }

    // ---- nothing recorded yet ---------------------------------------------------------------------
    private View empty() {
        LinearLayout card = k.card();
        card.setGravity(Gravity.CENTER);
        card.setOrientation(LinearLayout.VERTICAL);
        ImageView img = new ImageView(c);
        img.setImageResource(host.emptyImage());
        card.addView(img, new LinearLayout.LayoutParams(k.d(220), k.d(147)));
        TextView t = k.label(L.t("No trips yet"), 17, Kit.SOFT, Kit.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, k.d(10), 0, 0);
        card.addView(t, new LinearLayout.LayoutParams(-2, -2));
        final TextView restore = k.pill(L.t("Restore trips from backup"), true);
        restore.setVisibility(View.GONE);
        restore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.confirmRestore();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, k.d(40));
        rlp.topMargin = k.d(14);
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

    // ---- the list ---------------------------------------------------------------------------------
    private View list(List<Trip> trips) {
        final List<Object> items = new ArrayList<>();
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat name = new SimpleDateFormat("EEEE'" + L.sep() + "'d MMM", L.dateLocale());
        int i = 0;
        while (i < trips.size()) {
            String d = key.format(new Date(trips.get(i).startMs));
            int j = i;
            double km = 0;
            while (j < trips.size() && key.format(new Date(trips.get(j).startMs)).equals(d)) {
                km += trips.get(j).distanceKm;
                j++;
            }
            items.add(name.format(new Date(trips.get(i).startMs)) + "\n"
                    + (j - i == 1 ? L.f("%s km", Ui.f(km, "%.1f")) : L.f("%1$d trips, %2$s km", j - i, Ui.f(km, "%.0f"))));
            for (int n = i; n < j; n++) items.add(trips.get(n));
            i = j;
        }
        final ListView list = new ListView(c);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, k.d(8));
        list.setAdapter(new Kit.GroupedAdapter(items) {
            @Override View header(String text) {
                int nl = text.indexOf('\n');
                LinearLayout h = k.row();
                h.setPaddingRelative(k.d(6), k.d(12), k.d(6), k.d(8));
                h.addView(k.label(text.substring(0, nl), 14, Kit.SOFT, Kit.BOLD),
                        new LinearLayout.LayoutParams(0, -2, 1f));
                h.addView(k.label(text.substring(nl + 1), 12.5f, Kit.MUTED, Kit.MEDIUM));
                return h;
            }

            @Override View row(Object item) {
                return tripRow((Trip) item);
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Object o = items.get(pos);
                if (o instanceof Trip) host.openTrip((Trip) o);
            }
        });
        host.listBuilt(list);
        if (host.scrollFirst() > 0 || host.scrollTop() != 0) {
            list.setSelectionFromTop(host.scrollFirst(), host.scrollTop());
        }
        return list;
    }

    /** One trip: score ring, time and duration, then mileage, consumption, battery used and cost. */
    private View tripRow(Trip t) {
        FrameLayout outer = new FrameLayout(c);
        outer.setPadding(0, 0, 0, k.d(8));
        LinearLayout tile = k.row();
        tile.setBackground(Ui.round(Kit.CARD, 16 * k.scale, c));
        tile.setPaddingRelative(k.d(14), k.d(10), k.d(14), k.d(10));

        double sc = Stats.score(t);
        RingView ring = new RingView(c, 5 * k.scale, 14 * k.scale, 10 * k.scale);
        ring.setTrackColor(0x17FFFFFF);
        ring.set(Double.isNaN(sc) ? 0 : sc, 100, Ui.f(sc, "%.0f"), "", Kit.scoreColor(sc));
        tile.addView(ring, new LinearLayout.LayoutParams(k.d(46), k.d(46)));

        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        LinearLayout when = k.column();
        LinearLayout top = k.row();
        top.addView(k.label(tf.format(new Date(t.startMs)) + " – " + (t.ended ? tf.format(new Date(t.endMs)) : "..."),
                16, Kit.TEXT, Kit.BOLD));
        if (!t.ended) {
            TextView tag = k.label(L.t("Recording"), 11, Kit.ACCENT, Kit.BOLD);
            tag.setPadding(k.d(8), k.d(2), k.d(8), k.d(2));
            tag.setBackground(Ui.round(Kit.ACCENT_SOFT, 9 * k.scale, c));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.setMarginStart(k.d(10));
            top.addView(tag, tlp);
        }
        when.addView(top);
        TextView dur = k.label(Ui.duration(t.durationSec()), 13, Kit.MUTED, Kit.MEDIUM);
        dur.setPadding(0, k.d(2), 0, 0);
        when.addView(dur);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1.5f);
        wlp.setMarginStart(k.d(14));
        tile.addView(when, wlp);

        tile.addView(metric(Kit.numUnit(L.f("%s km", Ui.f(t.distanceKm, "%.1f")), 0.55f), L.t("Mileage")),
                new LinearLayout.LayoutParams(0, -2, 1f));
        double per100 = t.energyPer100Km();
        tile.addView(metric(Double.isNaN(per100) ? "--"
                        : Kit.valueUnit(Ui.f(per100, "%.1f"), L.t("kWh/100km"), 0.5f), L.t("Consumption")),
                new LinearLayout.LayoutParams(0, -2, 1.5f));
        double used = t.socUsed();
        tile.addView(metric(Double.isNaN(used) ? "--" : Kit.numUnit(Ui.f(used, "%.0f") + "%", 0.55f),
                L.t("Battery used")), new LinearLayout.LayoutParams(0, -2, 1f));
        double cost = Cost.total(t, c);
        tile.addView(metric(Double.isNaN(cost) ? "--" : Kit.numUnit(Cost.money(c, cost), 0.5f), L.t("Cost")),
                new LinearLayout.LayoutParams(0, -2, 1.3f));

        Kit.IconView chev = new Kit.IconView(c, Kit.IconView.CHEVRON, Kit.MUTED);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(k.d(16), k.d(16));
        clp.setMarginStart(k.d(6));
        tile.addView(chev, clp);
        outer.addView(tile, new FrameLayout.LayoutParams(-1, -2));
        return outer;
    }

    private LinearLayout metric(CharSequence value, String caption) {
        LinearLayout m = k.column();
        m.setPaddingRelative(0, 0, k.d(8), 0);
        TextView v = k.label("", 20, Kit.TEXT, Kit.BOLD);
        v.setText(value);
        v.setSingleLine(true);
        m.addView(v);
        TextView cap = k.label(caption, 12, Kit.MUTED, Kit.MEDIUM);
        cap.setSingleLine(true);
        cap.setPadding(0, k.d(1), 0, 0);
        m.addView(cap);
        return m;
    }
}
