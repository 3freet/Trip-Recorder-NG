package org.triprecorderng;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Keeps one JSON file per finished trip and charging session (summary, GPS/battery/energy track, hard
 * events) in Documents/TripRecorderNG/backup, so they survive an uninstall or a cleared app. Restore adds
 * the ones that are missing from the database; it never overwrites or removes existing ones.
 *
 * Earlier versions kept the files in Downloads/TripRecorderNG/backup and, before the rename, in
 * Downloads/TripRecorder/backup. Those folders are still read (restore, counts) and cleaned when an item
 * is deleted, but never written to; migrateOldFolders() moves their files into the current folder.
 */
final class Backup {
    private static final String SUFFIX = ".json";
    private static final int FORMAT = 1;

    private Backup() {}

    static final class Status {
        File dir;
        boolean publicFolder;
        int files;
        long newestMs;
    }

    static final class RestoreResult {
        int restoredCharges;
        int restored;
        int alreadyThere;
        int failed;
    }

    /** The backup folders of earlier versions (read, never written): Downloads/TripRecorderNG and Downloads/TripRecorder. */
    private static List<File> oldFolders() {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        List<File> l = new ArrayList<>();
        l.add(new File(new File(dl, "TripRecorderNG"), "backup"));
        l.add(new File(new File(dl, "TripRecorder"), "backup"));
        return l;
    }

    /** Every folder that may hold backup files, the current one first. */
    private static List<File> dirs(Context ctx) {
        List<File> l = new ArrayList<>();
        l.add(dir(ctx));
        for (File f : oldFolders()) if (f.isDirectory() && !f.equals(l.get(0))) l.add(f);
        return l;
    }

    /** Folder for the backup files: Documents/TripRecorderNG/backup, or the app's own folder if that fails. */
    static File dir(Context ctx) {
        File d = new File(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "TripRecorderNG"), "backup");
        if ((d.isDirectory() || d.mkdirs()) && d.canWrite()) return d;
        File base = ctx.getExternalFilesDir(null);
        File f = new File(base != null ? base : ctx.getFilesDir(), "backup");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    private static boolean isPublic(File d) {
        return d.getPath().contains("/Documents/") || d.getPath().contains("/Download");
    }

    private static void copyFile(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            FileOutputStream os = new FileOutputStream(to);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.getFD().sync();
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * Moves the backup files (and CSV/GPX exports) from the earlier Downloads folders into the current
     * folders. A file is deleted from the old place only after its copy has the same size; one the app
     * is not allowed to delete (made by an older install) is left, and read from there as before.
     * Returns how many files were moved.
     */
    static int migrateOldFolders(Context ctx) {
        int moved = 0;
        try {
            File dest = dir(ctx);
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            for (File old : oldFolders()) {
                File[] list = old.listFiles();
                if (list == null || old.equals(dest)) continue;
                for (File f : list) {
                    if (!f.isFile() || !f.getName().endsWith(SUFFIX)) continue;
                    File to = new File(dest, f.getName());
                    if (to.exists()) {
                        if (f.delete()) moved++;                // the current folder's copy wins: drop the duplicate
                    } else {
                        copyFile(f, to);
                        if (to.length() == f.length() && f.delete()) moved++;
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                old.delete();                                   // only succeeds when empty
            }
            // exports sit next to the backup folder: Downloads/TripRecorderNG/{trips.csv, trip-*.gpx}
            File oldExports = new File(dl, "TripRecorderNG");
            File newExports = Exporter.dir(ctx);
            File[] ex = oldExports.listFiles();
            if (ex != null && !oldExports.equals(newExports)) {
                for (File f : ex) {
                    String n = f.getName();
                    if (!f.isFile() || !(n.endsWith(".csv") || n.endsWith(".gpx"))) continue;
                    File to = new File(newExports, n);
                    if (to.exists()) {
                        if (f.delete()) moved++;
                    } else {
                        copyFile(f, to);
                        if (to.length() == f.length() && f.delete()) moved++;
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                oldExports.delete();
            }
        } catch (Throwable t) {
            Diag.log("backup: moving files from the old folders failed", t);
        }
        if (moved > 0) Diag.log("backup: moved " + moved + " file(s) from the old Downloads folders to " + dir(ctx).getPath());
        return moved;
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String fileName(Trip t) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(t.startMs));
        return "trip-" + stamp + "-" + shortId(t.id) + SUFFIX;
    }

    private static String chargeFileName(Charge c) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(c.startMs));
        return "charge-" + stamp + "-" + shortId(c.id) + SUFFIX;
    }

    private static File find(File dir, String id) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        String tail = "-" + shortId(id) + SUFFIX;
        for (File f : list) if (f.getName().endsWith(tail)) return f;
        return null;
    }

    static Status status(Context ctx) {
        Status s = new Status();
        s.dir = dir(ctx);
        s.publicFolder = isPublic(s.dir);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (File d : dirs(ctx)) {
            File[] list = d.listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (!f.getName().endsWith(SUFFIX)) continue;
                if (seen.add(f.getName())) s.files++;      // the same file in both folders counts once
                s.newestMs = Math.max(s.newestMs, f.lastModified());
            }
        }
        return s;
    }

    /** Removes the backup of a deleted trip, so a later restore does not bring it back. */
    static void remove(Context ctx, String tripId) {
        try {
            for (File d : dirs(ctx)) {
                File f = find(d, tripId);
                if (f != null && !f.delete()) Diag.log("backup: could not delete " + f.getName());
            }
        } catch (Throwable t) {
            Diag.log("backup: remove failed", t);
        }
    }

    /** Writes the backup of one finished trip (replaces an older copy). Returns false on failure. */
    static boolean write(Context ctx, Trip t) {
        if (!t.ended || "demo".equals(t.mode)) return true;
        try {
            TripDb db = TripDb.get(ctx);
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("trip", tripToJson(t));
            JSONArray pts = new JSONArray();
            for (double[] p : db.points(t.id)) pts.put(arr(p));
            root.put("points", pts);
            JSONArray ev = new JSONArray();
            for (double[] e : db.events(t.id)) ev.put(arr(e));
            root.put("events", ev);

            File dir = dir(ctx);
            File old = find(dir, t.id);
            File out = new File(dir, fileName(t));
            File tmp = new File(dir, out.getName() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(root.toString().getBytes("UTF-8"));
                os.getFD().sync();
            } finally {
                os.close();
            }
            if (!tmp.renameTo(out)) throw new IOException("rename failed");
            if (old != null && !old.equals(out)) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            return true;
        } catch (Throwable e) {
            Diag.log("backup: write failed for " + t.id, e);
            return false;
        }
    }

    /** Writes the backup of one finished charging session. Returns false on failure. */
    static boolean writeCharge(Context ctx, Charge c) {
        if (!c.ended || c.id.startsWith("demo-")) return true;
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("start_ms", c.startMs);
            o.put("end_ms", c.endMs);
            o.put("last_update_ms", c.lastUpdateMs);
            o.put("ended", c.ended);
            o.put("soc_start", num(c.socStart));
            o.put("soc_end", num(c.socEnd));
            o.put("kwh", num(c.kwhSignal));
            o.put("max_power", num(c.maxPowerKw));
            o.put("gun", c.gun);
            o.put("lat", num(c.lat));
            o.put("lon", num(c.lon));
            o.put("partial", c.partial);
            o.put("window_only", c.windowOnly);
            root.put("charge", o);
            JSONArray pts = new JSONArray();
            for (double[] p : TripDb.get(ctx).chargePoints(c.id)) pts.put(arr(p));
            root.put("points", pts);
            File dir = dir(ctx);
            File old = find(dir, c.id);
            File out = new File(dir, chargeFileName(c));
            File tmp = new File(dir, out.getName() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(root.toString().getBytes("UTF-8"));
                os.getFD().sync();
            } finally {
                os.close();
            }
            if (!tmp.renameTo(out)) throw new IOException("rename failed");
            if (old != null && !old.equals(out)) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            return true;
        } catch (Throwable e) {
            Diag.log("backup: charge write failed for " + c.id, e);
            return false;
        }
    }

    /** Backs up every finished trip that has no file yet. Returns how many were written. */
    static int writeMissing(Context ctx) {
        int n = 0;
        try {
            File dir = dir(ctx);
            for (Trip t : TripDb.get(ctx).all()) {
                if (!t.ended || "demo".equals(t.mode)) continue;
                if (find(dir, t.id) != null) continue;
                if (write(ctx, t)) n++;
            }
            for (Charge c : TripDb.get(ctx).charges()) {
                if (!c.ended || c.id.startsWith("demo-") || find(dir, c.id) != null) continue;
                if (writeCharge(ctx, c)) n++;
            }
        } catch (Throwable e) {
            Diag.log("backup: catch-up failed", e);
        }
        if (n > 0) Diag.log("backup: wrote " + n + " trip file(s) to " + dir(ctx).getPath());
        return n;
    }

    /** Adds the trips from the backup folder that are not in the database yet. */
    static RestoreResult restore(Context ctx) {
        RestoreResult r = new RestoreResult();
        List<File> files = new ArrayList<>();
        for (File d : dirs(ctx)) {
            File[] l = d.listFiles();
            if (l != null) files.addAll(java.util.Arrays.asList(l));
        }
        File[] list = files.toArray(new File[0]);
        TripDb db = TripDb.get(ctx);
        for (File f : list) {
            if (!f.getName().endsWith(SUFFIX)) continue;
            try {
                JSONObject root = new JSONObject(new String(readAll(f), "UTF-8"));
                if (root.has("charge")) {
                    if (restoreCharge(db, root)) r.restoredCharges++; else r.alreadyThere++;
                    continue;
                }
                Trip t = tripFromJson(root.getJSONObject("trip"));
                if (t.id == null || t.id.isEmpty()) throw new IOException("no trip id");
                if (db.exists(t.id)) {
                    r.alreadyThere++;
                    continue;
                }
                List<double[]> pts = new ArrayList<>();
                JSONArray pa = root.optJSONArray("points");
                if (pa != null) for (int i = 0; i < pa.length(); i++) pts.add(unarr(pa.getJSONArray(i)));
                List<double[]> ev = new ArrayList<>();
                JSONArray ea = root.optJSONArray("events");
                if (ea != null) for (int i = 0; i < ea.length(); i++) ev.add(unarr(ea.getJSONArray(i)));
                db.upsert(t);
                db.addPoints(t.id, pts);
                db.addEvents(t.id, ev);
                r.restored++;
            } catch (Throwable e) {
                r.failed++;
                Diag.log("backup: could not restore " + f.getName(), e);
            }
        }
        Diag.log("backup: restore -> " + r.restored + " trips + " + r.restoredCharges
                + " charges restored, " + r.alreadyThere
                + " already there, " + r.failed + " failed");
        return r;
    }

    private static boolean restoreCharge(TripDb db, JSONObject root) throws org.json.JSONException {
        JSONObject o = root.getJSONObject("charge");
        Charge c = new Charge();
        c.id = o.optString("id", "");
        if (c.id.isEmpty()) throw new org.json.JSONException("no charge id");
        if (db.chargeExists(c.id)) return false;
        c.startMs = o.optLong("start_ms");
        c.endMs = o.optLong("end_ms");
        c.lastUpdateMs = o.optLong("last_update_ms", c.endMs);
        c.ended = o.optBoolean("ended", true);
        c.socStart = dbl(o, "soc_start");
        c.socEnd = dbl(o, "soc_end");
        c.kwhSignal = dbl(o, "kwh");
        c.maxPowerKw = dbl(o, "max_power");
        c.gun = o.optInt("gun");
        c.lat = dbl(o, "lat");
        c.lon = dbl(o, "lon");
        c.partial = o.optBoolean("partial", false);
        c.windowOnly = o.optBoolean("window_only", false);
        List<double[]> pts = new ArrayList<>();
        JSONArray pa = root.optJSONArray("points");
        if (pa != null) for (int i = 0; i < pa.length(); i++) pts.add(unarr(pa.getJSONArray(i)));
        db.upsertCharge(c);
        db.addChargePoints(c.id, pts);
        return true;
    }

    // ---- JSON helpers ----------------------------------------------------------------------
    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static Object num(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? JSONObject.NULL : (Object) Double.valueOf(v);
    }

    private static double dbl(JSONObject o, String key) {
        return o.isNull(key) ? Double.NaN : o.optDouble(key, Double.NaN);
    }

    private static JSONArray arr(double[] p) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (double v : p) a.put(num(v));
        return a;
    }

    private static double[] unarr(JSONArray a) {
        double[] p = new double[a.length()];
        for (int i = 0; i < p.length; i++) p[i] = a.isNull(i) ? Double.NaN : a.optDouble(i, Double.NaN);
        return p;
    }

    private static JSONObject tripToJson(Trip t) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("id", t.id);
        o.put("start_ms", t.startMs);
        o.put("end_ms", t.endMs);
        o.put("last_update_ms", t.lastUpdateMs);
        o.put("ended", t.ended);
        o.put("mode", t.mode);
        o.put("distance_km", num(t.distanceKm));
        o.put("odo_start", num(t.odoStartKm));
        o.put("odo_end", num(t.odoEndKm));
        o.put("moving_s", num(t.movingSec));
        o.put("idle_s", num(t.idleSec));
        o.put("max_speed", num(t.maxSpeedKmh));
        o.put("soc_start", num(t.socStart));
        o.put("soc_end", num(t.socEnd));
        o.put("elec_start", num(t.elecStart));
        o.put("elec_end", num(t.elecEnd));
        o.put("hard_accel", t.hardAccel);
        o.put("hard_brake", t.hardBrake);
        o.put("start_lat", num(t.startLat));
        o.put("start_lon", num(t.startLon));
        o.put("end_lat", num(t.endLat));
        o.put("end_lon", num(t.endLon));
        o.put("fuel_start", num(t.fuelStart));
        o.put("fuel_end", num(t.fuelEnd));
        o.put("ev_start", num(t.evStart));
        o.put("ev_end", num(t.evEnd));
        o.put("hev_start", num(t.hevStart));
        o.put("hev_end", num(t.hevEnd));
        return o;
    }

    private static Trip tripFromJson(JSONObject o) {
        Trip t = new Trip();
        t.id = o.optString("id", "");
        t.startMs = o.optLong("start_ms");
        t.endMs = o.optLong("end_ms");
        t.lastUpdateMs = o.optLong("last_update_ms", t.endMs);
        t.ended = o.optBoolean("ended", true);
        t.mode = o.optString("mode", "");
        t.distanceKm = o.optDouble("distance_km", 0);
        t.odoStartKm = dbl(o, "odo_start");
        t.odoEndKm = dbl(o, "odo_end");
        t.movingSec = o.optDouble("moving_s", 0);
        t.idleSec = o.optDouble("idle_s", 0);
        t.maxSpeedKmh = o.optDouble("max_speed", 0);
        t.socStart = dbl(o, "soc_start");
        t.socEnd = dbl(o, "soc_end");
        t.elecStart = dbl(o, "elec_start");
        t.elecEnd = dbl(o, "elec_end");
        t.hardAccel = o.optInt("hard_accel");
        t.hardBrake = o.optInt("hard_brake");
        t.startLat = dbl(o, "start_lat");
        t.startLon = dbl(o, "start_lon");
        t.endLat = dbl(o, "end_lat");
        t.endLon = dbl(o, "end_lon");
        t.fuelStart = dbl(o, "fuel_start");
        t.fuelEnd = dbl(o, "fuel_end");
        t.evStart = dbl(o, "ev_start");
        t.evEnd = dbl(o, "ev_end");
        t.hevStart = dbl(o, "hev_start");
        t.hevEnd = dbl(o, "hev_end");
        return t;
    }
}
