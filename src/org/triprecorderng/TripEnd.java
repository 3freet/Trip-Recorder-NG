package org.triprecorderng;

/**
 * Decides when a running trip is over. Kept apart from the service so the rules can be tested.
 *
 * An ignition trip (started by the ignition) ends when the ignition reads off. If the ignition cannot
 * be read for a while (the data-link helper is down and only GPS is left) the trip is NOT ended by the
 * standing-still rule, which would cut a parked trip in two every time the helper restarts: it is held
 * open, and only ended when the ignition has been unreadable for HOLD_MS and the car is standing still.
 * A trip that was started by movement (no ignition available) ends when the car has stood still for
 * MOTION_END_MS.
 */
final class TripEnd {
    private TripEnd() {}

    static final long IGNITION_END_GRACE_MS = 5_000;
    static final long MOTION_END_MS = 180_000;
    static final long NO_DATA_END_MS = 600_000;
    /** How long an ignition trip stays open while the ignition cannot be read. */
    static final long HOLD_MS = 600_000;

    enum Reason { NONE, IGNITION_OFF, IGNITION_UNREADABLE, NO_DATA, STOPPED }

    /** All times are on the same clock (elapsed realtime, ms). */
    static Reason decide(boolean ignitionTrip, boolean ignitionKnown, boolean ignitionOn, long now,
                         long lastOnMs, long lastMoveMs, long lastDataMs) {
        if (ignitionTrip && ignitionKnown) {
            return !ignitionOn && now - lastOnMs > IGNITION_END_GRACE_MS ? Reason.IGNITION_OFF : Reason.NONE;
        }
        if (now - lastDataMs > NO_DATA_END_MS) return Reason.NO_DATA;     // lost the speed signal for a long time
        if (ignitionTrip) {
            // ignition unreadable: hold the trip, end only after HOLD_MS of that and a standstill
            boolean held = now - lastOnMs <= HOLD_MS || now - lastMoveMs <= MOTION_END_MS;
            return held ? Reason.NONE : Reason.IGNITION_UNREADABLE;
        }
        return now - lastDataMs < 10_000 && now - lastMoveMs > MOTION_END_MS ? Reason.STOPPED : Reason.NONE;
    }
}
