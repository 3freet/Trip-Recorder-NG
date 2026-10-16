package org.triprecorderng;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The Driving Behavior Q&A screen: questions and answers as cards in two columns. */
final class QaPage {
    private static final float SCALE = 1.2f;

    private QaPage() {}

    static View build(Context c) {
        Kit k = new Kit(c, SCALE);
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout cols = new LinearLayout(c);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        sv.addView(cols, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout left = k.column();
        LinearLayout right = k.column();
        cols.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.setMarginStart(k.d(10));
        cols.addView(right, rlp);
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
                {L.t("Why does a hybrid trip show two consumption numbers?"),
                        L.t("The car's energy counter is net: it also goes down when the engine charges the "
                                + "battery, so one kWh/100km figure for a mixed trip is misleading. On a hybrid trip "
                                + "the app splits it by the car's own electric and hybrid kilometres. Electric: kWh "
                                + "per 100 km driven on electricity, with the energy the engine put into the battery "
                                + "added back. Hybrid: litres per 100 km driven with the engine running; it includes "
                                + "the fuel that charged the battery. Both are estimates; the totals (kWh, litres, "
                                + "cost) are exact, and Whole trip per 100 km is the plain total divided by the "
                                + "distance. In Statistics, Consumption is the car's net figure for every trip, "
                                + "Electric leaves out the trips that burned fuel, Fuel is the litres burned and Fuel "
                                + "economy is litres per 100 km of hybrid driving.")},
                {L.t("Why is a parked car not counted in kWh/100km and the drive time?"),
                        L.t("A trip lasts while the ignition is on, so a car left parked with the air "
                                + "conditioning on, or with the cable in, stays in the same trip for hours. "
                                + "Standing still for 5 minutes or more counts as parked: the energy used then "
                                + "(shown as While parked in the trip details) and the time are left out of "
                                + "kWh/100km, the drive time and the longest drive. The totals, the battery used "
                                + "and the cost still include everything the trip used. Shorter stops, like "
                                + "traffic lights, are part of the drive. Trips recorded before this was added "
                                + "are worked out again from their saved samples; a trip with no saved samples "
                                + "keeps its whole figures.")},
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

        // the cards are balanced by length, so both columns end near each other
        int leftLen = 0;
        int rightLen = 0;
        for (String[] q : qa) {
            LinearLayout card = k.card();
            card.setPaddingRelative(k.d(16), k.d(14), k.d(16), k.d(14));
            TextView h = k.label(q[0], 15.5f, Kit.TEXT, Kit.BOLD);
            card.addView(h);
            TextView a = k.label(q[1], 13.5f, Kit.MUTED, Kit.MEDIUM);
            a.setLineSpacing(k.d(2), 1f);
            a.setPadding(0, k.d(6), 0, 0);
            card.addView(a);
            int len = q[0].length() + q[1].length();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (leftLen <= rightLen) {
                lp.topMargin = left.getChildCount() == 0 ? 0 : k.d(10);
                left.addView(card, lp);
                leftLen += len;
            } else {
                lp.topMargin = right.getChildCount() == 0 ? 0 : k.d(10);
                right.addView(card, lp);
                rightLen += len;
            }
        }
        LinearLayout.LayoutParams bottom = new LinearLayout.LayoutParams(-1, k.d(10));
        left.addView(new View(c), bottom);
        return sv;
    }
}
