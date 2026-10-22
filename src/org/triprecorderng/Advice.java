package org.triprecorderng;

import java.util.ArrayList;
import java.util.List;

/**
 * Short driving advice for a trip, picked by simple rules (hard acceleration, hard braking, speed,
 * consumption, long drives).
 */
final class Advice {
    private Advice() {}

    private static String acc1() {
        return L.t("You often accelerate hard. Pressing the pedal gently and steadily makes the drive "
            + "smoother and safer.");
    }
    private static String acc2() {
        return L.t("Repeated hard acceleration wears parts faster and shortens the life of the car. "
            + "Build up speed gradually.");
    }
    private static String dec1() {
        return L.t("In busy traffic or at high speed, keep your speed in check and leave a generous "
            + "gap to the car ahead.");
    }
    private static String dec2() {
        return L.t("Braking hard again and again wears the tires and brakes and shakes the suspension "
            + "and drivetrain. Keep your distance and ease off the pedal early.");
    }
    private static String speed1() {
        return L.t("Driving fast raises the risk of a crash. Stay within the posted speed limits.");
    }
    private static String speed2() {
        return L.t("The faster you go, the narrower your view and the less time you have to react to "
            + "something unexpected ahead. Keep your speed under control.");
    }
    private static String energy1() {
        return L.t("To use less energy: service the car regularly, avoid rush hour where you can, "
            + "carry less load and keep the tires at the right pressure.");
    }
    private static String energy2() {
        return L.t("Steady driving at a moderate speed within the limit uses the least; going much "
            + "faster or much slower costs more energy.");
    }
    private static String fatigue1() {
        return L.t("Plan breaks on long drives. If you feel tired, stop somewhere safe and rest; "
            + "pushing on only adds to the tiredness and the risk of an accident.");
    }
    private static String excellent() {
        return L.t("Great driving! Keep it up.");
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
        if (t.drivingSec() >= 2 * 3600) out.add(fatigue1());
        if (out.isEmpty()) out.add(excellent());
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }
}
