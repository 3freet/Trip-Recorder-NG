package org.triprecorderng;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The Setting screen: the same cards, switches and buttons as the Tweaks screen, in two columns. What the rows do
 * is unchanged; MainActivity keeps the dialogs and the work behind them and is reached through Host.
 */
final class SettingsPage {
    interface Host {
        void toast(String s);

        /** Draws the page again shortly (something it shows has changed). */
        void rebuildSoon();

        String languageName();

        void showLanguageDialog();

        String updateText();

        String channelDescription();

        void checkForUpdates();

        void showChannelDialog();

        /** The text views MainActivity refreshes after the update channel changes. */
        void bindUpdateViews(TextView updates, TextView channel);

        void toggleRecorder();

        String vehicleName(String raw);

        void selfTest();

        void toggleLink();

        void reconnect();

        void forgetKey();

        boolean locationAllowed();

        void requestLocation();

        void openAutostart();

        void showPriceDialog();

        void toggleBackup(boolean on);

        void backupNow();

        void confirmRestore();

        void exportCsv();

        /** Sets the code that is run about once a second while the page is shown. */
        void refresher(Runnable r);
    }

    /** Kept across redraws so the page does not jump back to the top when something changes. */
    private static boolean advancedOpen;
    private static int scrollY;

    static void resetScroll() {
        scrollY = 0;
    }

    private final Context c;
    private final Host host;
    private final Kit k;

    private Kit.Row serviceRow;
    private Kit.SwitchView serviceSwitch;
    private Kit.Row sourceRow;
    private Kit.Row linkRow;
    private Kit.SwitchView linkSwitch;
    private Kit.Row reconnectRow;

    static View build(Context c, Host host) {
        return new SettingsPage(c, host).create();
    }

    private SettingsPage(Context c, Host host) {
        this.c = c;
        this.host = host;
        this.k = new Kit(c);
    }

    private View create() {
        final ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout cols = new LinearLayout(c);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = k.column();
        LinearLayout right = k.column();
        cols.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.setMarginStart(k.d(12));
        cols.addView(right, rlp);
        sv.addView(cols, new ViewGroup.LayoutParams(-1, -2));

        generalSection(left);
        recordingSection(left);
        linkSection(right);
        backupSection(right);
        File lf = Diag.file();
        TextView log = k.label(L.f("Log file: %s", lf == null ? "?" : L.ltr(lf.getPath())), 12, Kit.MUTED, Kit.MEDIUM);
        log.setPadding(k.d(6), k.d(12), k.d(6), k.d(12));
        right.addView(log);

        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                scrollY = y;
            }
        });
        sv.post(new Runnable() {
            @Override public void run() {
                sv.scrollTo(0, scrollY);
            }
        });
        host.refresher(new Runnable() {
            @Override public void run() {
                refresh();
            }
        });
        refresh();
        return sv;
    }

    // ---- sections --------------------------------------------------------------------------------------
    private void section(LinearLayout into, String title, Kit.Row... rows) {
        TextView h = k.header(title);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = k.d(into.getChildCount() == 0 ? 0 : 12);
        into.addView(h, hlp);
        LinearLayout card = k.card();
        k.fill(card, rows);
        into.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private Kit.Row action(String title, String desc, String button, boolean primary, View.OnClickListener l) {
        Kit.Row r = k.buttonRow(title, desc, button, primary);
        r.onClick(l);
        return r;
    }

    private Kit.Row toggle(String title, String desc, Kit.SwitchView sw, View.OnClickListener l) {
        Kit.Row r = k.switchRow(title, desc, sw, false);
        r.onClick(l);
        return r;
    }

    private void generalSection(LinearLayout into) {
        Kit.Row language = action(L.t("Language"), host.languageName(), L.t("Change"), false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.showLanguageDialog();
                    }
                });
        Kit.Row updates = action(L.t("Updates"), host.updateText(), L.t("Check for updates"), true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.checkForUpdates();
                    }
                });
        Kit.Row prices = action(L.t("Energy prices"),
                L.f("Electricity %1$s %2$s/kWh%3$s, fuel %4$s %2$s/L%5$s", Ui.f(Cost.elecPrice(c), "%.4f"),
                        Prefs.currency(c), Cost.elecIsDefault(c) ? L.t(" (default)") : "",
                        Ui.f(Cost.fuelPrice(c), "%.3f"), Cost.fuelIsDefault(c) ? L.t(" (default)") : ""),
                L.t("Set"), false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.showPriceDialog();
                    }
                });
        final Kit.Row channel = action(L.t("Update channel"), host.channelDescription(), L.t("Change"), false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.showChannelDialog();
                    }
                });
        channel.setVisible(advancedOpen);
        final Kit.Row advanced = k.buttonRow(L.t("Advanced"),
                L.t("Options for experts: which builds the update check follows"),
                advancedOpen ? L.t("Hide") : L.t("Show"), false);
        advanced.onClick(new View.OnClickListener() {
            @Override public void onClick(View v) {
                advancedOpen = !advancedOpen;
                channel.setVisible(advancedOpen);
                ((TextView) advanced.control).setText(advancedOpen ? L.t("Hide") : L.t("Show"));
            }
        });
        host.bindUpdateViews(updates.desc, channel.desc);
        section(into, L.t("General"), language, updates, prices, advanced, channel);
    }

    private void recordingSection(LinearLayout into) {
        final RecorderService.Live l = RecorderService.live;
        serviceSwitch = new Kit.SwitchView(c);
        serviceRow = toggle(L.t("Trip recording service"), l.running ? L.t("Running") : L.t("Stopped"),
                serviceSwitch, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.toggleRecorder();
                    }
                });
        final Kit.SwitchView routeSwitch = new Kit.SwitchView(c);
        boolean routeOn = Prefs.recordRoute(c);
        routeSwitch.setOn(routeOn);
        Kit.Row route = toggle(L.t("Record route (GPS track)"),
                routeOn ? L.t("On: trips keep their route on the map")
                        : L.t("Off: new trips store no route and no event positions"),
                routeSwitch, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.setRecordRoute(c, !Prefs.recordRoute(c));
                        host.rebuildSoon();
                    }
                });
        sourceRow = action(L.t("Vehicle data source"), host.vehicleName(l.vehicle), L.t("Self-test"), false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.selfTest();
                    }
                });
        boolean loc = host.locationAllowed();
        Kit.Row location = action(L.t("Location (GPS track)"), loc ? L.t("Allowed") : L.t("Not allowed"),
                L.t("Allow"), !loc, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.requestLocation();
                    }
                });
        int health = Prefs.autoStartHealth(c);
        String autoText = health == Prefs.AUTOSTART_OK
                ? L.t("Started automatically after the last restart")
                : health == Prefs.AUTOSTART_BLOCKED
                ? L.t("NOT started automatically after the last restart: turn Trip Recorder NG OFF in "
                + "'Disable background Apps'")
                : L.t("Not confirmed yet: turn Trip Recorder NG OFF in 'Disable background Apps', then "
                + "restart the head unit once");
        Kit.Row autostart = action(L.t("BYD auto-start apps"), autoText, L.t("Open"), false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.openAutostart();
                    }
                });
        section(into, L.t("Trip recording"), serviceRow, route, sourceRow, location, autostart);
    }

    private void linkSection(LinearLayout into) {
        linkSwitch = new Kit.SwitchView(c);
        linkRow = toggle(L.t("Vehicle data link"), HelperLauncher.statusText(c), linkSwitch,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.toggleLink();
                    }
                });
        reconnectRow = action(L.t("Reconnect"), L.t("Try to connect to the car's data right now"),
                L.t("Retry now"), false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.reconnect();
                    }
                });
        Kit.Row key = action(L.t("Debugging key"), L.t("This app's key for the car's network debugging"),
                L.t("Forget"), false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.forgetKey();
                    }
                });
        section(into, L.t("Vehicle data link"), linkRow, reconnectRow, key);
    }

    private void backupSection(LinearLayout into) {
        final Backup.Status bs = Backup.status(c);
        boolean autoBk = Prefs.autoBackup(c);
        Kit.SwitchView sw = new Kit.SwitchView(c);
        sw.setOn(autoBk);
        Kit.Row auto = toggle(L.t("Automatic backup"),
                (autoBk ? L.t("On: every finished trip is also saved to ") : L.t("Off. Backup folder: "))
                        + L.ltr(bs.dir.getPath()) + "\n" + L.f("%d trip file(s)", bs.files)
                        + (bs.newestMs > 0 ? L.sep() + L.f("newest %s",
                        new SimpleDateFormat("d MMM HH:mm", L.dateLocale()).format(new Date(bs.newestMs)))
                        : "")
                        + (bs.publicFolder ? "" : "\n" + L.t("Warning: Documents is not writable, so this copy is "
                        + "deleted if the app is uninstalled")),
                sw, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.toggleBackup(!Prefs.autoBackup(c));
                    }
                });
        Kit.Row now = action(L.t("Back up now"), L.t("Saves any finished trip that has no backup file yet"),
                L.t("Back up"), false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.backupNow();
                    }
                });
        Kit.Row restore = action(L.t("Restore from backup"),
                L.t("Adds the trips from the backup folder that are missing here (nothing is overwritten)"),
                L.t("Restore"), false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.confirmRestore();
                    }
                });
        Kit.Row export = action(L.t("Export all trips"), "Documents/TripRecorderNG/trips.csv", L.t("Export"), false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.exportCsv();
                    }
                });
        section(into, L.t("Backup and export"), auto, now, restore, export);
    }

    // ---- keeping the live rows current ------------------------------------------------------------------
    private void refresh() {
        RecorderService.Live l = RecorderService.live;
        serviceSwitch.setOn(l.running);
        serviceRow.setDesc(l.running ? L.t("Running") : L.t("Stopped"));
        sourceRow.setDesc(host.vehicleName(l.vehicle));
        boolean linkOn = Prefs.linkEnabled(c);
        linkSwitch.setOn(linkOn);
        linkRow.setDesc(HelperLauncher.statusText(c));
        reconnectRow.setVisible(linkOn);
    }
}
