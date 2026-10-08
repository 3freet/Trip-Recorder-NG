package org.triprecorderng;

import java.util.ArrayList;
import java.util.List;

/**
 * Short driving advice for a trip, picked by simple rules from the stock app's own advice texts
 * (hard acceleration, hard braking, speed, consumption, long drives).
 */
final class Advice {
    private Advice() {}

    private static String acc1() {
        return L.t("You have a high frequency of sudden accelerations. It is recommended "
            + "to stabilize the accelerator pedal, accelerate smoothly.");
    }
    private static String acc2() {
        return L.t("Frequent rapid acceleration will accelerate the wear and aging of parts "
            + "and shorten the service life of the vehicle. We suggest you accelerate smoothly.");
    }
    private static String dec1() {
        return L.t("When there are many vehicles on the road or when driving at high speeds, "
            + "it is recommended to control your speed reasonably and maintain a safe distance from the vehicle "
            + "in front.");
    }
    private static String dec2() {
        return L.t("Frequent sudden braking can accelerate wear on tires, braking systems, "
            + "and cause shocks to the suspension and transmission systems. We recommend maintaining a safe "
            + "distance and decelerate in advance.");
    }
    private static String speed1() {
        return L.t("Driving too fast, easy to cause traffic accidents, it is recommended "
            + "to follow the prescribed speed to ensure safety.");
    }
    private static String speed2() {
        return L.t("At high speeds, your field of vision narrows, making it difficult to "
            + "properly handle sudden abnormalities ahead, which can easily lead to accidents. We recommend "
            + "controlling your speed reasonably.");
    }
    private static String energy1() {
        return L.t("We suggest regular maintenance, avoiding peak traffic times, reducing "
            + "vehicle weight, and maintaining normal tire pressure to reduce energy consumption from all aspects.");
    }
    private static String energy2() {
        return L.t("We recommend driving at an economical speed within the speed limit, as "
            + "driving too fast or too slow can increase fuel consumption.");
    }
    private static String fatigue1() {
        return L.t("It is recommended to rationalize the time of continuous driving, and "
            + "when you feel tired, stop at a safe place to rest as soon as possible; the longer you delay, the "
            + "more fatigue you will feel and increasing the risk of accidents.");
    }
    private static String excellent() {
        return L.t("Your driving habits are excellent, please continue to keep them up!");
    }

    /** Up to three pieces of advice, most important first; empty for a trip too short to judge. */
    static List<String> forTrip(Trip t) {
        List<String> out = new ArrayList<>();
        if (t.distanceKm < 2) return out;
        double per100 = t.distanceKm / 100.0;
        int acc = t.hardAccel, dec = t.hardBrake;
        if (acc >= 2 || acc / per100 >= 4) {
            out.add(acc1());
            if (acc >= 4) out.add(acc2());
        }
        if (dec >= 2 || dec / per100 >= 4) {
            out.add(dec1());
            if (dec >= 4) out.add(dec2());
        }
        if (t.distanceKm >= 20 && t.avgSpeedKmh() >= 110) {
            out.add(speed1());
            if (t.avgSpeedKmh() >= 125) out.add(speed2());
        }
        double kwh = t.energyPer100Km(), litres = t.fuelPer100Km();
        if ((!Double.isNaN(kwh) && kwh >= 22) || (!Double.isNaN(litres) && litres >= 8)) {
            out.add(energy1());
            out.add(energy2());
        }
        if (t.durationSec() >= 2 * 3600) out.add(fatigue1());
        if (out.isEmpty()) out.add(excellent());
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }
}
