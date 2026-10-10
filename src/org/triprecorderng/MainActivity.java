package org.triprecorderng;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Main screen laid out like the stock "Driving behavior" app (shown as Trip Recorder NG): a title bar with Q&A and Setting
 * buttons, a left tab menu (This Trip, My Trips, Attainment, Statistics) and a content area.
 */
public class MainActivity extends Activity {
    private static final int TAB_THIS = 0;
    private static final int TAB_TRIPS = 1;
    private static final int TAB_ATTAIN = 2;
    private static final int TAB_STATS = 3;
    private static final int TAB_CHARGE = 4;
    private static final int TAB_TWEAKS = 5;
    private static String appTitle() {
        return "Trip Recorder NG"; // the name is not translated
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<Trip> trips = new ArrayList<>();
    private List<Charge> charges = new ArrayList<>();

    private FrameLayout content;
    private View navView;
    private View qaButton;
    private View settingButton;
    private TextView titleView;
    private final LinearLayout[] tabViews = new LinearLayout[6];
    private final int[] tabIcons = new int[6];
    private int tab = TAB_THIS;
    private String overlay = null; // "detail", "settings", "qa" or null
    private volatile boolean updateBusy;
    private boolean advancedOpen;
    private TextView updatesValue, channelValue;

    // live widgets of the currently shown page
    private Runnable pageRefresh;
    private int statsMetric = Stats.MILEAGE;
    private boolean statsMonthly = false;
    private long lastLoad;
    // scroll position of the My Trips list, kept across the periodic refresh and the detail screen
    private ListView tripsList;
    private int tripsFirst;
    private int tripsTop;

    // ---- lifecycle -------------------------------------------------------------------------
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        freshOpening = b == null;
        Diag.init(this);
        buildShell();
        handleExtras(getIntent());
        requestPerms(false);
        startRecorder();
        select(TAB_THIS);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExtras(intent);
    }

    /** Test hook: "adb shell am start ... --ez demo_trips true" adds sample trips, clear_demo removes them. */
    private void handleExtras(final Intent i) {
        if (i == null) return;
        final boolean add = i.getBooleanExtra("demo_trips", false);
        final boolean clear = i.getBooleanExtra("clear_demo", false);
        if (!add && !clear) return;
        new Thread(new Runnable() {
            @Override public void run() {
                if (clear) DemoData.clear(MainActivity.this);
                if (add) {
                    DemoData.insert(MainActivity.this);
                    DemoData.insertCharges(MainActivity.this);
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        loadTrips(null);
                    }
                });
            }
        }).start();
    }

    /** A new instance of the screen (not a re-creation for a language change) counts as an opening of the app. */
    private boolean freshOpening;
    private static long lastStopMs;
    /**
     * Coming back after less than this is the same visit, not a new opening: a language change re-creates the screen
     * within a second. (Going to BYD's settings screen from the reminder has its own 10 minute pause.)
     */
    private static final long NEW_OPENING_AFTER_MS = 3_000;

    @Override protected void onResume() {
        super.onResume();
        ui.post(ticker);
        long away = android.os.SystemClock.elapsedRealtime() - lastStopMs;
        if (freshOpening || away > NEW_OPENING_AFTER_MS) ui.postDelayed(autoStartCheck, 1200);
        freshOpening = false;
    }

    @Override protected void onStop() {
        super.onStop();
        lastStopMs = android.os.SystemClock.elapsedRealtime();
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(autoStartCheck);
    }

    private final Runnable autoStartCheck = new Runnable() {
        @Override public void run() {
            maybeAutoStartAlert();
        }
    };

    private AlertDialog autoStartDialog;

    /**
     * When BYD has not let the app start by itself (its "Disable background Apps" switch is on for
     * Trip Recorder NG), tell the user how to fix it, every time the app is opened. The state is inferred from
     * whether the system's start broadcast reached the app after the last restart (BYD's own switch cannot be
     * read). Skipped while driving, in the first minutes after the head unit boots (the broadcast may simply not
     * have arrived yet) and while the user is in BYD's settings screen. "Don't show again" mutes it, but every
     * 15th opening it comes back anyway.
     */
    private void maybeAutoStartAlert() {
        if (isFinishing() || isDestroyed()) return;
        if (autoStartDialog != null && autoStartDialog.isShowing()) return;
        Intent in = getIntent();
        if (in != null && in.getBooleanExtra("skip_alert", false)) return;
        Prefs.noteRun(this);
        int health = Prefs.autoStartHealth(this);
        if (health == Prefs.AUTOSTART_OK) return;
        if (android.os.SystemClock.elapsedRealtime() < 180_000) return;
        double speed = RecorderService.live.speed;
        if (!Double.isNaN(speed) && speed >= 5) return;
        if (System.currentTimeMillis() - autostartScreenOpenedMs < 10 * 60_000) return;
        boolean reminder = false;
        if (Prefs.autoStartAlertMuted(this)) {
            if (!Prefs.countMutedOpening(this)) return;
            reminder = true;
        }
        final boolean again = reminder;
        String how = L.t("Open Setting > BYD auto-start apps, find Trip Recorder NG in 'Disable background Apps' and "
                + "turn it OFF (OFF means the app is allowed to start). Then restart the head unit once.");
        String why = health == Prefs.AUTOSTART_BLOCKED
                ? L.t("BYD did not start Trip Recorder NG by itself after the last restart, so trips are not "
                + "recorded until you open the app.\n\n")
                : L.t("Auto-start is not confirmed yet. BYD switches it back to blocked every time Trip "
                + "Recorder is installed or updated, and until it is allowed the app does not record "
                + "after a restart.\n\n");
        String note = reminder
                ? L.f("You chose not to see this reminder again. It comes back every %d openings of the app "
                + "while auto-start is still blocked.\n\n", Prefs.MUTED_REMINDER_EVERY)
                : "";
        try {
            autoStartDialog = Ui.show(new AlertDialog.Builder(this)
                    .setTitle(L.t("Turn off 'Disable background Apps'"))
                    .setMessage(note + why + how)
                    .setPositiveButton(L.t("Open BYD settings"), new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            openAutostart();
                        }
                    })
                    .setNeutralButton(again ? L.t("Show every time") : L.t("Don't show again"),
                            new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            Prefs.setAutoStartAlertMuted(MainActivity.this, !again);
                            toast(again ? L.t("This reminder will show every time the app is opened.")
                                    : L.f("Understood. The reminder comes back after %d openings of the app.",
                                    Prefs.MUTED_REMINDER_EVERY));
                        }
                    })
                    .setNegativeButton(L.t("Later"), null));
        } catch (Throwable t) {
            Diag.log("could not show the auto-start reminder", t);
        }
    }

    /** When the user last went to BYD's auto-start screen from this app; the reminder waits a while after that. */
    private static long autostartScreenOpenedMs;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (now - lastLoad > 8000) loadTrips(null);
            if (pageRefresh != null) pageRefresh.run();
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onBackPressed() {
        if (overlay != null) closeOverlay(); else super.onBackPressed();
    }

    private void loadTrips(final Runnable then) {
        lastLoad = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Trip> all = TripDb.get(MainActivity.this).all();
                final List<Charge> allCharges = TripDb.get(MainActivity.this).charges();
                ui.post(new Runnable() {
                    @Override public void run() {
                        trips = all;
                        charges = allCharges;
                        if (then != null) then.run();
                        else if (overlay == null && (tab == TAB_TRIPS || tab == TAB_ATTAIN
                                || tab == TAB_STATS || tab == TAB_CHARGE)) renderCurrentTab();
                    }
                });
            }
        }).start();
    }

    // ---- shell: top bar, tab menu, content -------------------------------------------------
    private void buildShell() {
        int p24 = Ui.dp(this, 24);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // title bar and menu stay where the driver reaches them
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.BG_CENTER, Ui.BG, Ui.BG});
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setGradientRadius(Ui.dp(this, 900));
        bg.setGradientCenter(0.7f, 0.35f);
        root.setBackground(bg);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // title bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(p24, Ui.dp(this, 14), p24, 0);
        GlyphView back = new GlyphView(this, GlyphView.BACK);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onBackPressed();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        titleView = Ui.text(this, appTitle(), 26, Ui.TEXT_VALUE);
        titleView.setPadding(Ui.dp(this, 12), 0, 0, 0);
        titleView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START); // the title bar stays left to right
        bar.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        qaButton = barButton(GlyphView.HELP, L.t("Driving Behavior Q&A"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showOverlay("qa");
            }
        });
        bar.addView(qaButton);
        settingButton = barButton(GlyphView.GEAR, L.t("Setting"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showOverlay("settings");
            }
        });
        bar.addView(settingButton);
        column.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(0, Ui.dp(this, 20), 0, 0);
        body.addView(nav, new LinearLayout.LayoutParams(Ui.dp(this, 260), -1));
        navView = nav;
        tabIcons[0] = res("nav_this_trip");
        tabIcons[1] = res("nav_my_trips");
        tabIcons[2] = res("nav_attainment");
        tabIcons[3] = res("nav_statistics");
        tabIcons[4] = res("nav_charging");
        tabIcons[5] = res("nav_tweaks");
        String[] names = {L.t("This Trip"), L.t("My Trips"), L.t("Attainment"), L.t("Statistics"), L.t("Charging"), L.t("Tweaks")};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.HORIZONTAL);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 12), 0);
            ImageView icon = new ImageView(this);
            icon.setImageResource(tabIcons[i]);
            t.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
            TextView label = Ui.text(this, names[i], 22, Ui.TEXT_VALUE);
            label.setPadding(Ui.dp(this, 14), 0, 0, 0);
            t.addView(label);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx != tab) {
                        tripsFirst = 0;
                        tripsTop = 0;
                    }
                    overlay = null;
                    select(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
            lp.setMargins(Ui.dp(this, 24), Ui.dp(this, i == 0 ? 8 : 16), Ui.dp(this, 12), 0);
            nav.addView(t, lp);
            tabViews[i] = t;
        }

        content = new FrameLayout(this);
        content.setLayoutDirection(Ui.contentDirection()); // only the content area flips for Arabic
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1f);
        clp.setMargins(0, Ui.dp(this, 14), p24, p24);
        body.addView(content, clp);

        setContentView(root);
    }

    /** Full-screen pages (settings, Q&A, trip details) hide the menu like the stock app does. */
    private void setChrome(boolean fullScreenPage) {
        int vis = fullScreenPage ? View.GONE : View.VISIBLE;
        ((LinearLayout.LayoutParams) content.getLayoutParams()).leftMargin =
                fullScreenPage ? Ui.dp(this, 24) : 0;
        content.requestLayout();
        navView.setVisibility(vis);
        qaButton.setVisibility(vis);
        settingButton.setVisibility(vis);
    }

    private int res(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private View barButton(int glyph, String text, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 6), 0);
        row.addView(new GlyphView(this, glyph), new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
        TextView t = Ui.text(this, text, 20, Ui.TEXT_VALUE);
        t.setPadding(Ui.dp(this, 8), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(l);
        return row;
    }

    private void select(int index) {
        tab = index;
        overlay = null;
        setChrome(false);
        titleView.setText(appTitle());
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == index;
            LinearLayout t = tabViews[i];
            t.setBackgroundResource(sel ? res("tab_selected_bg") : 0);
            ((TextView) t.getChildAt(1)).setTextColor(sel ? Ui.TEXT_SELECTED : 0xCCFFFFFF);
            ((ImageView) t.getChildAt(0)).setColorFilter(sel ? 0xFFFFFFFF : 0xCCFFFFFF,
                    PorterDuff.Mode.SRC_IN);
        }
        renderCurrentTab();
    }

    private void saveTripsScroll() {
        if (tripsList == null || tripsList.getParent() == null) return;
        tripsFirst = tripsList.getFirstVisiblePosition();
        View c = tripsList.getChildAt(0);
        tripsTop = c == null ? 0 : c.getTop() - tripsList.getPaddingTop();
    }

    private void renderCurrentTab() {
        pageRefresh = null;
        if (tab == TAB_TRIPS) saveTripsScroll();
        content.removeAllViews();
        switch (tab) {
            case TAB_THIS: content.addView(thisTripPage()); break;
            case TAB_TRIPS: content.addView(myTripsPage()); break;
            case TAB_ATTAIN: content.addView(attainmentPage()); break;
            case TAB_CHARGE: content.addView(chargingPage()); break;
            case TAB_TWEAKS: content.addView(tweaksPage()); break;
            default: content.addView(statisticsPage()); break;
        }
    }

    private void showOverlay(String which) {
        overlay = which;
        pageRefresh = null;
        setChrome(true);
        content.removeAllViews();
        if ("settings".equals(which)) {
            titleView.setText(L.t("Setting"));
            content.addView(settingsPage());
        } else {
            titleView.setText(L.t("Driving Behavior Q&A"));
            content.addView(qaPage());
        }
    }

    private void closeOverlay() {
        overlay = null;
        select(tab);
    }

    // ---- small builders --------------------------------------------------------------------
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(Ui.CARD, 8, this));
        return c;
    }

    private TextView pill(String text, View.OnClickListener l) {
        TextView t = Ui.text(this, text, 18, Ui.TEXT_VALUE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        t.setBackground(Ui.stroke(0x4DFFFFFF, 0x00000000, 20, this));
        t.setOnClickListener(l);
        return t;
    }

    private TextView pageTitle(String s) {
        TextView t = Ui.text(this, s, 24, Ui.TEXT_VALUE);
        t.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 12));
        return t;
    }

    /** The name of the vehicle data source as shown to the user (the code compares the English names). */
    private static String vehicleName(String name) {
        if ("BYD API via helper".equals(name)) return L.t("BYD API via helper");
        if ("GPS only".equals(name)) return L.t("GPS only");
        if ("BYD framework API".equals(name)) return L.t("BYD framework API");
        if ("autoservice binder".equals(name)) return L.t("autoservice binder");
        if ("none".equals(name)) return L.t("none");
        return name;
    }

    // ---- This Trip -------------------------------------------------------------------------
    private View thisTripPage() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        // left card: live speed ring + state
        LinearLayout left = card();
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setPadding(Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 16));
        final RingView ring = new RingView(this, 10, 44, 16);
        left.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 220)));
        final TextView state = Ui.text(this, "", 22, Ui.TEXT_SELECTED);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, Ui.dp(this, 22), 0, 0);
        left.addView(state, new LinearLayout.LayoutParams(-1, -2));
        final TextView sub = Ui.text(this, "", 16, Ui.TEXT_LABEL);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 10), 0, 0);
        left.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(left, new LinearLayout.LayoutParams(Ui.dp(this, 312), -1));

        // right card: 3 x 3 grid of values
        LinearLayout right = card();
        right.setPadding(Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 20));
        final TextView head = Ui.text(this, "", 18, Ui.TEXT_DATE);
        right.addView(head);
        final TextView[][] cells = new TextView[3][3];
        String[][] labels = {
                {L.t("Mileage (km)"), L.t("Duration"), L.t("Average speed (km/h)")},
                {L.t("Max speed (km/h)"), L.t("Battery (%)"), L.t("Energy (kWh/100km)")},
                {L.t("Hard acceleration"), L.t("Hard braking"), L.t("Drive score")}};
        for (int r = 0; r < 3; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                LinearLayout cell = Ui.stat(this, "--", labels[r][c], 34, 16);
                cells[r][c] = (TextView) cell.getChildAt(0);
                line.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
            right.addView(line, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1f);
        rlp.setMarginStart(Ui.dp(this, 24));
        row.addView(right, rlp);

        pageRefresh = new Runnable() {
            @Override public void run() {
                RecorderService.Live l = RecorderService.live;
                Trip t = RecorderService.current;
                double speed = l.speed;
                ring.set(Double.isNaN(speed) ? 0 : speed, 180,
                        Double.isNaN(speed) ? "--" : String.format(Locale.US, "%.0f", speed),
                        L.t("km/h"), Ui.BLUE);
                if (!l.running) {
                    state.setText(L.t("Service stopped"));
                } else if (t != null) {
                    state.setText(L.t("Recording"));
                } else {
                    state.setText(L.t("Not recording"));
                }
                sub.setText(L.f("Vehicle data: %s", vehicleName(l.vehicle)) + "\n"
                        + L.f("GPS: %s", l.gps ? (l.gpsFix ? L.t("fix") : L.t("searching")) : L.t("off"))
                        + (Double.isNaN(l.power) ? "" : "\n" + L.f("Power level %d", (int) l.power))
                        + ("GPS only".equals(l.vehicle)
                        ? (Prefs.linkEnabled(MainActivity.this)
                        ? "\n" + L.f("Data link: %s", HelperLauncher.statusText(MainActivity.this))
                        : "\n" + L.t("Turn on Vehicle data link in Setting for battery and energy")) : "")
                        + (Double.isNaN(l.elecRange) && Double.isNaN(l.fuelRange) ? ""
                        : "\n" + L.t("Range:") + (Double.isNaN(l.elecRange) ? "" : " " + L.f("EV %s km", Ui.f(l.elecRange, "%.0f")))
                        + (Double.isNaN(l.fuelRange) ? "" : "  " + L.f("fuel %s km", Ui.f(l.fuelRange, "%.0f"))
                        + (Double.isNaN(l.fuelPct) ? "" : " (" + Ui.f(l.fuelPct, "%.0f") + "%)")))
                        + (ChargeRecorder.status.isEmpty() ? "" : "\n" + ChargeRecorder.status)
                        + (Prefs.autoStartHealth(MainActivity.this) == Prefs.AUTOSTART_BLOCKED
                        ? "\n" + L.t("Auto-start is blocked by BYD - see Setting") : ""));
                Trip show = t;
                if (show == null && !trips.isEmpty()) show = trips.get(0);
                if (show == null) {
                    head.setText(L.t("No trip recorded yet"));
                    for (TextView[] rr : cells) for (TextView c : rr) c.setText("--");
                    return;
                }
                head.setText(t != null ? L.t("Current trip")
                        : L.f("Last trip - %s", new SimpleDateFormat("d MMM HH:mm", L.dateLocale())
                        .format(new Date(show.startMs))));
                double sc = Stats.score(show);
                cells[0][0].setText(Ui.f(show.distanceKm, "%.1f"));
                cells[0][1].setText(Ui.duration(show.durationSec()));
                cells[0][2].setText(Ui.f(show.avgSpeedKmh(), "%.0f"));
                cells[1][0].setText(Ui.f(show.maxSpeedKmh, "%.0f"));
                double socNow = t != null && !Double.isNaN(l.soc) ? l.soc : show.socEnd;
                cells[1][1].setText(Ui.f(socNow, "%.0f"));
                cells[1][2].setText(Ui.f(show.energyPer100Km(), "%.1f"));
                cells[2][0].setText(String.valueOf(show.hardAccel));
                cells[2][1].setText(String.valueOf(show.hardBrake));
                cells[2][2].setText(Ui.f(sc, "%.0f"));
            }
        };
        pageRefresh.run();
        return row;
    }

    // ---- My Trips --------------------------------------------------------------------------
    private View myTripsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        double km = 0;
        for (Trip t : trips) km += t.distanceKm;
        TextView title = pageTitle(L.t("My Trips"));
        title.setPaddingRelative(Ui.dp(this, 4), 0, 0, 0);
        head.addView(title);
        double totalCost = 0;
        boolean anyCost = false;
        for (Trip t : trips) {
            double c = Cost.total(t, this);
            if (!Double.isNaN(c)) {
                totalCost += c;
                anyCost = true;
            }
        }
        TextView tip = Ui.text(this, "   " + L.f("%1$d trips, %2$s km", trips.size(), Ui.f(km, "%.0f"))
                + (anyCost ? L.sep() + Cost.money(this, totalCost) : ""), 16, Ui.TEXT_LABEL);
        head.addView(tip, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(pill(L.t("Export CSV"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                exportCsv();
            }
        }));
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) head.getLayoutParams()).bottomMargin = Ui.dp(this, 12);

        LinearLayout box = card();
        col.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (trips.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(this);
            img.setImageResource(res("empty_trips"));
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 312), Ui.dp(this, 208));
            imgLp.gravity = Gravity.CENTER_HORIZONTAL;
            empty.addView(img, imgLp);
            TextView t = Ui.text(this, L.t("No trips yet"), 20, Ui.TEXT_LABEL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 12), 0, 0);
            empty.addView(t, new LinearLayout.LayoutParams(-1, -2));
            final TextView restoreBtn = pill(L.t("Restore trips from backup"), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirmRestore();
                }
            });
            restoreBtn.setVisibility(View.GONE);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.gravity = Gravity.CENTER_HORIZONTAL;
            rlp.topMargin = Ui.dp(this, 16);
            empty.addView(restoreBtn, rlp);
            new Thread(new Runnable() {
                @Override public void run() {
                    final int files = Backup.status(MainActivity.this).files;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (files > 0) {
                                restoreBtn.setText(L.f("Restore %d trip(s) from backup", files));
                                restoreBtn.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                }
            }).start();
            box.addView(empty, new LinearLayout.LayoutParams(-1, -1));
            return col;
        }

        final List<Object> items = new ArrayList<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String last = "";
        for (Trip t : trips) {
            String d = day.format(new Date(t.startMs));
            if (!d.equals(last)) {
                items.add(d);
                last = d;
            }
            items.add(t);
        }
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 8));
        list.setClipToPadding(false);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return items.get(i) instanceof String ? 0 : 1; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                Object o = items.get(i);
                if (o instanceof String) {
                    TextView t = Ui.text(MainActivity.this, (String) o, 20, Ui.TEXT_DATE);
                    t.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 16),
                            0, Ui.dp(MainActivity.this, 4));
                    return t;
                }
                return tripRow((Trip) o);
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Object o = items.get(pos);
                if (o instanceof Trip) showDetail((Trip) o);
            }
        });
        box.addView(list, new LinearLayout.LayoutParams(-1, -1));
        tripsList = list;
        if (tripsFirst > 0 || tripsTop != 0) list.setSelectionFromTop(tripsFirst, tripsTop);
        return col;
    }

    /** One trip row: time range, four value/label cells and the score ring. */
    private View tripRow(Trip t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), 0);

        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        String range = tf.format(new Date(t.startMs)) + "-"
                + (t.ended ? tf.format(new Date(t.endMs)) : "...");
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.addView(Ui.text(this, range, 16, Ui.TEXT_LABEL));
        if (!t.ended) {
            TextView tag = Ui.text(this, L.t("Recording"), 11, Ui.GREEN);
            tag.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
            tag.setBackground(Ui.stroke(Ui.GREEN, 0, 9, this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(Ui.dp(this, 12));
            timeRow.addView(tag, lp);
        }
        row.addView(timeRow);

        LinearLayout data = new LinearLayout(this);
        data.setOrientation(LinearLayout.HORIZONTAL);
        data.setGravity(Gravity.CENTER_VERTICAL);
        data.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14));
        data.addView(Ui.stat(this, Ui.f(t.distanceKm, "%.1f"), L.t("Mileage (km)"), 20, 14),
                new LinearLayout.LayoutParams(0, -2, 1f));
        data.addView(Ui.stat(this, Ui.duration(t.durationSec()), L.t("Duration"), 20, 14),
                new LinearLayout.LayoutParams(0, -2, 1f));
        data.addView(Ui.stat(this, Ui.f(t.energyPer100Km(), "%.1f"), L.t("Consumption (kWh/100km)"), 20, 14),
                new LinearLayout.LayoutParams(0, -2, 1.3f));
        if (Cost.enabled(this)) {
            data.addView(Ui.stat(this, Cost.amount(this, Cost.total(t, this)),
                    L.f("Cost (%s)", Prefs.currency(this)), 20, 14), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        data.addView(Ui.stat(this, Ui.f(t.socUsed(), "%.0f"), L.t("Battery used (%)"), 20, 14),
                new LinearLayout.LayoutParams(0, -2, 1f));
        double sc = Stats.score(t);
        RingView ring = new RingView(this, 6, 20, 12);
        ring.set(Double.isNaN(sc) ? 0 : sc, 100, Double.isNaN(sc) ? "--" : String.format(Locale.US, "%.0f", sc),
                null, Ui.scoreColor(sc));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72));
        rlp.setMarginStart(Ui.dp(this, 16));
        data.addView(ring, rlp);
        row.addView(data);
        row.addView(Ui.divider(this));
        return row;
    }

    // ---- Attainment ------------------------------------------------------------------------
    private View attainmentPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(pageTitle(L.t("Highest achievements")));
        Stats.Records r = Stats.records(trips);
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", L.dateLocale());

        String[][] tiles = new String[12][3];
        tiles[0] = tile(L.t("Longest trip"), r.longest == null ? "--" : L.f("%s km", Ui.f(r.longest.distanceKm, "%.1f")), r.longest, df);
        tiles[1] = tile(L.t("Longest drive"), r.longestTime == null ? "--" : Ui.duration(r.longestTime.durationSec()), r.longestTime, df);
        tiles[2] = tile(L.t("Top speed"), r.fastest == null ? "--" : L.f("%s km/h", Ui.f(r.fastest.maxSpeedKmh, "%.0f")), r.fastest, df);
        tiles[3] = tile(L.t("Most efficient (5 km+)"), r.mostEfficient == null ? "--" : L.f("%s /100km", Ui.f(r.mostEfficient.energyPer100Km(), "%.1f")), r.mostEfficient, df);
        tiles[4] = tile(L.t("Best drive score"), r.bestScore == null ? "--" : Ui.f(Stats.score(r.bestScore), "%.0f"), r.bestScore, df);
        double evSum = 0, hevSum = 0, fuelSum = 0, costSum = 0;
        int fuelTrips = 0;
        boolean anyCostTile = false;
        for (Trip t : trips) {
            if (!Double.isNaN(t.evKm()) && !Double.isNaN(t.hevKm())) {
                evSum += t.evKm();
                hevSum += t.hevKm();
            }
            if (!Double.isNaN(t.fuelUsed())) {
                fuelSum += t.fuelUsed();
                fuelTrips++;
            }
            double c = Cost.total(t, this);
            if (!Double.isNaN(c)) {
                costSum += c;
                anyCostTile = true;
            }
        }
        tiles[5] = new String[]{L.t("Total distance"), L.f("%s km", Ui.f(r.totalKm, "%.0f")),
                evSum + hevSum >= 1 ? L.f("%s%% driven electric", Ui.f(evSum / (evSum + hevSum) * 100, "%.0f")) : ""};
        tiles[6] = new String[]{L.t("Total trips"), String.valueOf(r.count), ""};
        tiles[7] = new String[]{L.t("Total drive time"), Ui.duration(r.totalSec), ""};
        tiles[8] = new String[]{L.t("Total cost"), anyCostTile ? Cost.money(this, costSum) : "--",
                L.t("electricity + fuel")};
        tiles[9] = new String[]{L.t("Fuel used"), fuelTrips > 0 ? L.f("%s L", Ui.f(fuelSum, "%.1f")) : "--",
                fuelTrips > 0 ? L.f("over %d trip(s)", fuelTrips)
                        : L.t("recorded for new trips")};
        Stats.Co2 co2 = Stats.co2Avoided(trips);
        tiles[10] = new String[]{L.t("CO2 avoided (estimate)"), co2.trips > 0 ? L.f("%s kg", Ui.f(co2.kg, "%.0f")) : "--",
                co2.trips > 0 ? L.f("about %s trees a year", Ui.f(co2.trees, "%.1f")) : L.t("needs fuel data")};
        int lv = Stats.level(r.totalKm);
        String next = lv + 1 < Stats.LEVEL_KM.length
                ? L.f("Level (next: %s km)", Ui.f(Stats.LEVEL_KM[lv + 1], "%.0f")) : L.t("Level (top)");
        tiles[11] = new String[]{next, L.f("Lv %d", lv + 1), Stats.levelName(lv)};

        for (int rr = 0; rr < 3; rr++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 4; c++) {
                String[] tl = tiles[rr * 4 + c];
                LinearLayout cell = card();
                cell.setPaddingRelative(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 12));
                cell.addView(Ui.text(this, tl[0], 16, Ui.TEXT_DATE));
                TextView v = Ui.text(this, tl[1], 32, Ui.TEXT_SELECTED);
                v.setPadding(0, Ui.dp(this, 8), 0, 0);
                cell.addView(v);
                TextView s = Ui.text(this, tl[2], 14, Ui.TEXT_LABEL);
                s.setPadding(0, Ui.dp(this, 6), 0, 0);
                cell.addView(s);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.setMarginStart(c == 0 ? 0 : Ui.dp(this, 16));
                line.addView(cell, lp);
            }
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 0, 1f);
            llp.bottomMargin = Ui.dp(this, 12);
            col.addView(line, llp);
        }
        return col;
    }

    private static String[] tile(String title, String value, Trip t, SimpleDateFormat df) {
        return new String[]{title, value, t == null ? "" : df.format(new Date(t.startMs))};
    }

    // ---- Statistics ------------------------------------------------------------------------
    private View statisticsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle(L.t("Statistics")));
        View spacer = new View(this);
        head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        String[] metricNames = {L.t("Mileage"), L.t("Consumption"), L.t("Drive score"), L.t("Trips"), L.t("Cost"), L.t("Fuel")};
        for (int i = 0; i < metricNames.length; i++) {
            final int m = i;
            head.addView(chip(metricNames[i], statsMetric == i, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    statsMetric = m;
                    renderCurrentTab();
                }
            }));
        }
        View gap = new View(this);
        head.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 24), 1));
        head.addView(chip(L.t("Daily"), !statsMonthly, new View.OnClickListener() {
            @Override public void onClick(View v) {
                statsMonthly = false;
                renderCurrentTab();
            }
        }));
        head.addView(chip(L.t("Monthly"), statsMonthly, new View.OnClickListener() {
            @Override public void onClick(View v) {
                statsMonthly = true;
                renderCurrentTab();
            }
        }));
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout box = card();
        box.setPadding(Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 16));
        String[] units = {L.t("Mileage (km)"), L.t("Consumption (kWh/100km)"), L.t("Average drive score"), L.t("Number of trips"),
                L.f("Cost (%s)", Prefs.currency(this)),
                L.t("Fuel used (L)")};
        box.addView(Ui.text(this, units[statsMetric] + (statsMonthly ? L.t(" - last 12 months") : L.t(" - last 14 days")),
                18, Ui.TEXT_DATE));
        Stats.Series s = Stats.series(this, trips, statsMonthly, statsMetric);
        BarChartView chart = new BarChartView(this);
        int[] colors = {Ui.GREEN, Ui.BLUE, Ui.AMBER, 0xFFB48CFF, 0xFFFF8A65, 0xFF4DD0E1};
        chart.setDecimals(statsMetric == Stats.COST ? Cost.decimalsFor(Prefs.currency(this)) : 1);
        chart.set(s.values, s.labels, colors[statsMetric]);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = Ui.dp(this, 8);
        box.addView(chart, clp);
        col.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        return col;
    }

    private TextView chip(String text, boolean selected, View.OnClickListener l) {
        TextView t = Ui.text(this, text, 18, selected ? Ui.TEXT_SELECTED : Ui.TEXT_DATE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        t.setBackground(Ui.round(selected ? 0x33FFFFFF : 0x00000000, 18, this));
        t.setOnClickListener(l);
        return t;
    }

    // ---- trip detail -----------------------------------------------------------------------
    private void showDetail(final Trip t) {
        saveTripsScroll();
        final TextView overView = Ui.text(this, "...", 17, Ui.TEXT_VALUE);
        overlay = "detail";
        pageRefresh = null;
        setChrome(true);
        titleView.setText(L.t("Trip details"));
        content.removeAllViews();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout mapCard = card();
        final RouteView route = new RouteView(this);
        mapCard.addView(route, new LinearLayout.LayoutParams(-1, -1));
        leftCol.addView(mapCard, new LinearLayout.LayoutParams(-1, 0, 8f));
        final TripChartsPanel charts = new TripChartsPanel(this, route);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(-1, 0, 5f);
        chLp.topMargin = Ui.dp(this, 14);
        leftCol.addView(charts, chLp);
        row.addView(leftCol, new LinearLayout.LayoutParams(0, -1, 1f));
        new Thread(new Runnable() {
            @Override public void run() {
                final List<double[]> pts = TripAnalysis.holdStill(TripDb.get(MainActivity.this).points(t.id));
                final List<double[]> events = TripDb.get(MainActivity.this).events(t.id);
                ui.post(new Runnable() {
                    @Override public void run() {
                        route.set(pts, events);
                        charts.setData(t, pts);
                        double over = TripAnalysis.secondsAbove(pts, 100);
                        overView.setText(pts.size() < 2 ? "--" : Ui.duration((long) over));
                    }
                });
            }
        }).start();

        LinearLayout info = card();
        info.setPadding(Ui.dp(this, 28), Ui.dp(this, 22), Ui.dp(this, 28), Ui.dp(this, 18));
        SimpleDateFormat df = new SimpleDateFormat("EEEE" + L.sep() + "d MMMM yyyy", L.dateLocale());
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        info.addView(Ui.text(this, df.format(new Date(t.startMs)), 20, Ui.TEXT_VALUE));
        TextView range = Ui.text(this, tf.format(new Date(t.startMs)) + " - "
                + (t.endMs > 0 ? tf.format(new Date(t.endMs)) : "..."), 16, Ui.TEXT_LABEL);
        range.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        info.addView(range);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        double sc = Stats.score(t);
        RingView ring = new RingView(this, 8, 30, 14);
        ring.set(Double.isNaN(sc) ? 0 : sc, 100, Double.isNaN(sc) ? "--" : String.format(Locale.US, "%.0f", sc),
                L.t("score"), Ui.scoreColor(sc));
        top.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 110)));
        LinearLayout big = new LinearLayout(this);
        big.setOrientation(LinearLayout.VERTICAL);
        big.setPaddingRelative(Ui.dp(this, 22), 0, 0, 0);
        big.addView(Ui.text(this, L.f("%s km", Ui.f(t.distanceKm, "%.1f")), 34, Ui.TEXT_SELECTED));
        TextView dur = Ui.text(this, Ui.duration(t.durationSec()), 20, Ui.TEXT_DATE);
        dur.setPadding(0, Ui.dp(this, 6), 0, 0);
        big.addView(dur);
        top.addView(big);
        info.addView(top);

        List<String[]> lines = new ArrayList<>();
        lines.add(new String[]{L.t("Average speed"), L.f("%s km/h", Ui.f(t.avgSpeedKmh(), "%.0f"))});
        lines.add(new String[]{L.t("Max speed"), L.f("%s km/h", Ui.f(t.maxSpeedKmh, "%.0f"))});
        lines.add(new String[]{L.t("Battery"), Ui.f(t.socStart, "%.0f") + "% -> " + Ui.f(t.socEnd, "%.0f") + "%"});
        lines.add(new String[]{L.t("Energy used"), L.f("%s kWh", Ui.f(t.energyUsed(), "%.2f"))});
        lines.add(new String[]{L.t("Consumption"), L.f("%s kWh/100km", Ui.f(t.energyPer100Km(), "%.1f"))});
        if (!Double.isNaN(t.fuelUsed())) {
            lines.add(new String[]{L.t("Fuel used"), L.f("%s L", Ui.f(t.fuelUsed(), "%.1f"))
                    + (Double.isNaN(t.fuelPer100Km()) ? "" : "  (" + L.f("%s L/100km", Ui.f(t.fuelPer100Km(), "%.1f")) + ")")});
        }
        if (!Double.isNaN(t.evKm()) && !Double.isNaN(t.hevKm())) {
            lines.add(new String[]{L.t("Electric / hybrid distance"),
                    L.f("%1$s km / %2$s km", Ui.f(t.evKm(), "%.0f"), Ui.f(t.hevKm(), "%.0f"))});
        }
        lines.add(new String[]{L.t("Odometer change"), L.f("%s km", Ui.f(t.odometerDeltaKm(), "%.1f"))});
        lines.add(new String[]{L.t("Distance measured by"),
                !Double.isNaN(t.journeyAcc) && t.journeyAcc >= JourneyTracker.TRUST_KM
                        ? L.t("car trip counter") : L.t("speed")});
        lines.add(new String[]{L.t("Hard accel / braking"), t.hardAccel + " / " + t.hardBrake});
        lines.add(new String[]{L.t("Idle time"), Ui.duration((long) t.idleSec)});
        lines.add(new String[]{L.t("Time above 100 km/h"), null});
        if (Cost.enabled(this)) {
            if (!Double.isNaN(Cost.elec(t, this))) {
                lines.add(new String[]{L.t("Electricity cost"), Cost.money(this, Cost.elec(t, this))});
            }
            if (!Double.isNaN(Cost.fuel(t, this))) {
                lines.add(new String[]{L.t("Fuel cost"), Cost.money(this, Cost.fuel(t, this))});
            }
            lines.add(new String[]{L.t("Trip cost"), Cost.money(this, Cost.total(t, this))
                    + (Double.isNaN(t.fuelUsed()) ? L.t("  (fuel not recorded)") : "")});
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 14), 0, 0);
        for (String[] l : lines) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            line.addView(Ui.text(this, l[0], 17, Ui.TEXT_LABEL), new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(l[1] == null ? overView : Ui.text(this, l[1], 17, Ui.TEXT_VALUE));
            list.addView(line);
            list.addView(Ui.divider(this));
        }
        List<String> advice = Advice.forTrip(t);
        if (!advice.isEmpty()) {
            TextView head = Ui.text(this, L.t("Driving advice"), 17, Ui.TEXT_SELECTED);
            head.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
            list.addView(head);
            for (String a : advice) {
                TextView at = Ui.text(this, a, 15, Ui.TEXT_DATE);
                at.setLineSpacing(Ui.dp(this, 2), 1f);
                at.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
                list.addView(at);
            }
        }
        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        info.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, Ui.dp(this, 10), 0, 0);
        buttons.addView(pill(L.t("Export GPX"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                exportGpx(t);
            }
        }));
        View g = new View(this);
        buttons.addView(g, new LinearLayout.LayoutParams(Ui.dp(this, 14), 1));
        buttons.addView(pill(L.t("Delete"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDelete(t);
            }
        }));
        info.addView(buttons);

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 460), -1);
        ilp.setMarginStart(Ui.dp(this, 24));
        row.addView(info, ilp);
        content.addView(row);
    }

    // ---- Tweaks ----------------------------------------------------------------------------
    private View tweaksPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = pageTitle(L.t("Tweaks"));
        title.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 12));
        col.addView(title);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = card();
        box.setPadding(Ui.dp(this, 28), Ui.dp(this, 8), Ui.dp(this, 28), Ui.dp(this, 8));
        sv.addView(box, new ViewGroup.LayoutParams(-1, -2));

        final View stateRow = settingRow(L.t("Data roaming"),
                Tweaks.roamingText(this), L.t("Turn on now"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Tweaks.applyRoaming(MainActivity.this, "button");
                        toast(L.t("Turning data roaming on..."));
                    }
                });
        box.addView(stateRow);
        final TextView stateText = (TextView) stateRow.getTag();

        boolean keep = Prefs.keepRoaming(this);
        final View keepRow = settingRow(L.t("Keep data roaming on"),
                keep ? L.t("On: applied at start-up, whenever the data link helper starts, and again within a "
                        + "minute if the car switches it off")
                        : L.t("Off: roaming is only turned on when you press the button above"),
                keep ? L.t("Turn off") : L.t("Turn on"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean on = !Prefs.keepRoaming(MainActivity.this);
                        Prefs.setKeepRoaming(MainActivity.this, on);
                        if (on) Tweaks.ensureRoaming(MainActivity.this, true, "switched on");
                        renderCurrentTab();
                    }
                });
        box.addView(keepRow);
        final TextView netText = Ui.text(this, NetStatus.line(this), 14, Ui.TEXT_LABEL);
        netText.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(netText);

        final View cloudRow = settingRow(L.t("Connect to BYD cloud"), Tweaks.cloudText(), L.t("Connect now"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Tweaks.connectCloud(MainActivity.this);
                        toast(L.t("Asking the car to connect..."));
                    }
                });
        box.addView(cloudRow);
        final TextView cloudText = (TextView) cloudRow.getTag();

        TextView note = Ui.text(this, L.t("Needs the Vehicle data link (Setting): the changes are made through the car's "
                + "network debugging. Commands: ") + Tweaks.ROAMING_COMMAND + "  |  " + Tweaks.CLOUD_COMMAND, 14,
                Ui.TEXT_LABEL);
        note.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        box.addView(note);

        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        pageRefresh = new Runnable() {
            @Override public void run() {
                stateText.setText(Tweaks.roamingText(MainActivity.this));
                netText.setText(NetStatus.line(MainActivity.this));
                cloudText.setText(Tweaks.cloudText());
            }
        };
        return col;
    }

    // ---- Charging --------------------------------------------------------------------------
    private View chargingPage() {
        final double kwhPerPct = Charge.kwhPerPercent(trips);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        double kwh = 0;
        boolean anyKwh = false;
        for (Charge c : charges) {
            double e = c.energyKwh(kwhPerPct);
            if (!Double.isNaN(e)) {
                kwh += e;
                anyKwh = true;
            }
        }
        TextView title = pageTitle(L.t("Charging"));
        title.setPaddingRelative(Ui.dp(this, 4), 0, 0, 0);
        head.addView(title);
        double chargeCost = 0;
        boolean anyChargeCost = false;
        for (Charge c : charges) {
            double cc = Cost.charge(c, kwhPerPct, this);
            if (!Double.isNaN(cc)) {
                chargeCost += cc;
                anyChargeCost = true;
            }
        }
        head.addView(Ui.text(this, "   " + L.f("%d session(s)", charges.size())
                + (anyKwh ? L.sep() + L.f("about %s kWh added", Ui.f(kwh, "%.0f")) : "")
                + (anyChargeCost ? L.sep() + L.f("about %s", Cost.money(this, chargeCost)) : ""), 16, Ui.TEXT_LABEL),
                new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) head.getLayoutParams()).bottomMargin = Ui.dp(this, 12);

        LinearLayout box = card();
        col.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (charges.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(this);
            img.setImageResource(res("empty_trips"));
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 312), Ui.dp(this, 208));
            imgLp.gravity = Gravity.CENTER_HORIZONTAL;
            empty.addView(img, imgLp);
            TextView t = Ui.text(this, L.t("No charging sessions yet"), 20, Ui.TEXT_LABEL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 12), 0, 0);
            empty.addView(t, new LinearLayout.LayoutParams(-1, -2));
            TextView hint = Ui.text(this, Prefs.linkEnabled(this)
                    ? L.t("A session is recorded automatically while the car charges")
                    : L.t("Turn on Vehicle data link in Setting: charging is read from the car through it"),
                    16, Ui.TEXT_LABEL);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0);
            empty.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            box.addView(empty, new LinearLayout.LayoutParams(-1, -1));
            return col;
        }

        final List<Object> items = new ArrayList<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String last = "";
        for (Charge c : charges) {
            String d = day.format(new Date(c.startMs));
            if (!d.equals(last)) {
                items.add(d);
                last = d;
            }
            items.add(c);
        }
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 8));
        list.setClipToPadding(false);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return items.get(i) instanceof String ? 0 : 1; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                Object o = items.get(i);
                if (o instanceof String) {
                    TextView t = Ui.text(MainActivity.this, (String) o, 20, Ui.TEXT_DATE);
                    t.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 16),
                            0, Ui.dp(MainActivity.this, 4));
                    return t;
                }
                return chargeRow((Charge) o, kwhPerPct);
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Object o = items.get(pos);
                if (o instanceof Charge) showChargeDetail((Charge) o, kwhPerPct);
            }
        });
        box.addView(list, new LinearLayout.LayoutParams(-1, -1));
        return col;
    }

    private static String kwhText(Charge c, double kwhPerPct) {
        double e = c.energyKwh(kwhPerPct);
        return Double.isNaN(e) ? "--" : L.ltr((c.energyIsEstimate() ? "~" : "") + Ui.f(e, "%.1f"));
    }

    private View chargeRow(Charge c, double kwhPerPct) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), 0);

        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.addView(Ui.text(this, tf.format(new Date(c.startMs)) + "-"
                + (c.ended ? tf.format(new Date(c.endMs)) : "..."), 16, Ui.TEXT_LABEL));
        if (!c.ended) {
            TextView tag = Ui.text(this, L.t("Charging"), 11, Ui.GREEN);
            tag.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
            tag.setBackground(Ui.stroke(Ui.GREEN, 0, 9, this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(Ui.dp(this, 12));
            timeRow.addView(tag, lp);
        }
        if (c.partial) {
            TextView tag = Ui.text(this, L.t("Partial"), 11, Ui.AMBER);
            tag.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
            tag.setBackground(Ui.stroke(Ui.AMBER, 0, 9, this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(Ui.dp(this, 12));
            timeRow.addView(tag, lp);
        }
        row.addView(timeRow);

        LinearLayout data = new LinearLayout(this);
        data.setOrientation(LinearLayout.HORIZONTAL);
        data.setGravity(Gravity.CENTER_VERTICAL);
        data.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14));
        double gained = c.socGained();
        data.addView(Ui.stat(this, Double.isNaN(gained) ? "--" : L.ltr((gained >= 0 ? "+" : "") + Ui.f(gained, "%.0f")),
                L.t("Battery gained (%)"), 20, 14), new LinearLayout.LayoutParams(0, -2, 1f));
        data.addView(Ui.stat(this, Ui.f(c.socStart, "%.0f") + " > " + Ui.f(c.socEnd, "%.0f"),
                L.t("Battery (%)"), 20, 14), new LinearLayout.LayoutParams(0, -2, 1f));
        data.addView(Ui.stat(this, kwhText(c, kwhPerPct), L.t("Energy added (kWh)"), 20, 14),
                new LinearLayout.LayoutParams(0, -2, 1f));
        data.addView(Ui.stat(this, Ui.duration(c.durationSec()), L.t("Duration"), 20, 14),
                new LinearLayout.LayoutParams(0, -2, 1f));
        if (Cost.enabled(this)) {
            data.addView(Ui.stat(this, Cost.amount(this, Cost.charge(c, kwhPerPct, this)),
                    L.f("Cost (%s)", Prefs.currency(this)), 20, 14), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        data.addView(Ui.stat(this, c.typeName().isEmpty() ? "--" : c.typeName(), L.t("Type"), 20, 14),
                new LinearLayout.LayoutParams(0, -2, 0.7f));
        row.addView(data);
        row.addView(Ui.divider(this));
        return row;
    }

    private void showChargeDetail(final Charge c, double kwhPerPct) {
        overlay = "detail";
        pageRefresh = null;
        setChrome(true);
        titleView.setText(L.t("Charging details"));
        content.removeAllViews();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout chartCard = card();
        chartCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 12));
        final TextView readout = Ui.text(this, L.t("Battery (%) while charging - touch the chart to inspect"),
                16, Ui.TEXT_LABEL);
        chartCard.addView(readout);
        final TripChartView chart = new TripChartView(this);
        chart.setGapMs(Double.MAX_VALUE); // a long gap is the unit asleep: join the readings with a straight line
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = Ui.dp(this, 10);
        chartCard.addView(chart, clp);
        row.addView(chartCard, new LinearLayout.LayoutParams(0, -1, 1f));
        new Thread(new Runnable() {
            @Override public void run() {
                final List<double[]> pts = TripDb.get(MainActivity.this).chargePoints(c.id);
                final double[] times = new double[pts.size()];
                final double[] soc = new double[pts.size()];
                for (int i = 0; i < times.length; i++) {
                    times[i] = pts.get(i)[0];
                    soc[i] = pts.get(i)[1];
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        chart.set(times, soc, "%", Ui.GREEN, L.t("Not enough samples to draw a chart"));
                        chart.setListener(new TripChartView.Listener() {
                            @Override public void onCursor(int index, double timeMs) {
                                if (index < 0 || index >= pts.size() || Double.isNaN(timeMs)) {
                                    readout.setText(L.t("Battery (%) while charging - touch the chart to inspect"));
                                    return;
                                }
                                double[] p = pts.get(index);
                                String t = new SimpleDateFormat("HH:mm", L.dateLocale()).format(new Date((long) timeMs));
                                readout.setText(t + "   " + Ui.f(TripAnalysis.valueAt(times, soc, timeMs), "%.0f") + "%"
                                        + (Double.isNaN(p[2]) || p[2] <= 0 ? "" : "   " + L.f("%s kW", Ui.f(p[2], "%.1f"))));
                            }
                        });
                    }
                });
            }
        }).start();

        LinearLayout info = card();
        info.setPadding(Ui.dp(this, 28), Ui.dp(this, 22), Ui.dp(this, 28), Ui.dp(this, 18));
        SimpleDateFormat df = new SimpleDateFormat("EEEE" + L.sep() + "d MMMM yyyy", L.dateLocale());
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        info.addView(Ui.text(this, df.format(new Date(c.startMs)), 20, Ui.TEXT_VALUE));
        TextView range = Ui.text(this, tf.format(new Date(c.startMs)) + " - "
                + (c.ended ? tf.format(new Date(c.endMs)) : L.t("charging now")), 16, Ui.TEXT_LABEL);
        range.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        info.addView(range);
        double gained = c.socGained();
        info.addView(Ui.text(this, Double.isNaN(gained) ? "--" : L.ltr((gained >= 0 ? "+" : "") + Ui.f(gained, "%.0f") + "%"),
                34, Ui.TEXT_SELECTED));
        TextView dur = Ui.text(this, Ui.duration(c.durationSec()), 20, Ui.TEXT_DATE);
        dur.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        info.addView(dur);

        double e = c.energyKwh(kwhPerPct);
        double hours = c.durationSec() / 3600.0;
        String[][] lines = {
                {L.t("Battery"), Ui.f(c.socStart, "%.0f") + "% -> " + Ui.f(c.socEnd, "%.0f") + "%"},
                {c.energyIsEstimate() ? L.t("Energy added (estimate)") : L.t("Energy added"),
                        Double.isNaN(e) ? "--" : L.f("%s kWh", Ui.f(e, "%.1f"))},
                {L.t("Average power"), Double.isNaN(e) || hours < 0.02 || c.partial ? "--"
                        : L.f("%s kW", Ui.f(e / hours, "%.1f"))},
                {L.t("Highest power reported"), Double.isNaN(c.maxPowerKw) ? L.t("not reported by the car")
                        : L.f("%s kW", Ui.f(c.maxPowerKw, "%.1f"))},
                {L.t("Charger"), c.typeName().isEmpty() ? "--" : c.typeName()},
                {L.t("Cost"), Cost.money(this, Cost.charge(c, kwhPerPct, this))}};
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (String[] l : lines) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            line.addView(Ui.text(this, l[0], 17, Ui.TEXT_LABEL), new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(Ui.text(this, l[1], 17, Ui.TEXT_VALUE));
            list.addView(line);
            list.addView(Ui.divider(this));
        }
        if (c.partial) {
            TextView note = Ui.text(this, c.windowOnly
                    ? L.t("Partial: this charge happened while the head unit was asleep. Only the battery "
                    + "level before and after is known, so the times shown are the window in which it "
                    + "happened, not how long it charged.")
                    : L.t("Partial: the app missed part of this session (the head unit "
                    + "was asleep or the app was not running), so the battery gain and energy can be "
                    + "lower than the real total."), 15, Ui.AMBER);
            note.setLineSpacing(Ui.dp(this, 2), 1f);
            note.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
            list.addView(note);
        }
        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        info.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, Ui.dp(this, 10), 0, 0);
        if (!Double.isNaN(c.lat) && !Double.isNaN(c.lon)) {
            buttons.addView(pill(L.t("Show place"), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    MapLink.openPlace(MainActivity.this, c.lat, c.lon);
                }
            }));
            buttons.addView(new View(this), new LinearLayout.LayoutParams(Ui.dp(this, 14), 1));
        }
        buttons.addView(pill(L.t("Delete"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.show(new AlertDialog.Builder(MainActivity.this)
                        .setMessage(L.t("Delete this charging session permanently?"))
                        .setPositiveButton(L.t("Delete"), new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                TripDb.get(MainActivity.this).deleteCharge(c.id);
                                closeOverlay();
                                loadTrips(null);
                            }
                        })
                        .setNegativeButton(L.t("Cancel"), null));
            }
        }));
        info.addView(buttons);

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 460), -1);
        ilp.setMarginStart(Ui.dp(this, 24));
        row.addView(info, ilp);
        content.addView(row);
    }

    // ---- settings and Q&A ------------------------------------------------------------------
    private View settingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = card();
        box.setPadding(Ui.dp(this, 28), Ui.dp(this, 8), Ui.dp(this, 28), Ui.dp(this, 8));
        sv.addView(box, new ViewGroup.LayoutParams(-1, -2));

        box.addView(settingRow(L.t("Language"), languageName(), L.t("Change"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showLanguageDialog();
            }
        }));
        final View updatesRow = settingRow(L.t("Updates"), updateText(), L.t("Check for updates"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        checkForUpdates();
                    }
                });
        updatesValue = (TextView) updatesRow.getTag();
        box.addView(updatesRow);
        final RecorderService.Live l = RecorderService.live;
        box.addView(settingRow(L.t("Trip recording service"), l.running ? L.t("Running") : L.t("Stopped"),
                l.running ? L.t("Stop") : L.t("Start"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (RecorderService.live.running) {
                            stopService(new Intent(MainActivity.this, RecorderService.class));
                        } else {
                            startRecorder();
                        }
                        ui.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (overlay != null) showOverlay("settings");
                            }
                        }, 800);
                    }
                }));
        box.addView(settingRow(L.t("Vehicle data source"), vehicleName(l.vehicle), L.t("Self-test"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                selfTest();
            }
        }));
        boolean linkOn = Prefs.linkEnabled(this);
        final View linkRow = settingRow(L.t("Vehicle data link"), HelperLauncher.statusText(this),
                linkOn ? L.t("Disable") : L.t("Enable"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (Prefs.linkEnabled(MainActivity.this)) {
                            HelperLauncher.disable(MainActivity.this);
                            toast(L.t("Vehicle data link turned off"));
                            refreshSettingsSoon();
                        } else if (Prefs.linkConsented(MainActivity.this)) {
                            HelperLauncher.enable(MainActivity.this);
                            toast(L.t("Connecting to the car's data service..."));
                            refreshSettingsSoon();
                        } else {
                            confirmEnableLink();
                        }
                    }
                });
        box.addView(linkRow);
        final TextView linkStatus = (TextView) linkRow.getTag();
        if (linkOn) {
            box.addView(settingRow(L.t("Reconnect"), L.t("Try to connect to the car's data right now"),
                    L.t("Retry now"), new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            HelperLauncher.ensureAsync(MainActivity.this, true);
                            toast(L.t("Reconnecting..."));
                            refreshSettingsSoon();
                        }
                    }));
        }
        box.addView(settingRow(L.t("Debugging key"), L.t("This app's key for the car's network debugging"),
                L.t("Forget"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        HelperLauncher.disable(MainActivity.this);
                        HelperLauncher.forgetKey(MainActivity.this);
                        toast(L.t("Key removed and link turned off"));
                        refreshSettingsSoon();
                    }
                }));
        pageRefresh = new Runnable() {
            @Override public void run() {
                linkStatus.setText(HelperLauncher.statusText(MainActivity.this));
            }
        };
        boolean loc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        box.addView(settingRow(L.t("Location (GPS track)"), loc ? L.t("Allowed") : L.t("Not allowed"), L.t("Allow"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        requestPerms(true);
                    }
                }));
        int health = Prefs.autoStartHealth(this);
        String autoText = health == Prefs.AUTOSTART_OK
                ? L.t("Started automatically after the last restart")
                : health == Prefs.AUTOSTART_BLOCKED
                ? L.t("NOT started automatically after the last restart: turn Trip Recorder NG OFF in "
                + "'Disable background Apps'")
                : L.t("Not confirmed yet: turn Trip Recorder NG OFF in 'Disable background Apps', then "
                + "restart the head unit once");
        box.addView(settingRow(L.t("BYD auto-start apps"), autoText, L.t("Open"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openAutostart();
                    }
                }));
        box.addView(settingRow(L.t("Battery optimisation"), L.t("Keep the recorder running"), L.t("Open"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openBattery();
                    }
                }));
        box.addView(settingRow(L.t("Energy prices"),
                L.f("Electricity %1$s %2$s/kWh%3$s, fuel %4$s %2$s/L%5$s", Ui.f(Cost.elecPrice(this), "%.4f"),
                        Prefs.currency(this), Cost.elecIsDefault(this) ? L.t(" (default)") : "",
                        Ui.f(Cost.fuelPrice(this), "%.3f"), Cost.fuelIsDefault(this) ? L.t(" (default)") : ""),
                L.t("Set"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showPriceDialog();
                    }
                }));
        boolean routeOn = Prefs.recordRoute(this);
        box.addView(settingRow(L.t("Record route (GPS track)"),
                routeOn ? L.t("On: trips keep their route on the map")
                        : L.t("Off: new trips store no route and no event positions"),
                routeOn ? L.t("Turn off") : L.t("Turn on"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.setRecordRoute(MainActivity.this, !Prefs.recordRoute(MainActivity.this));
                        refreshSettingsSoon();
                    }
                }));
        final Backup.Status bs = Backup.status(this);
        boolean autoBk = Prefs.autoBackup(this);
        box.addView(settingRow(L.t("Automatic backup"),
                (autoBk ? L.t("On: every finished trip is also saved to ") : L.t("Off. Backup folder: "))
                        + L.ltr(bs.dir.getPath()) + "\n" + L.f("%d trip file(s)", bs.files)
                        + (bs.newestMs > 0 ? L.sep() + L.f("newest %s",
                        new SimpleDateFormat("d MMM HH:mm", L.dateLocale()).format(new Date(bs.newestMs)))
                        : "")
                        + (bs.publicFolder ? "" : "\n" + L.t("Warning: Documents is not writable, so this copy is "
                        + "deleted if the app is uninstalled")),
                autoBk ? L.t("Turn off") : L.t("Turn on"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean on = !Prefs.autoBackup(MainActivity.this);
                        Prefs.setAutoBackup(MainActivity.this, on);
                        if (on) backupNow(false);
                        toast(on ? L.t("Automatic backup turned on") : L.t("Automatic backup turned off"));
                        refreshSettingsSoon();
                    }
                }));
        box.addView(settingRow(L.t("Back up now"), L.t("Saves any finished trip that has no backup file yet"),
                L.t("Back up"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        backupNow(true);
                    }
                }));
        box.addView(settingRow(L.t("Restore from backup"),
                L.t("Adds the trips from the backup folder that are missing here (nothing is overwritten)"),
                L.t("Restore"), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        confirmRestore();
                    }
                }));
        box.addView(settingRow(L.t("Export all trips"), "Documents/TripRecorderNG/trips.csv", L.t("Export"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        exportCsv();
                    }
                }));
        final View channelRow = settingRow(L.t("Update channel"), channelDescription(), L.t("Change"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showChannelDialog();
                    }
                });
        channelValue = (TextView) channelRow.getTag();
        channelRow.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
        final View advancedRow = settingRow(L.t("Advanced"), L.t("Options for experts: which builds the update check follows"),
                advancedOpen ? L.t("Hide") : L.t("Show"), null);
        final TextView advancedButton = (TextView) ((ViewGroup) ((ViewGroup) advancedRow).getChildAt(0)).getChildAt(1);
        advancedButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                advancedOpen = !advancedOpen;
                channelRow.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
                advancedButton.setText(advancedOpen ? L.t("Hide") : L.t("Show"));
            }
        });
        box.addView(advancedRow);
        box.addView(channelRow);
        File lf = Diag.file();
        TextView log = Ui.text(this, L.f("Log file: %s", lf == null ? "?" : L.ltr(lf.getPath())), 14, Ui.TEXT_LABEL);
        log.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));
        box.addView(log);
        return sv;
    }

    /** The language choice as shown in the Language row (the language names are always in their own language). */
    private String languageName() {
        String v = L.setting(this);
        if (L.AR.equals(v)) return "العربية";
        if (L.EN.equals(v)) return "English";
        return L.t("Automatic (follows the head unit)") + " - " + (L.isArabic() ? "العربية" : "English");
    }

    private void showLanguageDialog() {
        final String[] values = {L.AUTO, L.EN, L.AR};
        String[] labels = {L.t("Automatic (follows the head unit)"), (L.isArabic() ? "\u200F" : "") + "English", (L.isArabic() ? "" : "\u200E") + "العربية"};
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.t("Language"))
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        L.setSetting(MainActivity.this, values[which]);
                        recreate(); // every screen is rebuilt in the new language
                    }
                })
                .setNegativeButton(L.t("Cancel"), null));
    }

    // ---- updates (manual only) ----------------------------------------------------------------

    private String updateText() {
        return L.f("Installed: %1$s%2$s   -   following: %3$s", Updates.installedName(this),
                Updates.installed(this) == null ? " " + L.t("(not a release build)") : "",
                Updates.channelTitle(Updates.channel(this)));
    }

    private String channelDescription() {
        return Updates.DEV.equals(Updates.channel(this))
                ? L.t("Dev: the newest builds, made for testing, may contain bugs")
                : L.t("Stable: tested builds (recommended)");
    }

    private void showChannelDialog() {
        final String[] values = {Updates.STABLE, Updates.DEV};
        String[] labels = {L.t("Stable - tested builds (recommended)"), L.t("Dev - the newest builds, for testing")};
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.t("Update channel"))
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        Prefs.setUpdateChannel(MainActivity.this, values[which]);
                        if (updatesValue != null) updatesValue.setText(updateText());
                        if (channelValue != null) channelValue.setText(channelDescription());
                        checkForUpdates(); // offers the newest build of that channel, which may mean switching
                    }
                })
                .setNegativeButton(L.t("Cancel"), null));
    }

    private void showInfo(String message) {
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.t("Updates"))
                .setMessage(message)
                .setPositiveButton(L.t("OK"), null));
    }

    private void checkForUpdates() {
        if (updateBusy) return;
        updateBusy = true;
        final String channel = Updates.channel(this);
        toast(L.t("Checking for updates..."));
        new Thread(new Runnable() {
            @Override public void run() {
                final Updates.Check c = Updates.check(MainActivity.this, channel);
                ui.post(new Runnable() {
                    @Override public void run() {
                        updateBusy = false;
                        if (!isFinishing() && !isDestroyed()) showUpdateResult(channel, c);
                    }
                });
            }
        }).start();
    }

    private void showUpdateResult(String channel, Updates.Check c) {
        String chName = Updates.channelTitle(channel);
        if (c.error != null) {
            showInfo(L.f("Could not check for updates: %s", c.error));
            return;
        }
        if (c.latest == null) {
            showInfo(L.f("No %s build has been published yet.", chName));
            return;
        }
        final Updates.Release r = c.latest;
        Updates.Build mine = Updates.installed(this);
        int cmp = Updates.compare(mine, r);
        if (cmp == 0) {
            showInfo(L.f("You have the latest %1$s build: %2$s.", chName, r.build.name()));
            return;
        }
        String when = r.publishedMs > 0
                ? " (" + new SimpleDateFormat("d MMM yyyy", L.dateLocale()).format(new Date(r.publishedMs)) + ")" : "";
        StringBuilder msg = new StringBuilder();
        if (cmp > 0) {
            msg.append(L.f("A newer %1$s build is available: %2$s%3$s.", chName, r.build.name(), when));
        } else {
            msg.append(L.f("The latest %1$s build is %2$s%3$s, which is older than the installed build %4$s. "
                    + "Install it anyway?", chName, r.build.name(), when, Updates.installedName(this)));
        }
        msg.append("\n").append(L.f("Installed: %s", Updates.installedName(this)));
        if (mine != null && !mine.channel.equals(channel)) {
            msg.append("\n\n").append(L.f("This switches the app from %1$s to %2$s.",
                    Updates.channelTitle(mine.channel), chName));
        }
        if (Updates.DEV.equals(channel)) {
            msg.append("\n\n").append(L.t("Dev builds are made for testing and may contain bugs. Going back to Stable "
                    + "later usually works but cannot be guaranteed if a dev build changed how trips are stored "
                    + "(your backup in Documents/TripRecorderNG stays safe)."));
        }
        msg.append("\n\n").append(L.t("The app restarts during the update. BYD switches its auto-start setting "
                + "back on at every install: afterwards turn Trip Recorder NG OFF in 'Disable background Apps' "
                + "and restart the head unit once."));
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.t("Update available"))
                .setMessage(msg.toString())
                .setPositiveButton(L.t("Install"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        startUpdate(r);
                    }
                })
                .setNegativeButton(L.t("Cancel"), null));
    }

    /** Downloads the build, checks it, saves a backup and installs it through the car's loopback debugging. */
    private void startUpdate(final Updates.Release r) {
        if (updateBusy) return;
        double speed = RecorderService.live.speed;
        if (!Double.isNaN(speed) && speed >= 5) {
            showInfo(L.t("Update only while the car is parked."));
            return;
        }
        if (!Prefs.linkEnabled(this)) {
            showInfo(L.t("Installing needs the Vehicle data link: turn it on in Setting first. The app installs "
                    + "the update through the car's network debugging."));
            return;
        }
        updateBusy = true;
        final Context app = getApplicationContext();
        final boolean[] cancel = {false};
        final AlertDialog progress = Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.f("Installing %s", r.build.name()))
                .setMessage(L.t("Downloading..."))
                .setCancelable(false)
                .setNegativeButton(L.t("Cancel"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        cancel[0] = true;
                    }
                }));
        final long[] lastShown = {0};
        new Thread(new Runnable() {
            private void say(final String text, final boolean last) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (progress.isShowing()) {
                            progress.setMessage(text);
                            if (last && progress.getButton(android.content.DialogInterface.BUTTON_NEGATIVE) != null) {
                                progress.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
                            }
                        }
                    }
                });
            }

            private void fail(final String why) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        updateBusy = false;
                        if (progress.isShowing()) progress.dismiss();
                        if (!isFinishing() && !isDestroyed()) showInfo(why);
                    }
                });
            }

            @Override public void run() {
                try {
                    File apk = Updates.download(app, r, new Updates.Progress() {
                        @Override public void onProgress(long done, long total) {
                            long now = System.currentTimeMillis();
                            if (now - lastShown[0] < 400) return;
                            lastShown[0] = now;
                            say(total > 0 ? L.f("Downloading... %d%%", (int) (done * 100 / total)) : L.t("Downloading..."),
                                    false);
                        }

                        @Override public boolean cancelled() {
                            return cancel[0];
                        }
                    });
                    say(L.t("Checking the file..."), true);
                    String bad = Updates.verifyApk(app, apk);
                    if (bad != null) {
                        fail(bad);
                        return;
                    }
                    try {
                        Backup.writeMissing(app); // the trips are saved before anything is replaced
                    } catch (Throwable t) {
                        Diag.log("backup before update failed", t);
                    }
                    say(L.t("Installing... the app restarts in a moment."), true);
                    AdbLoopback.Result res = Updates.install(app, apk);
                    // only reached when this app was not replaced
                    Diag.log("update install result: " + res.status + " | " + res.output.trim() + " | " + res.detail);
                    fail(installFailure(res));
                } catch (Updates.Cancelled c) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            updateBusy = false;
                            if (progress.isShowing()) progress.dismiss();
                        }
                    });
                } catch (IOException e) {
                    Diag.log("update download failed", e);
                    fail(L.f("The update failed: %s", e.getMessage()));
                } catch (Throwable t) {
                    Diag.log("update failed", t);
                    fail(L.t("The update failed."));
                }
            }
        }).start();
    }

    private String installFailure(AdbLoopback.Result res) {
        switch (res.status) {
            case UNREACHABLE:
                return L.t("The car's network debugging could not be reached. Check Setting > Vehicle data link.");
            case REFUSED:
                return L.t("The car refused this app's debugging key. Turn the Vehicle data link off and on again.");
            case WAITING_FOR_APPROVAL:
                return L.t("The car did not answer in time. If the app restarts by itself, the update worked.");
            case ERROR:
                return L.f("The update failed: %s", res.detail);
            default:
                // the installer's answer: "Failure [...]" or a Java stack trace; the first line is enough here
                String out = res.output.trim();
                if (out.isEmpty()) return L.t("The update did not finish.");
                int nl = out.indexOf('\n');
                String first = nl > 0 ? out.substring(0, nl).trim() : out;
                if (first.length() > 160) first = first.substring(0, 160) + "...";
                return L.f("The update failed: %s", first) + (nl > 0 ? "\n" + L.t("Details are in the log file.") : "");
        }
    }

    private View settingRow(String title, String value, String button, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(Ui.text(this, title, 20, Ui.TEXT_VALUE));
        TextView v = Ui.text(this, value, 15, Ui.TEXT_LABEL);
        v.setPadding(0, Ui.dp(this, 5), 0, 0);
        left.addView(v);
        row.setTag(v);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(pill(button, l));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(Ui.divider(this));
        wrap.setTag(v);
        return wrap;
    }

    private View qaPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = card();
        box.setPadding(Ui.dp(this, 32), Ui.dp(this, 22), Ui.dp(this, 32), Ui.dp(this, 22));
        sv.addView(box, new ViewGroup.LayoutParams(-1, -2));
        String[][] qa = {
                {L.t("How is a trip recorded?"),
                        L.t("A trip is one ignition cycle: it starts when the car is switched on and ends "
                                + "when it is switched off. If the ignition state cannot be read, a trip "
                                + "starts when the car moves and ends after three minutes standing still.")},
                {L.t("What is measured?"),
                        L.t("Speed (several times a second), battery level and the car's energy counter, "
                                + "plus the GPS position every few seconds. Distance comes from the car's own trip counter (from speed when that is not available).")},
                {L.t("Does it need internet?"),
                        L.t("No. Everything is stored on this head unit. Nothing is uploaded. The only exception is "
                                + "Setting > Check for updates, which contacts GitHub, and only when you tap it.")},
                {L.t("How is the drive score calculated?"),
                        L.t("Simplified: 100 points, minus 5 for every hard acceleration or hard braking. "
                                + "It differs from the stock app, which uses settings downloaded from BYD.")},
                {L.t("What is the Vehicle data link?"),
                        L.t("An optional setting (off by default). When on, the app starts a small helper "
                                + "through the car's network debugging so it can read BYD's own vehicle "
                                + "data: battery level, energy counter, odometer and ignition. Without it "
                                + "trips are recorded from GPS only.")},
                {L.t("Why does recording stop after a restart?"),
                        L.t("BYD blocks apps from starting by themselves. Open Setting > BYD auto-start apps and turn "
                                + "Trip Recorder NG OFF in 'Disable background Apps'. BYD switches it back on every time "
                                + "the app is installed or updated, so do this again after an update.")},
                {L.t("Where are my files?"),
                        L.t("Export CSV and GPX from My Trips or Setting. Files go to Documents/TripRecorderNG.")},
                {L.t("Are my trips backed up?"),
                        L.t("Yes, by default. Each finished trip is also saved as a small file in "
                                + "Documents/TripRecorderNG/backup, so the trips survive an uninstall or a "
                                + "cleared app. Setting > Restore from backup adds any missing trips back.")},
                {L.t("How is the cost calculated?"),
                        L.t("Prices start at 0.010 OMR per kWh and 0.239 OMR per litre. Change them in Setting > "
                                + "Energy prices (leave a price empty to go back to the default). A trip costs the energy it took from the battery times the electricity "
                                + "price, plus the fuel it burned times the fuel price. This car is a plug-in "
                                + "hybrid, so fuel is recorded with each trip made since the update (older trips "
                                + "only get the electricity part). A charging session costs the energy it added "
                                + "times the electricity price. Charging losses are not included.")},
                {L.t("What are Driving advice, CO2 avoided and the driving level?"),
                        L.t("Driving advice on a trip uses the stock app's advice texts, chosen by simple rules "
                                + "(hard braking or acceleration, high average speed, high consumption, a long "
                                + "drive). CO2 avoided compares your trips with a typical petrol car (8 L per "
                                + "100 km) minus the fuel you burned and an assumed 0.4 kg CO2 per kWh of grid "
                                + "electricity: it is an estimate. The driving level uses the stock app's level "
                                + "names but steps by the distance recorded here.")},
                {L.t("What are Tweaks?"),
                        L.t("Small changes to the car that need the shell user's rights, made through the car's "
                                + "network debugging, so they need the Vehicle data link. The first one turns "
                                + "mobile data roaming on (settings put global data_roaming 1). \"Keep data "
                                + "roaming on\" repeats it at start-up, whenever the helper starts, and within a "
                                + "minute if something switches roaming off.")},
                {L.t("How are charging sessions recorded?"),
                        L.t("With the Vehicle data link on, the app reads the car's charging state. A session "
                                + "starts when the car begins charging and ends a minute after it stops. It "
                                + "stores the battery level along the way. This car does not report charging "
                                + "power or energy, so the kWh added is an estimate: the battery percentage "
                                + "gained times the kWh per percent measured on your own trips. The head "
                                + "unit sleeps when the car is off and the app cannot record while it sleeps: "
                                + "a session it only saw part of is marked Partial, and the final battery "
                                + "level is read as soon as the unit wakes. If the whole charge happened "
                                + "while it slept, the app compares the battery level with the last one it "
                                + "saw and records the rise as a Partial session.")},
                {L.t("What do the colours and charts on a trip show?"),
                        L.t("The route is coloured by speed (green slow, red fast). Red and amber dots mark "
                                + "hard braking and hard acceleration. Under the map, pick Speed, Battery, "
                                + "Elevation or Efficiency and touch the chart to see that moment on the map. "
                                + "Battery, Efficiency and event dots are recorded for trips made after the "
                                + "update; older trips show speed and elevation only.")}};
        for (String[] q : qa) {
            TextView h = Ui.text(this, q[0], 20, Ui.TEXT_SELECTED);
            h.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
            box.addView(h);
            TextView a = Ui.text(this, q[1], 17, Ui.TEXT_DATE);
            a.setLineSpacing(Ui.dp(this, 3), 1f);
            a.setPadding(0, 0, 0, Ui.dp(this, 14));
            box.addView(a);
        }
        return sv;
    }

    // ---- actions ---------------------------------------------------------------------------
    private void refreshSettingsSoon() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if ("settings".equals(overlay)) showOverlay("settings");
            }
        }, 400);
    }

    private void confirmEnableLink() {
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.t("Turn on Vehicle data link?"))
                .setMessage(L.t("This adds battery, energy, odometer and ignition data to your trips.\n\n"
                        + "To get them, Trip Recorder NG uses the car's own network debugging (ADB) on "
                        + "this head unit to start a small helper. Depending on the head unit, the car "
                        + "may ask you to allow debugging for this app (tick \"Always allow\" and tap "
                        + "Allow); on some units it is accepted automatically. After that the helper "
                        + "starts by itself each time the car boots.\n\n"
                        + "You can switch it off here at any time (this stops the helper), and "
                        + "\"Forget\" deletes the app's key."))
                .setPositiveButton(L.t("Turn on"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Prefs.setLinkConsented(MainActivity.this);
                        HelperLauncher.enable(MainActivity.this);
                        toast(L.t("Connecting to the car's data service..."));
                        ui.postDelayed(new Runnable() {
                            @Override public void run() {
                                if ("settings".equals(overlay)) showOverlay("settings");
                            }
                        }, 600);
                    }
                })
                .setNegativeButton(L.t("Cancel"), null));
    }

    private EditText priceField(LinearLayout box, String label, String hint, String value, boolean decimal) {
        TextView t = Ui.text(this, label, 15, 0xFF8A8F98);
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        box.addView(t);
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0x55FFFFFF);
        e.setSingleLine(true);
        e.setText(value);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(e);
        return e;
    }

    /** Asks for the electricity price per kWh, the fuel price per litre and the currency label. */
    private void showPriceDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0);
        double ep = Prefs.elecPriceSet(this), fp = Prefs.fuelPriceSet(this);
        // the on-screen keypad covers the fields, so show what has been typed on a line above them
        final TextView summary = Ui.text(this, "", 16, Ui.GREEN);
        summary.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(summary);
        final EditText cur = priceField(box, L.t("Currency"), "OMR", Prefs.currency(this), false);
        final EditText elec = priceField(box, L.t("Electricity price per kWh"),
                L.f("default %s", trimNumber(Cost.DEFAULT_ELEC_PRICE)),
                Double.isNaN(ep) ? "" : trimNumber(ep), true);
        final EditText fuel = priceField(box, L.t("Fuel price per litre"),
                L.f("default %s", trimNumber(Cost.DEFAULT_FUEL_PRICE)),
                Double.isNaN(fp) ? "" : trimNumber(fp), true);
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                String code = cur.getText().toString().trim();
                if (code.isEmpty()) code = "OMR";
                String el = cleanPrice(elec.getText().toString()), fu = cleanPrice(fuel.getText().toString());
                summary.setText(L.f("Electricity %1$s %2$s/kWh     Fuel %3$s %2$s/L",
                        el.isEmpty() ? trimNumber(Cost.DEFAULT_ELEC_PRICE) + L.t(" (default)") : el, code,
                        fu.isEmpty() ? trimNumber(Cost.DEFAULT_FUEL_PRICE) + L.t(" (default)") : fu));
            }
        };
        cur.addTextChangedListener(watcher);
        elec.addTextChangedListener(watcher);
        fuel.addTextChangedListener(watcher);
        watcher.afterTextChanged(null);
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.t("Energy prices"))
                .setMessage(L.t("Used to work out what each trip and each charge cost. Leave a price empty to "
                        + "use the default. The cost counts the energy taken from the battery, so charging "
                        + "losses are not included."))
                .setView(box)
                .setPositiveButton(L.t("Save"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Prefs.setPrices(MainActivity.this, cleanPrice(elec.getText().toString()),
                                cleanPrice(fuel.getText().toString()), cur.getText().toString().trim());
                        toast(L.t("Prices saved"));
                        loadTrips(null);
                        refreshSettingsSoon();
                    }
                })
                .setNegativeButton(L.t("Cancel"), null));
    }

    /** A positive number as text, or empty if the input is not one. */
    private static String cleanPrice(String in) {
        try {
            double v = Double.parseDouble(in.trim());
            return v > 0 ? trimNumber(v) : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String trimNumber(double v) {
        String s = String.format(java.util.Locale.US, "%.5f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private void backupNow(final boolean report) {
        new Thread(new Runnable() {
            @Override public void run() {
                int n = Backup.writeMissing(MainActivity.this);
                Backup.Status st = Backup.status(MainActivity.this);
                if (report) {
                    toast(n == 0 ? L.f("Everything is already backed up (%d files)", st.files)
                            : L.f("Backed up %1$d item(s) (%2$d files in %3$s)", n, st.files, L.ltr(st.dir.getPath())));
                }
                if (report) refreshSettingsSoon();
            }
        }).start();
    }

    private void confirmRestore() {
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.t("Restore trips from backup?"))
                .setMessage(L.t("Trips found in the backup folder that are not in the app yet will be added. "
                        + "Existing trips are not changed."))
                .setPositiveButton(L.t("Restore"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        restoreNow();
                    }
                })
                .setNegativeButton(L.t("Cancel"), null));
    }

    private void restoreNow() {
        new Thread(new Runnable() {
            @Override public void run() {
                Backup.RestoreResult r = Backup.restore(MainActivity.this);
                ChargeRecorder.repairSplits(MainActivity.this, TripDb.get(MainActivity.this));
                toast(L.f("%1$d trip(s) and %2$d charging session(s) restored", r.restored, r.restoredCharges)
                        + (r.alreadyThere > 0 ? L.sep() + L.f("%d already in the app", r.alreadyThere) : "")
                        + (r.failed > 0 ? L.sep() + L.f("%d could not be read", r.failed) : ""));
                ui.post(new Runnable() {
                    @Override public void run() {
                        loadTrips(null);
                    }
                });
            }
        }).start();
    }

    private void confirmDelete(final Trip t) {
        Ui.show(new AlertDialog.Builder(this)
                .setMessage(L.t("Delete this trip permanently?"))
                .setPositiveButton(L.t("Delete"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        TripDb.get(MainActivity.this).delete(t.id);
                        closeOverlay();
                        loadTrips(null);
                    }
                })
                .setNegativeButton(L.t("Cancel"), null));
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void exportGpx(final Trip t) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    toast(L.f("Saved %s", L.ltr(Exporter.exportGpx(MainActivity.this, t).getPath())));
                } catch (Throwable e) {
                    Diag.log("gpx export failed", e);
                    toast(L.f("Export failed: %s", e.getMessage()));
                }
            }
        }).start();
    }

    private void exportCsv() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    toast(L.f("Saved %s", L.ltr(Exporter.exportCsv(MainActivity.this).getPath())));
                } catch (Throwable e) {
                    Diag.log("csv export failed", e);
                    toast(L.f("Export failed: %s", e.getMessage()));
                }
            }
        }).start();
    }

    private void selfTest() {
        new Thread(new Runnable() {
            @Override public void run() {
                VehicleProvider p = RecorderService.holder.provider;
                if (p == null) p = new VehicleProvider(MainActivity.this, null);
                final String report = p.probe();
                Vehicle v = p.get();
                final StringBuilder sb = new StringBuilder(report).append("\n\n");
                if (v != null) {
                    sb.append("speed ").append(Ui.f(v.speedKmh(), "%.1f")).append(" km/h\n");
                    sb.append("power level ").append(Ui.f(v.powerLevel(), "%.0f")).append('\n');
                    sb.append("battery ").append(Ui.f(v.socPercent(), "%.1f")).append(" %\n");
                    sb.append("energy counter ").append(Ui.f(v.totalElecConsumption(), "%.2f")).append('\n');
                    sb.append("driving time counter ").append(Ui.f(v.totalDrivingTime(), "%.1f")).append('\n');
                    sb.append("odometer ").append(Ui.f(v.odometerKm(), "%.1f")).append(" km\n");
                    sb.append("last error: ").append(v.lastError()).append('\n');
                }
                sb.append("\nGPS permission: ").append(
                        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                == PackageManager.PERMISSION_GRANTED ? "granted" : "NOT granted");
                ui.post(new Runnable() {
                    @Override public void run() {
                        Ui.show(new AlertDialog.Builder(MainActivity.this)
                                .setTitle(L.t("Self-test"))
                                .setMessage(sb.toString())
                                .setPositiveButton(L.t("OK"), null));
                    }
                });
            }
        }).start();
    }

    private void startRecorder() {
        try {
            startForegroundService(new Intent(this, RecorderService.class));
        } catch (Throwable t) {
            Diag.log("start failed", t);
        }
    }

    private void requestPerms(boolean force) {
        List<String> need = new ArrayList<>();
        String[] all = {
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE};
        for (String p : all) {
            if (force || checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        startRecorder();
    }

    private void openAutostart() {
        autostartScreenOpenedMs = System.currentTimeMillis();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName("com.byd.appstartmanagement",
                "com.byd.appstartmanagement.frame.AppStartManagement"));
        try {
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
            } catch (Throwable t2) {
                toast(L.t("Could not open settings"));
            }
        }
    }

    private void openBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast(L.t("Not available on this unit"));
        }
    }

    // ---- title-bar glyphs ------------------------------------------------------------------
    /** Small vector-style icons for the title bar, drawn with Canvas. */
    static final class GlyphView extends View {
        static final int BACK = 0;
        static final int HELP = 1;
        static final int GEAR = 2;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlyphView(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Ui.TEXT_VALUE);
            p.setStrokeWidth(2.4f * c.getResources().getDisplayMetrics().density);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) * 0.42f;
            switch (kind) {
                case BACK:
                    c.drawLine(cx + r * 0.35f, cy - r * 0.8f, cx - r * 0.45f, cy, p);
                    c.drawLine(cx - r * 0.45f, cy, cx + r * 0.35f, cy + r * 0.8f, p);
                    break;
                case HELP:
                    c.drawCircle(cx, cy, r, p);
                    Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                    t.setColor(Ui.TEXT_VALUE);
                    t.setTextAlign(Paint.Align.CENTER);
                    t.setTextSize(r * 1.35f);
                    c.drawText("?", cx, cy + r * 0.48f, t);
                    break;
                default:
                    c.drawCircle(cx, cy, r * 0.42f, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4.0;
                        float x1 = cx + (float) Math.cos(a) * r * 0.72f;
                        float y1 = cy + (float) Math.sin(a) * r * 0.72f;
                        float x2 = cx + (float) Math.cos(a) * r;
                        float y2 = cy + (float) Math.sin(a) * r;
                        c.drawLine(x1, y1, x2, y2, p);
                    }
                    c.drawCircle(cx, cy, r * 0.72f, p);
            }
        }
    }
}
