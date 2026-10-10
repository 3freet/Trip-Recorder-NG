package org.triprecorderng;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Writes trips to CSV and GPX files in Documents/TripRecorderNG (or the app folder as fallback). */
final class Exporter {
    private Exporter() {}

    static File dir(Context ctx) {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "TripRecorderNG");
        if (d.isDirectory() || d.mkdirs()) {
            if (d.canWrite()) return d;
        }
        File fallback = ctx.getExternalFilesDir(null);
        return fallback != null ? fallback : ctx.getFilesDir();
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static String local(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        return f.format(new Date(ms));
    }

    private static String num(double v, String fmt) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, fmt, v);
    }

    static File exportCsv(Context ctx) throws IOException {
        List<Trip> trips = TripDb.get(ctx).all();
        File out = new File(dir(ctx), "trips.csv");
        FileWriter w = new FileWriter(out, false);
        try {
            w.write("start,end,duration_s,distance_km,odometer_delta_km,moving_s,idle_s,"
                    + "avg_speed_kmh,max_speed_kmh,soc_start,soc_end,soc_used,energy_used,"
                    + "energy_per_100km,hard_accel,hard_brake,start_lat,start_lon,end_lat,end_lon,"
                    + "mode,ended,id,fuel_used_l,fuel_per_100km,ev_km,hev_km,electricity_cost,fuel_cost,"
                    + "total_cost,currency,speed_integral_km,car_trip_counter_km\n");
            for (Trip t : trips) {
                w.write(local(t.startMs) + "," + (t.endMs > 0 ? local(t.endMs) : "") + ","
                        + t.durationSec() + "," + num(t.distanceKm, "%.3f") + ","
                        + num(t.odometerDeltaKm(), "%.1f") + "," + num(t.movingSec, "%.0f") + ","
                        + num(t.idleSec, "%.0f") + "," + num(t.avgSpeedKmh(), "%.1f") + ","
                        + num(t.maxSpeedKmh, "%.1f") + "," + num(t.socStart, "%.1f") + ","
                        + num(t.socEnd, "%.1f") + "," + num(t.socUsed(), "%.1f") + ","
                        + num(t.energyUsed(), "%.2f") + "," + num(t.energyPer100Km(), "%.1f") + ","
                        + t.hardAccel + "," + t.hardBrake + "," + num(t.startLat, "%.6f") + ","
                        + num(t.startLon, "%.6f") + "," + num(t.endLat, "%.6f") + ","
                        + num(t.endLon, "%.6f") + "," + t.mode + "," + (t.ended ? 1 : 0) + ","
                        + t.id + "," + num(t.fuelUsed(), "%.2f") + "," + num(t.fuelPer100Km(), "%.2f") + ","
                        + num(t.evKm(), "%.0f") + "," + num(t.hevKm(), "%.0f") + ","
                        + num(Cost.elec(t, ctx), "%.4f") + "," + num(Cost.fuel(t, ctx), "%.4f") + ","
                        + num(Cost.total(t, ctx), "%.4f") + ","
                        + Prefs.currency(ctx) + "," + num(t.speedKm, "%.3f") + ","
                        + num(t.journeyAcc, "%.1f") + "\n");
            }
        } finally {
            w.close();
        }
        return out;
    }

    /** The file name a trip's GPX gets, from the start time. */
    static String gpxFileName(long startMs) {
        return "trip-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(startMs)) + ".gpx";
    }

    /** One GPX track with every point of the trip; points are {time ms, lat, lon, altitude, ...}. */
    static String gpx(long startMs, List<double[]> pts) {
        StringBuilder sb = new StringBuilder(pts.size() * 90 + 300);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"TripRecorder\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("<trk><name>").append(local(startMs)).append("</name><trkseg>\n");
        for (double[] p : pts) {
            sb.append("<trkpt lat=\"").append(String.format(Locale.US, "%.6f", p[1])).append("\" lon=\"")
                    .append(String.format(Locale.US, "%.6f", p[2])).append("\"><ele>")
                    .append(String.format(Locale.US, "%.1f", p[3])).append("</ele><time>").append(iso((long) p[0]))
                    .append("</time></trkpt>\n");
        }
        sb.append("</trkseg></trk>\n</gpx>\n");
        return sb.toString();
    }

    static File exportGpx(Context ctx, Trip t) throws IOException {
        List<double[]> pts = TripDb.get(ctx).points(t.id);
        File out = new File(dir(ctx), gpxFileName(t.startMs));
        FileWriter w = new FileWriter(out, false);
        try {
            w.write(gpx(t.startMs, pts));
        } finally {
            w.close();
        }
        return out;
    }
}
