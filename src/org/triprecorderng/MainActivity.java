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
    // title bar actions of the detail screens
    private LinearLayout exportButton;
    private LinearLayout placeButton;
    private LinearLayout deleteButton;
    private TextView titleView;
    private final LinearLayout[] tabViews = new LinearLayout[6];
    private final int[] tabIcons = new int[6];
    private int tab = TAB_THIS;
    private String overlay = null; // "detail", "settings", "qa" or null
    private volatile boolean updateBusy;
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

    /**
     * Test hooks: "adb shell am start ... --ez demo_trips true" adds sample trips, clear_demo removes them;
     * "--es blind_test left|right --ei blind_secs 20" shows the blind-spot window as if that signal were on.
     */
    private void handleExtras(final Intent i) {
        if (i == null) return;
        String blind = i.getStringExtra("blind_test");
        if (blind != null) BlindSpot.test("left".equals(blind) ? SideCamera.LEFT : SideCamera.RIGHT, i.getIntExtra("blind_secs", 20));
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
                List<Trip> loaded = TripDb.get(MainActivity.this).all();
                boolean unsplit = false;
                for (Trip t : loaded) if (t.ended && Double.isNaN(t.driveSec)) unsplit = true;
                // older and restored trips get their driving/parked split worked out from their stored samples
                if (unsplit && DriveSplit.backfill(MainActivity.this) > 0) loaded = TripDb.get(MainActivity.this).all();
                final List<Trip> all = loaded;
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
        exportButton = barButton(GlyphView.SHARE, L.t("Export GPX"), null);
        placeButton = barButton(GlyphView.PIN, L.t("Show place"), null);
        deleteButton = barButton(GlyphView.TRASH, L.t("Delete"), null);
        ((TextView) deleteButton.getChildAt(1)).setTextColor(0xFFFF8D8D);
        ((GlyphView) deleteButton.getChildAt(0)).setColor(0xFFFF8D8D);
        exportButton.setVisibility(View.GONE);
        placeButton.setVisibility(View.GONE);
        deleteButton.setVisibility(View.GONE);
        bar.addView(exportButton);
        bar.addView(placeButton);
        bar.addView(deleteButton);
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
        exportButton.setVisibility(View.GONE);
        placeButton.setVisibility(View.GONE);
        deleteButton.setVisibility(View.GONE);
    }

    private int res(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private LinearLayout barButton(int glyph, String text, View.OnClickListener l) {
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
        if ("settings".equals(which) && !"settings".equals(overlay)) SettingsPage.resetScroll();
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
        return ThisTripPage.build(this, new ThisTripPage.Host() {
            @Override public List<Trip> trips() {
                return trips;
            }

            @Override public void openTrip(Trip t) {
                showDetail(t);
            }

            @Override public void openSetting() {
                showOverlay("settings");
            }

            @Override public void refresher(Runnable r) {
                pageRefresh = r;
            }
        });
    }

    // ---- My Trips --------------------------------------------------------------------------
    private View myTripsPage() {
        return MyTripsPage.build(this, new MyTripsPage.Host() {
            @Override public List<Trip> trips() {
                return trips;
            }

            @Override public void openTrip(Trip t) {
                showDetail(t);
            }

            @Override public void exportCsv() {
                MainActivity.this.exportCsv();
            }

            @Override public void confirmRestore() {
                MainActivity.this.confirmRestore();
            }

            @Override public int emptyImage() {
                return res("empty_trips");
            }

            @Override public void listBuilt(ListView list) {
                tripsList = list;
            }

            @Override public int scrollFirst() {
                return tripsFirst;
            }

            @Override public int scrollTop() {
                return tripsTop;
            }
        });
    }

    // ---- Attainment ------------------------------------------------------------------------
    private View attainmentPage() {
        return AttainmentPage.build(this, trips);
    }

    // ---- Statistics ------------------------------------------------------------------------
    private View statisticsPage() {
        return StatisticsPage.build(this, trips, new StatisticsPage.Host() {
            @Override public void redraw() {
                renderCurrentTab();
            }
        });
    }

    // ---- trip detail -----------------------------------------------------------------------
    private void showDetail(final Trip t) {
        saveTripsScroll();
        overlay = "detail";
        pageRefresh = null;
        setChrome(true);
        titleView.setText(L.t("Trip details"));
        content.removeAllViews();
        content.addView(TripDetailPage.build(this, t));
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                exportGpx(t);
            }
        });
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDelete(t);
            }
        });
        exportButton.setVisibility(View.VISIBLE);
        deleteButton.setVisibility(View.VISIBLE);
    }

    // ---- Tweaks ----------------------------------------------------------------------------
    private View tweaksPage() {
        return TweaksPage.build(this, new TweaksPage.Host() {
            @Override public void openSetting() {
                showOverlay("settings");
            }

            @Override public void confirmModemReboot() {
                MainActivity.this.confirmModemReboot();
            }

            @Override public void refresher(Runnable r) {
                pageRefresh = r;
            }
        });
    }

    // ---- Charging --------------------------------------------------------------------------
    private View chargingPage() {
        return ChargingPage.build(this, new ChargingPage.Host() {
            @Override public List<Charge> charges() {
                return charges;
            }

            @Override public List<Trip> trips() {
                return trips;
            }

            @Override public void openCharge(Charge ch, double kwhPerPercent) {
                showChargeDetail(ch, kwhPerPercent);
            }

            @Override public int emptyImage() {
                return res("empty_trips");
            }
        });
    }

    private void showChargeDetail(final Charge c, double kwhPerPct) {
        overlay = "detail";
        pageRefresh = null;
        setChrome(true);
        titleView.setText(L.t("Charging details"));
        content.removeAllViews();
        content.addView(ChargeDetailPage.build(this, c, kwhPerPct));
        placeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MapLink.openPlace(MainActivity.this, c.lat, c.lon);
            }
        });
        deleteButton.setOnClickListener(new View.OnClickListener() {
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
        });
        placeButton.setVisibility(!Double.isNaN(c.lat) && !Double.isNaN(c.lon) ? View.VISIBLE : View.GONE);
        deleteButton.setVisibility(View.VISIBLE);
    }

    // ---- settings and Q&A ------------------------------------------------------------------
    private View settingsPage() {
        return SettingsPage.build(this, new SettingsPage.Host() {
            @Override public void toast(String s) {
                MainActivity.this.toast(s);
            }

            @Override public void rebuildSoon() {
                refreshSettingsSoon();
            }

            @Override public String languageName() {
                return MainActivity.this.languageName();
            }

            @Override public void showLanguageDialog() {
                MainActivity.this.showLanguageDialog();
            }

            @Override public String updateText() {
                return MainActivity.this.updateText();
            }

            @Override public String channelDescription() {
                return MainActivity.this.channelDescription();
            }

            @Override public void checkForUpdates() {
                MainActivity.this.checkForUpdates();
            }

            @Override public void showChannelDialog() {
                MainActivity.this.showChannelDialog();
            }

            @Override public void bindUpdateViews(TextView updates, TextView channel) {
                updatesValue = updates;
                channelValue = channel;
            }

            @Override public void toggleRecorder() {
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

            @Override public String vehicleName(String raw) {
                return MainActivity.vehicleName(raw);
            }

            @Override public void selfTest() {
                MainActivity.this.selfTest();
            }

            @Override public void toggleLink() {
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

            @Override public void reconnect() {
                HelperLauncher.ensureAsync(MainActivity.this, true);
                toast(L.t("Reconnecting..."));
                refreshSettingsSoon();
            }

            @Override public void forgetKey() {
                HelperLauncher.disable(MainActivity.this);
                HelperLauncher.forgetKey(MainActivity.this);
                toast(L.t("Key removed and link turned off"));
                refreshSettingsSoon();
            }

            @Override public boolean locationAllowed() {
                return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            }

            @Override public void requestLocation() {
                requestPerms(true);
            }

            @Override public void openAutostart() {
                MainActivity.this.openAutostart();
            }

            @Override public void showPriceDialog() {
                MainActivity.this.showPriceDialog();
            }

            @Override public void toggleBackup(boolean on) {
                Prefs.setAutoBackup(MainActivity.this, on);
                if (on) MainActivity.this.backupNow(false);
                toast(on ? L.t("Automatic backup turned on") : L.t("Automatic backup turned off"));
                refreshSettingsSoon();
            }

            @Override public void backupNow() {
                MainActivity.this.backupNow(true);
            }

            @Override public void confirmRestore() {
                MainActivity.this.confirmRestore();
            }

            @Override public void exportCsv() {
                MainActivity.this.exportCsv();
            }

            @Override public void refresher(Runnable r) {
                pageRefresh = r;
            }
        });
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
        if (cmp > 0) {
            String changes = Updates.changesSince(c.all, channel, mine, r);
            if (!changes.isEmpty()) {
                msg.append("\n\n").append(L.t("What's new"));
                for (String line : changes.split("\n")) msg.append('\n').append(line.isEmpty() ? "" : L.ltr(line));
            }
        }
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
                if (first.equals("Success")) return L.t("The update was installed. If the app does not open by "
                        + "itself, open it again.");
                if (first.length() > 160) first = first.substring(0, 160) + "...";
                return L.f("The update failed: %s", first) + (nl > 0 ? "\n" + L.t("Details are in the log file.") : "");
        }
    }

    private View qaPage() {
        return QaPage.build(this);
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
                summary.setText(L.f("Electricity %1$s     Fuel %2$s",
                        L.per((el.isEmpty() ? trimNumber(Cost.DEFAULT_ELEC_PRICE) : el) + " " + code, L.t("kWh"))
                                + (el.isEmpty() ? L.t(" (default)") : ""),
                        L.per((fu.isEmpty() ? trimNumber(Cost.DEFAULT_FUEL_PRICE) : fu) + " " + code, L.t("L"))
                                + (fu.isEmpty() ? L.t(" (default)") : "")));
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

    private void confirmModemReboot() {
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(L.t("Reboot the modem?"))
                .setMessage(L.t("The car's mobile connection drops for about a minute while the modem restarts."))
                .setPositiveButton(L.t("Reboot"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Tweaks.rebootModem(MainActivity.this, "button");
                    }
                })
                .setNegativeButton(L.t("Cancel"), null));
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

    // ---- title-bar glyphs ------------------------------------------------------------------
    /** Small vector-style icons for the title bar, drawn with Canvas. */
    static final class GlyphView extends View {
        static final int BACK = 0;
        static final int HELP = 1;
        static final int GEAR = 2;
        static final int SHARE = 3;
        static final int PIN = 4;
        static final int TRASH = 5;
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

        void setColor(int color) {
            p.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) * 0.42f;
            switch (kind) {
                case SHARE:
                    c.drawLine(cx, cy + r * 0.35f, cx, cy - r, p);
                    c.drawLine(cx - r * 0.45f, cy - r * 0.55f, cx, cy - r, p);
                    c.drawLine(cx + r * 0.45f, cy - r * 0.55f, cx, cy - r, p);
                    c.drawLine(cx - r * 0.8f, cy + r * 0.2f, cx - r * 0.8f, cy + r * 0.85f, p);
                    c.drawLine(cx - r * 0.8f, cy + r * 0.85f, cx + r * 0.8f, cy + r * 0.85f, p);
                    c.drawLine(cx + r * 0.8f, cy + r * 0.85f, cx + r * 0.8f, cy + r * 0.2f, p);
                    break;
                case PIN:
                    c.drawCircle(cx, cy - r * 0.2f, r * 0.62f, p);
                    c.drawLine(cx - r * 0.5f, cy + r * 0.25f, cx, cy + r * 0.95f, p);
                    c.drawLine(cx + r * 0.5f, cy + r * 0.25f, cx, cy + r * 0.95f, p);
                    c.drawCircle(cx, cy - r * 0.2f, r * 0.2f, p);
                    break;
                case TRASH:
                    c.drawLine(cx - r * 0.9f, cy - r * 0.55f, cx + r * 0.9f, cy - r * 0.55f, p);
                    c.drawLine(cx - r * 0.3f, cy - r * 0.55f, cx - r * 0.3f, cy - r * 0.9f, p);
                    c.drawLine(cx + r * 0.3f, cy - r * 0.55f, cx + r * 0.3f, cy - r * 0.9f, p);
                    c.drawLine(cx - r * 0.3f, cy - r * 0.9f, cx + r * 0.3f, cy - r * 0.9f, p);
                    c.drawLine(cx - r * 0.65f, cy - r * 0.55f, cx - r * 0.5f, cy + r * 0.9f, p);
                    c.drawLine(cx - r * 0.5f, cy + r * 0.9f, cx + r * 0.5f, cy + r * 0.9f, p);
                    c.drawLine(cx + r * 0.5f, cy + r * 0.9f, cx + r * 0.65f, cy - r * 0.55f, p);
                    break;
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
