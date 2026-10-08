package org.triprecorderng;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local SQLite storage: one row per trip plus an optional GPS track per trip. */
final class TripDb extends SQLiteOpenHelper {
    private static final String NAME = "trips.db";
    private static final int VERSION = 6;

    private static TripDb instance;
    private final Context appCtx;

    static synchronized TripDb get(Context ctx) {
        if (instance == null) instance = new TripDb(ctx.getApplicationContext());
        return instance;
    }

    private TripDb(Context ctx) {
        super(ctx, NAME, null, VERSION);
        appCtx = ctx;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips ("
                + "id TEXT PRIMARY KEY, start_ms INTEGER, end_ms INTEGER, last_update_ms INTEGER,"
                + "ended INTEGER, mode TEXT, distance_km REAL, odo_start REAL, odo_end REAL,"
                + "moving_s REAL, idle_s REAL, max_speed REAL, soc_start REAL, soc_end REAL,"
                + "elec_start REAL, elec_end REAL, hard_accel INTEGER, hard_brake INTEGER,"
                + "start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL,"
                + "fuel_start REAL, fuel_end REAL, ev_start REAL, ev_end REAL, hev_start REAL, hev_end REAL,"
                + "speed_km REAL, journey_acc REAL, journey_last REAL)");
        db.execSQL("CREATE TABLE points ("
                + "trip_id TEXT, ts INTEGER, lat REAL, lon REAL, alt REAL, gps_speed REAL,"
                + "car_speed REAL, bearing REAL, soc REAL, elec REAL)");
        db.execSQL("CREATE INDEX points_trip ON points(trip_id, ts)");
        createEvents(db);
        createCharges(db);
    }

    private static void createCharges(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE charges (id TEXT PRIMARY KEY, start_ms INTEGER, end_ms INTEGER,"
                + "last_update_ms INTEGER, ended INTEGER, soc_start REAL, soc_end REAL, kwh REAL,"
                + "max_power REAL, gun INTEGER, lat REAL, lon REAL, partial INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE charge_points (charge_id TEXT, ts INTEGER, soc REAL, power REAL)");
        db.execSQL("CREATE INDEX charge_points_id ON charge_points(charge_id, ts)");
    }

    private static void createEvents(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (trip_id TEXT, ts INTEGER, type INTEGER, lat REAL, lon REAL,"
                + "value REAL)");
        db.execSQL("CREATE INDEX events_trip ON events(trip_id, ts)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // v2: battery and energy along the track, and the positions of hard accel/brake events
            db.execSQL("ALTER TABLE points ADD COLUMN soc REAL");
            db.execSQL("ALTER TABLE points ADD COLUMN elec REAL");
            createEvents(db);
        }
        if (oldV < 3) {
            createCharges(db);
        } else if (oldV < 4) {
            db.execSQL("ALTER TABLE charges ADD COLUMN partial INTEGER DEFAULT 0");
        }
        if (oldV < 6) {
            // v6: speed-integrated distance kept apart from the shown distance, and the car's journey counter
            for (String col : new String[]{"speed_km", "journey_acc", "journey_last"}) {
                db.execSQL("ALTER TABLE trips ADD COLUMN " + col + " REAL");
            }
        }
        if (oldV < 5) {
            // v5: fuel and EV/HEV mileage counters per trip (plug-in hybrid)
            for (String col : new String[]{"fuel_start", "fuel_end", "ev_start", "ev_end", "hev_start", "hev_end"}) {
                db.execSQL("ALTER TABLE trips ADD COLUMN " + col + " REAL");
            }
        }
    }

    private static void put(ContentValues cv, String key, double v) {
        if (Double.isNaN(v)) cv.putNull(key); else cv.put(key, v);
    }

    private static double dbl(Cursor c, int i) {
        return c.isNull(i) ? Double.NaN : c.getDouble(i);
    }

    synchronized void upsert(Trip t) {
        ContentValues cv = new ContentValues();
        cv.put("id", t.id);
        cv.put("start_ms", t.startMs);
        cv.put("end_ms", t.endMs);
        cv.put("last_update_ms", t.lastUpdateMs);
        cv.put("ended", t.ended ? 1 : 0);
        cv.put("mode", t.mode);
        cv.put("distance_km", t.distanceKm);
        put(cv, "odo_start", t.odoStartKm);
        put(cv, "odo_end", t.odoEndKm);
        cv.put("moving_s", t.movingSec);
        cv.put("idle_s", t.idleSec);
        cv.put("max_speed", t.maxSpeedKmh);
        put(cv, "soc_start", t.socStart);
        put(cv, "soc_end", t.socEnd);
        put(cv, "elec_start", t.elecStart);
        put(cv, "elec_end", t.elecEnd);
        cv.put("hard_accel", t.hardAccel);
        cv.put("hard_brake", t.hardBrake);
        put(cv, "start_lat", t.startLat);
        put(cv, "start_lon", t.startLon);
        put(cv, "end_lat", t.endLat);
        put(cv, "end_lon", t.endLon);
        put(cv, "fuel_start", t.fuelStart);
        put(cv, "fuel_end", t.fuelEnd);
        put(cv, "ev_start", t.evStart);
        put(cv, "ev_end", t.evEnd);
        put(cv, "hev_start", t.hevStart);
        put(cv, "hev_end", t.hevEnd);
        put(cv, "speed_km", t.speedKm);
        put(cv, "journey_acc", t.journeyAcc);
        put(cv, "journey_last", t.journeyLast);
        getWritableDatabase().insertWithOnConflict("trips", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void addPoints(String tripId, List<double[]> pts) {
        if (pts.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (double[] p : pts) {
                ContentValues cv = new ContentValues();
                cv.put("trip_id", tripId);
                cv.put("ts", (long) p[0]);
                cv.put("lat", p[1]);
                cv.put("lon", p[2]);
                cv.put("alt", p[3]);
                cv.put("gps_speed", p[4]);
                put(cv, "car_speed", p[5]);
                cv.put("bearing", p[6]);
                put(cv, "soc", p.length > 7 ? p[7] : Double.NaN);
                put(cv, "elec", p.length > 8 ? p[8] : Double.NaN);
                db.insert("points", null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static Trip read(Cursor c) {
        Trip t = new Trip();
        t.id = c.getString(0);
        t.startMs = c.getLong(1);
        t.endMs = c.getLong(2);
        t.lastUpdateMs = c.getLong(3);
        t.ended = c.getInt(4) == 1;
        t.mode = c.getString(5);
        t.distanceKm = c.getDouble(6);
        t.odoStartKm = dbl(c, 7);
        t.odoEndKm = dbl(c, 8);
        t.movingSec = c.getDouble(9);
        t.idleSec = c.getDouble(10);
        t.maxSpeedKmh = c.getDouble(11);
        t.socStart = dbl(c, 12);
        t.socEnd = dbl(c, 13);
        t.elecStart = dbl(c, 14);
        t.elecEnd = dbl(c, 15);
        t.hardAccel = c.getInt(16);
        t.hardBrake = c.getInt(17);
        t.startLat = dbl(c, 18);
        t.startLon = dbl(c, 19);
        t.endLat = dbl(c, 20);
        t.endLon = dbl(c, 21);
        t.fuelStart = dbl(c, 22);
        t.fuelEnd = dbl(c, 23);
        t.evStart = dbl(c, 24);
        t.evEnd = dbl(c, 25);
        t.hevStart = dbl(c, 26);
        t.hevEnd = dbl(c, 27);
        t.speedKm = c.isNull(28) ? t.distanceKm : c.getDouble(28);   // older trips: their distance was the speed integral
        t.journeyAcc = dbl(c, 29);
        t.journeyLast = dbl(c, 30);
        return t;
    }

    private static final String COLS = "id,start_ms,end_ms,last_update_ms,ended,mode,distance_km,"
            + "odo_start,odo_end,moving_s,idle_s,max_speed,soc_start,soc_end,elec_start,elec_end,"
            + "hard_accel,hard_brake,start_lat,start_lon,end_lat,end_lon,fuel_start,fuel_end,"
            + "ev_start,ev_end,hev_start,hev_end,speed_km,journey_acc,journey_last";

    synchronized List<Trip> all() {
        List<Trip> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM trips ORDER BY start_ms DESC", null);
        try {
            while (c.moveToNext()) out.add(read(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** The most recent trip that was never closed (service killed or car rebooted mid-trip). */
    synchronized Trip lastOpen() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM trips WHERE ended=0 ORDER BY start_ms DESC LIMIT 1", null);
        try {
            return c.moveToFirst() ? read(c) : null;
        } finally {
            c.close();
        }
    }

    synchronized List<double[]> points(String tripId) {
        List<double[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,lat,lon,alt,gps_speed,car_speed,bearing,soc,elec FROM points "
                        + "WHERE trip_id=? ORDER BY ts", new String[]{tripId});
        try {
            while (c.moveToNext()) {
                out.add(new double[]{c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3),
                        c.getDouble(4), dbl(c, 5), c.getDouble(6), dbl(c, 7), dbl(c, 8)});
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Hard acceleration / braking events: {ts, type (0 accel, 1 brake), lat, lon, m/s2}. */
    synchronized void addEvents(String tripId, List<double[]> events) {
        if (events.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (double[] e : events) {
                ContentValues cv = new ContentValues();
                cv.put("trip_id", tripId);
                cv.put("ts", (long) e[0]);
                cv.put("type", (int) e[1]);
                put(cv, "lat", e[2]);
                put(cv, "lon", e[3]);
                put(cv, "value", e[4]);
                db.insert("events", null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized List<double[]> events(String tripId) {
        List<double[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,type,lat,lon,value FROM events WHERE trip_id=? ORDER BY ts",
                new String[]{tripId});
        try {
            while (c.moveToNext()) {
                out.add(new double[]{c.getLong(0), c.getInt(1), dbl(c, 2), dbl(c, 3), dbl(c, 4)});
            }
        } finally {
            c.close();
        }
        return out;
    }

    synchronized boolean exists(String tripId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM trips WHERE id=? LIMIT 1",
                new String[]{tripId});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    synchronized void delete(String tripId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("points", "trip_id=?", new String[]{tripId});
        db.delete("events", "trip_id=?", new String[]{tripId});
        db.delete("trips", "id=?", new String[]{tripId});
        Backup.remove(appCtx, tripId);
    }

    // ---- charging sessions -----------------------------------------------------------------
    synchronized void upsertCharge(Charge c) {
        ContentValues cv = new ContentValues();
        cv.put("id", c.id);
        cv.put("start_ms", c.startMs);
        cv.put("end_ms", c.endMs);
        cv.put("last_update_ms", c.lastUpdateMs);
        cv.put("ended", c.ended ? 1 : 0);
        put(cv, "soc_start", c.socStart);
        put(cv, "soc_end", c.socEnd);
        put(cv, "kwh", c.kwhSignal);
        put(cv, "max_power", c.maxPowerKw);
        cv.put("gun", c.gun);
        put(cv, "lat", c.lat);
        put(cv, "lon", c.lon);
        cv.put("partial", c.windowOnly ? 2 : c.partial ? 1 : 0);
        getWritableDatabase().insertWithOnConflict("charges", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Samples of a session: {ts, soc, power kW}. */
    synchronized void addChargePoints(String chargeId, List<double[]> pts) {
        if (pts.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (double[] p : pts) {
                ContentValues cv = new ContentValues();
                cv.put("charge_id", chargeId);
                cv.put("ts", (long) p[0]);
                put(cv, "soc", p[1]);
                put(cv, "power", p[2]);
                db.insert("charge_points", null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static final String CHARGE_COLS = "id,start_ms,end_ms,last_update_ms,ended,soc_start,"
            + "soc_end,kwh,max_power,gun,lat,lon,partial";

    private static Charge readCharge(Cursor c) {
        Charge x = new Charge();
        x.id = c.getString(0);
        x.startMs = c.getLong(1);
        x.endMs = c.getLong(2);
        x.lastUpdateMs = c.getLong(3);
        x.ended = c.getInt(4) == 1;
        x.socStart = dbl(c, 5);
        x.socEnd = dbl(c, 6);
        x.kwhSignal = dbl(c, 7);
        x.maxPowerKw = dbl(c, 8);
        x.gun = c.getInt(9);
        x.lat = dbl(c, 10);
        x.lon = dbl(c, 11);
        int part = c.getInt(12);
        x.partial = part >= 1;
        x.windowOnly = part == 2;
        return x;
    }

    synchronized List<Charge> charges() {
        List<Charge> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + CHARGE_COLS + " FROM charges ORDER BY start_ms DESC", null);
        try {
            while (c.moveToNext()) out.add(readCharge(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** The most recent finished charging session, or null. */
    synchronized Charge latestEndedCharge() {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + CHARGE_COLS
                + " FROM charges WHERE ended=1 ORDER BY end_ms DESC LIMIT 1", null);
        try {
            return c.moveToFirst() ? readCharge(c) : null;
        } finally {
            c.close();
        }
    }

    /** True if a trip of at least 300 m started strictly between the two times. */
    synchronized boolean hasTripBetween(long fromMs, long toMs) {
        if (toMs <= fromMs) return false;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM trips WHERE start_ms>? AND start_ms<? AND distance_km>=0.3 LIMIT 1",
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** Hands the samples of one session over to another (when two sessions are joined). */
    synchronized void moveChargePoints(String fromId, String toId) {
        ContentValues cv = new ContentValues();
        cv.put("charge_id", toId);
        getWritableDatabase().update("charge_points", cv, "charge_id=?", new String[]{fromId});
    }

    synchronized Charge lastOpenCharge() {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + CHARGE_COLS
                + " FROM charges WHERE ended=0 ORDER BY start_ms DESC LIMIT 1", null);
        try {
            return c.moveToFirst() ? readCharge(c) : null;
        } finally {
            c.close();
        }
    }

    synchronized List<double[]> chargePoints(String chargeId) {
        List<double[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,soc,power FROM charge_points WHERE charge_id=? ORDER BY ts",
                new String[]{chargeId});
        try {
            while (c.moveToNext()) out.add(new double[]{c.getLong(0), dbl(c, 1), dbl(c, 2)});
        } finally {
            c.close();
        }
        return out;
    }

    synchronized boolean chargeExists(String chargeId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM charges WHERE id=? LIMIT 1",
                new String[]{chargeId});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    synchronized void deleteCharge(String chargeId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("charge_points", "charge_id=?", new String[]{chargeId});
        db.delete("charges", "id=?", new String[]{chargeId});
        Backup.remove(appCtx, chargeId);
    }
}
