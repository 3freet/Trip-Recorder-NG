package org.triprecorderng;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The Tweaks screen: cards with switches and buttons, a Connection card and a Details sheet. */
final class TweaksPage {
    interface Host {
        void openSetting();

        void confirmModemReboot();

        /** Sets the code that is run about once a second while the page is shown. */
        void refresher(Runnable r);
    }

    private final Context c;
    private final Host host;
    private final Kit k;

    // what refresh() keeps up to date
    private TextView roamState;
    private Kit.SwitchView roamSwitch;
    private Kit.SwitchView keepRoamSwitch;
    private ActionView cloud;
    private ActionView avas;
    private Kit.SwitchView keepAvasSwitch;
    private ActionView modem;
    private Kit.SwitchView autoModemSwitch;
    private View dotRoam;
    private TextView connRoam;
    private View dotCell;
    private TextView connCell;
    private TextView connCellNote;
    private TextView connBadge;
    private TextView connApps;
    private FrameLayout sheet;

    static View build(Context c, Host host) {
        return new TweaksPage(c, host).create();
    }

    private TweaksPage(Context c, Host host) {
        this.c = c;
        this.host = host;
        this.k = new Kit(c);
    }

    // ---- the page --------------------------------------------------------------------------------
    private View create() {
        FrameLayout root = new FrameLayout(c);

        LinearLayout page = k.column();
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = k.row();
        TextView title = k.label(L.t("Tweaks"), 22, Kit.TEXT, Kit.BOLD);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(detailsButton());
        page.addView(head, new LinearLayout.LayoutParams(-1, k.d(48)));

        // the cards scroll; the Connection card beside them stays where it is
        LinearLayout cols = new LinearLayout(c);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout left = k.column();
        left.addView(roamingCard());
        left.addView(cloudCard(), k.gap(-1, -2, 0, 8));
        left.addView(avasCard(), k.gap(-1, -2, 0, 8));
        left.addView(modemCard(), k.gap(-1, -2, 0, 8));
        left.addView(noteLine(), k.gap(-1, k.d(30), 0, 6));
        sv.addView(left, new ViewGroup.LayoutParams(-1, -2));
        cols.addView(sv, new LinearLayout.LayoutParams(0, -1, 400f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 214f);
        rlp.setMarginStart(k.d(10));
        cols.addView(connectionCard(), rlp);
        page.addView(cols, new LinearLayout.LayoutParams(-1, 0, 1f));

        sheet = detailsSheet();
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));

        host.refresher(new Runnable() {
            @Override public void run() {
                refresh();
            }
        });
        refresh();
        return root;
    }

    private View detailsButton() {
        LinearLayout b = k.row();
        b.setGravity(Gravity.CENTER);
        b.setPadding(k.d(18), 0, k.d(18), 0);
        b.setBackground(Ui.stroke(0x14FFFFFF, Kit.SHEET, 22 * k.scale, c));
        b.addView(new Kit.IconView(c, Kit.IconView.CODE_ICON, Kit.SOFT),
                new LinearLayout.LayoutParams(k.d(18), k.d(18)));
        TextView t = k.label(L.t("Details"), 14, Kit.SOFT, Kit.MEDIUM);
        t.setPadding(k.d(8), 0, 0, 0);
        b.addView(t);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sheet.setVisibility(View.VISIBLE);
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, k.d(44)));
        return b;
    }

    private View noteLine() {
        LinearLayout l = k.row();
        l.addView(new Kit.IconView(c, Kit.IconView.INFO, Kit.MUTED), new LinearLayout.LayoutParams(k.d(16), k.d(16)));
        TextView a = k.label(L.t("Changes need the Vehicle data link turned on in"), 12.5f, Kit.MUTED, Kit.MEDIUM);
        a.setPadding(k.d(8), 0, k.d(6), 0);
        l.addView(a);
        TextView s = k.label(L.t("Setting"), 12.5f, Kit.ACCENT, Kit.BOLD);
        s.setPaintFlags(s.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        s.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.openSetting();
            }
        });
        l.addView(s);
        l.setPadding(k.d(4), 0, 0, 0);
        return l;
    }

    // ---- cards ---------------------------------------------------------------------------------------
    private View roamingCard() {
        LinearLayout card = k.card();

        LinearLayout big = k.row();
        big.setPadding(k.d(16), 0, k.d(16), 0);
        LinearLayout text = k.column();
        text.addView(k.label(L.t("Data roaming"), 17, Kit.TEXT, Kit.BOLD));
        roamState = k.label("", 13, Kit.MUTED, Kit.BOLD);
        roamState.setPadding(0, k.d(2), 0, 0);
        text.addView(roamState);
        big.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        roamSwitch = new Kit.SwitchView(c);
        big.addView(roamSwitch, new LinearLayout.LayoutParams(k.d(60), k.d(34)));
        big.setMinimumHeight(k.d(60));
        big.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = shownRoaming() != 1;
                if (!on) Prefs.setKeepRoaming(c, false); // otherwise it would be switched back on within a minute
                Tweaks.setRoaming(c, on, "switch");
                refresh();
            }
        });
        card.addView(big, new LinearLayout.LayoutParams(-1, -2));

        keepRoamSwitch = new Kit.SwitchView(c);
        Kit.Row keep = k.switchRow(L.t("Keep data roaming on"),
                L.t("Re-applied at start-up and whenever the car switches it off."), keepRoamSwitch, true);
        keep.onClick(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !Prefs.keepRoaming(c);
                Prefs.setKeepRoaming(c, on);
                if (on) Tweaks.ensureRoaming(c, true, "switched on");
                refresh();
            }
        });
        keep.divider();
        card.addView(keep.view, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private View cloudCard() {
        cloud = new ActionView(L.t("Connect to BYD cloud"),
                L.t("Links the car to BYD's server over any network, for the phone app."),
                L.t("Connect now"), L.t("Connecting..."), L.t("Request sent"));
        cloud.button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Tweaks.connectCloud(c);
                refresh();
            }
        });
        return cloud.card;
    }

    private View avasCard() {
        avas = new ActionView(L.t("AVAS (external EV sound)"),
                L.t("Turns the car's external EV sound (AVAS) off, the same switch as in the control center"),
                L.t("Turn off now"), L.t("Turning off..."), L.t("Turned off"));
        avas.button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Tweaks.avasOff(c);
                refresh();
            }
        });
        keepAvasSwitch = new Kit.SwitchView(c);
        Kit.Row keep = k.switchRow(L.t("Keep AVAS off"),
                L.t("Turned off at every start-up and whenever the car is switched on."), keepAvasSwitch, true);
        keep.onClick(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !Prefs.keepAvasOff(c);
                Prefs.setKeepAvasOff(c, on);
                if (on) Tweaks.avasStart(c, "switched on", true);
                refresh();
            }
        });
        keep.divider();
        avas.card.addView(keep.view, new LinearLayout.LayoutParams(-1, -2));
        return avas.card;
    }

    private View modemCard() {
        modem = new ActionView(L.t("Modem reboot"),
                L.t("A full modem reboot using AT commands (AT+CFUN=1,1). The mobile connection drops for about "
                        + "a minute."),
                L.t("Reboot now"), L.t("Rebooting..."), L.t("Rebooted"));
        modem.button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.confirmModemReboot();
            }
        });
        autoModemSwitch = new Kit.SwitchView(c);
        Kit.Row auto = k.switchRow(L.t("Reboot automatically"),
                L.t("When the mobile connections APN2 and APN3 are both down and there is no signal."),
                autoModemSwitch, true);
        auto.onClick(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setAutoModemReboot(c, !Prefs.autoModemReboot(c));
                Tweaks.autoModemChanged();
                refresh();
            }
        });
        auto.divider();
        modem.card.addView(auto.view, new LinearLayout.LayoutParams(-1, -2));
        return modem.card;
    }

    private View connectionCard() {
        LinearLayout card = k.card();
        card.setPadding(k.d(16), k.d(14), k.d(16), k.d(14));
        card.addView(k.label(L.t("Connection"), 13, Kit.MUTED, Kit.BOLD));

        // roaming setting
        LinearLayout r1 = k.row();
        r1.setGravity(Gravity.TOP);
        dotRoam = dot();
        r1.addView(dotRoam, dotParams());
        LinearLayout t1 = k.column();
        t1.addView(k.label(L.t("Roaming setting"), 12.5f, Kit.MUTED, Kit.MEDIUM));
        connRoam = k.label("", 16, Kit.TEXT, Kit.BOLD);
        t1.addView(connRoam);
        r1.addView(t1);
        card.addView(r1, k.gap(-1, -2, 0, 12));
        card.addView(thinLine(), k.gap(-1, 1, 0, 12));

        // cellular
        LinearLayout r2 = k.row();
        r2.setGravity(Gravity.TOP);
        dotCell = dot();
        r2.addView(dotCell, dotParams());
        LinearLayout t2 = k.column();
        LinearLayout h2 = k.row();
        h2.addView(k.label(L.t("Cellular"), 12.5f, Kit.MUTED, Kit.MEDIUM));
        connBadge = k.label(L.t("Roaming"), 11.5f, Kit.AMBER, Kit.BOLD);
        connBadge.setPadding(k.d(7), k.d(1), k.d(7), k.d(1));
        connBadge.setBackground(Ui.round(0x29F2B24C, 8 * k.scale, c));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(k.d(8));
        h2.addView(connBadge, blp);
        t2.addView(h2);
        connCell = k.label("", 16, Kit.TEXT, Kit.BOLD);
        t2.addView(connCell);
        connCellNote = k.label("", 12.5f, Kit.MUTED, Kit.MEDIUM);
        t2.addView(connCellNote);
        r2.addView(t2);
        card.addView(r2, k.gap(-1, -2, 0, 12));
        card.addView(thinLine(), k.gap(-1, 1, 0, 12));

        // apps
        LinearLayout r3 = k.row();
        r3.setGravity(Gravity.TOP);
        View dotApps = dot();
        setDot(dotApps, Kit.BLUE);
        r3.addView(dotApps, dotParams());
        LinearLayout t3 = k.column();
        t3.addView(k.label(L.t("Apps use"), 12.5f, Kit.MUTED, Kit.MEDIUM));
        connApps = k.label("", 16, Kit.TEXT, Kit.BOLD);
        t3.addView(connApps);
        r3.addView(t3);
        card.addView(r3, k.gap(-1, -2, 0, 12));
        return card;
    }

    private View thinLine() {
        View v = new View(c);
        v.setBackgroundColor(Kit.LINE);
        return v;
    }

    private View dot() {
        View v = new View(c);
        setDot(v, Kit.DOT_OFF);
        return v;
    }

    private void setDot(View v, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
    }

    private LinearLayout.LayoutParams dotParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(k.d(8), k.d(8));
        lp.topMargin = k.d(7);
        lp.setMarginEnd(k.d(10));
        return lp;
    }

    // ---- the Details sheet -----------------------------------------------------------------------------
    private FrameLayout detailsSheet() {
        FrameLayout overlay = new FrameLayout(c);
        overlay.setBackgroundColor(Kit.SCRIM);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        final FrameLayout self = overlay;
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                self.setVisibility(View.GONE);
            }
        });

        LinearLayout panel = k.column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Kit.SHEET);
        float r = 20 * k.scale * c.getResources().getDisplayMetrics().density;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setPadding(k.d(22), k.d(8), k.d(22), k.d(14));
        panel.setClickable(true); // taps on the sheet itself do not close it

        LinearLayout top = k.row();
        top.addView(k.label(L.t("Details"), 18, Kit.TEXT, Kit.BOLD), new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout close = new FrameLayout(c);
        close.setBackground(Ui.round(Kit.NEUTRAL_BG, 22 * k.scale, c));
        close.addView(new Kit.IconView(c, Kit.IconView.CLOSE, Kit.SOFT),
                new FrameLayout.LayoutParams(k.d(18), k.d(18), Gravity.CENTER));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                self.setVisibility(View.GONE);
            }
        });
        top.addView(close, new LinearLayout.LayoutParams(k.d(44), k.d(44)));
        panel.addView(top, new LinearLayout.LayoutParams(-1, k.d(48)));

        LinearLayout two = new LinearLayout(c);
        two.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout explain = k.column();
        addExplain(explain, L.t("How changes are made"),
                L.t("Changes go through the car's network debugging, so the Vehicle data link must be turned on "
                        + "in Setting."));
        addExplain(explain, L.t("Keep data roaming on"),
                L.t("Applied at start-up, whenever the data link helper starts, and again within a minute if the "
                        + "car switches it off."));
        addExplain(explain, L.t("Keep AVAS off"),
                L.t("Applied at start-up and whenever the ignition comes on, and checked again for 90 seconds, "
                        + "because the car can bring it back on shortly after it starts. At other times it is left "
                        + "alone."));
        addExplain(explain, L.t("Modem reboot"),
                L.t("Restarts the modem with AT commands, as Engineer Mode's AT Command Tool does. The line under "
                        + "the button says how long LTE took to come back."));
        addExplain(explain, L.t("Reboot automatically"),
                L.t("Runs the reboot by itself when APN2 and APN3 have both been down and the modem has had no "
                        + "signal for about 2.5 minutes: at most 3 times in a row, 15 minutes apart."));
        two.addView(explain, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout cmds = k.column();
        cmds.addView(k.label(L.t("Commands"), 12.5f, Kit.MUTED, Kit.BOLD));
        addCommand(cmds, L.t("Data roaming"),
                Tweaks.ROAMING_COMMAND + "\n" + Tweaks.ROAMING_OFF_COMMAND);
        addCommand(cmds, L.t("Connect to BYD cloud"), Tweaks.CLOUD_COMMAND);
        addCommand(cmds, L.t("AVAS (external EV sound)"), Tweaks.AVAS_CALL);
        addCommand(cmds, L.t("Modem reboot"), Tweaks.MODEM_AT);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(k.d(290), -2);
        clp.setMarginStart(k.d(24));
        two.addView(cmds, clp);

        ScrollView sc = new ScrollView(c);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(two, new ViewGroup.LayoutParams(-1, -2));
        int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.62f);
        panel.addView(sc, new LinearLayout.LayoutParams(-1, maxH));

        overlay.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return overlay;
    }

    private void addExplain(LinearLayout into, String head, String text) {
        TextView h = k.label(head, 12.5f, Kit.MUTED, Kit.BOLD);
        TextView b = k.label(text, 13.5f, Kit.SOFT, Kit.MEDIUM);
        b.setPadding(0, k.d(4), 0, 0);
        b.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = k.d(into.getChildCount() == 0 ? 6 : 14);
        into.addView(h, lp);
        into.addView(b);
    }

    private void addCommand(LinearLayout into, String name, String command) {
        TextView n = k.label(name, 12.5f, Kit.MUTED, Kit.MEDIUM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = k.d(10);
        into.addView(n, lp);
        TextView cmd = k.label(command, 12.5f, 0xFFCFEFEA, Kit.REGULAR);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setTextDirection(View.TEXT_DIRECTION_LTR);
        cmd.setPadding(k.d(12), k.d(9), k.d(12), k.d(9));
        cmd.setBackground(Ui.round(Kit.CODE, 10 * k.scale, c));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = k.d(4);
        into.addView(cmd, clp);
    }

    // ---- keeping the page current -----------------------------------------------------------------------
    /** The roaming value to show: the one being set while a change is running, else the live setting. */
    private int shownRoaming() {
        return Tweaks.ROAMING.state == Tweaks.Action.BUSY && Tweaks.roamingTarget >= 0
                ? Tweaks.roamingTarget : Tweaks.roamingState(c);
    }

    private void refresh() {
        int roam = shownRoaming();
        boolean on = roam == 1;
        roamSwitch.setOn(on);
        if (Tweaks.ROAMING.state == Tweaks.Action.FAILED) {
            roamState.setText(Tweaks.ROAMING.detail);
            roamState.setTextColor(Kit.AMBER);
        } else {
            roamState.setText(roam == 1 ? L.t("On") : roam == 0 ? L.t("Off") : L.t("Unknown"));
            roamState.setTextColor(on ? Kit.ACCENT : Kit.MUTED);
        }
        keepRoamSwitch.setOn(Prefs.keepRoaming(c));
        keepAvasSwitch.setOn(Prefs.keepAvasOff(c));
        autoModemSwitch.setOn(Prefs.autoModemReboot(c));
        cloud.update(Tweaks.CLOUD);
        avas.update(Tweaks.AVAS);
        modem.update(Tweaks.MODEM);

        NetStatus.Snapshot n = NetStatus.read(c);
        connRoam.setText(n.roamingSetting == 1 ? L.t("On") : n.roamingSetting == 0 ? L.t("Off") : L.t("Unknown"));
        setDot(dotRoam, n.roamingSetting == 1 ? Kit.ACCENT : Kit.DOT_OFF);
        if (!n.available) {
            connCell.setText(L.t("Network status unavailable"));
            connCellNote.setText("");
            setDot(dotCell, Kit.DOT_OFF);
            connBadge.setVisibility(View.GONE);
        } else {
            connCell.setText(n.cellular ? L.t("Connected") : L.t("Not connected"));
            connCellNote.setText(!n.cellular ? "" : n.validated ? L.t("Internet works") : L.t("No internet yet"));
            connCellNote.setVisibility(n.cellular ? View.VISIBLE : View.GONE);
            setDot(dotCell, !n.cellular ? Kit.DOT_OFF : n.validated ? Kit.ACCENT : Kit.AMBER);
            connBadge.setVisibility(n.cellular && n.roamingNow ? View.VISIBLE : View.GONE);
        }
        connApps.setText(n.appsUse == NetStatus.APPS_WIFI ? L.t("Wi-Fi")
                : n.appsUse == NetStatus.APPS_CELLULAR ? L.t("Cellular")
                : n.appsUse == NetStatus.APPS_ETHERNET ? L.t("Ethernet")
                : n.appsUse == NetStatus.APPS_OTHER ? L.t("Other") : L.t("Nothing"));
    }

    // ---- a card with one button that shows what is going on -----------------------------------------------
    /** A card with a title, a description, a result line and one button that shows what is going on. */
    private final class ActionView {
        final LinearLayout card = k.card();
        final LinearLayout button = k.row();
        private final TextView buttonText;
        private final Kit.IconView check;
        private final TextView detail;
        private final String idle;
        private final String busy;
        private final String done;

        ActionView(String title, String desc, String idle, String busy, String done) {
            this.idle = idle;
            this.busy = busy;
            this.done = done;
            LinearLayout top = k.row();
            top.setPadding(k.d(16), k.d(10), k.d(16), k.d(10));
            LinearLayout text = k.column();
            text.addView(k.label(title, 16, Kit.TEXT, Kit.BOLD));
            TextView dd = k.label(desc, 12.5f, Kit.MUTED, Kit.MEDIUM);
            dd.setPadding(0, k.d(3), 0, 0);
            text.addView(dd);
            detail = k.label("", 12.5f, Kit.MUTED, Kit.BOLD);
            detail.setPadding(0, k.d(4), 0, 0);
            detail.setVisibility(View.GONE);
            text.addView(detail);
            top.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

            button.setGravity(Gravity.CENTER);
            button.setPadding(k.d(16), 0, k.d(16), 0);
            check = new Kit.IconView(c, Kit.IconView.CHECK, Kit.ACCENT);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(k.d(16), k.d(16));
            ilp.setMarginEnd(k.d(6));
            button.addView(check, ilp);
            buttonText = k.label(idle, 14, Kit.ON_ACCENT, Kit.BOLD);
            button.addView(buttonText);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, k.d(44));
            blp.setMarginStart(k.d(12));
            top.addView(button, blp);
            button.setMinimumWidth(k.d(108));
            card.addView(top, new LinearLayout.LayoutParams(-1, -2));
            apply(Tweaks.Action.IDLE);
        }

        void update(Tweaks.Action a) {
            apply(a.state);
            String d = a.detail;
            detail.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
            detail.setText(d);
            detail.setTextColor(a.state == Tweaks.Action.FAILED ? Kit.AMBER : Kit.MUTED);
        }

        private void apply(int state) {
            int bg = state == Tweaks.Action.BUSY ? Kit.BUSY_BG
                    : state == Tweaks.Action.OK ? Kit.ACCENT_SOFT : Kit.ACCENT;
            int fg = state == Tweaks.Action.BUSY ? Kit.OFF_THUMB
                    : state == Tweaks.Action.OK ? Kit.ACCENT : Kit.ON_ACCENT;
            button.setBackground(Ui.round(bg, 22 * k.scale, c));
            buttonText.setTextColor(fg);
            buttonText.setText(state == Tweaks.Action.BUSY ? busy : state == Tweaks.Action.OK ? done : idle);
            check.setVisibility(state == Tweaks.Action.OK ? View.VISIBLE : View.GONE);
            button.setEnabled(state != Tweaks.Action.BUSY);
        }
    }
}
