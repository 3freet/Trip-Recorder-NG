package org.triprecorderng;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opens a recorded trip in a map app the user picks from an "Open track with" list.
 * <ul>
 * <li>Map apps that can open GPX files (OsmAnd, Organic Maps, ...) get the trip as a GPX file: the track is drawn
 * exactly as recorded, off-road parts included.</li>
 * <li>Google Maps, Yandex and 2GIS cannot open GPX or draw an arbitrary line from a link, so for them the trip
 * is sent as a driving route: start, end and the few points that best keep the track's shape as waypoints.
 * They then follow the roads between those points, and the list says so.</li>
 * <li>"Other apps" hands the GPX file to Android's own chooser (file managers and so on).</li>
 * </ul>
 */
final class MapLink {
    static final String MAPS_PACKAGE = "com.google.android.apps.maps";
    private static final int MAX_WAYPOINTS = 8;

    private MapLink() {}

    /** points are {time, lat, lon, ...}; needs at least two. Returns null if the trip has no track. */
    static Uri routeUri(List<double[]> pts) {
        if (pts == null || pts.size() < 2) return null;
        int last = pts.size() - 1;
        StringBuilder sb = new StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving");
        sb.append("&origin=").append(ll(pts.get(0)));
        sb.append("&destination=").append(ll(pts.get(last)));
        List<Integer> via = shapePoints(pts, MAX_WAYPOINTS);
        if (!via.isEmpty()) {
            sb.append("&waypoints=");
            for (int i = 0; i < via.size(); i++) {
                if (i > 0) sb.append("%7C");
                sb.append(ll(pts.get(via.get(i))));
            }
        }
        return Uri.parse(sb.toString());
    }

    private static final String YANDEX_NAVI = "ru.yandex.yandexnavi";
    private static final String YANDEX_MAPS = "ru.yandex.yandexmaps";
    private static final String DGIS = "ru.dublgis.dgismobile";

    /** Yandex Navigator: start, up to three shape points as via points, end. */
    static String yandexNaviUrl(List<double[]> pts) {
        int last = pts.size() - 1;
        StringBuilder sb = new StringBuilder("yandexnavi://build_route_on_map?");
        sb.append(String.format(Locale.US, "lat_from=%.6f&lon_from=%.6f", pts.get(0)[1], pts.get(0)[2]));
        List<Integer> via = shapePoints(pts, 3);
        for (int i = 0; i < via.size(); i++) {
            double[] p = pts.get(via.get(i));
            sb.append(String.format(Locale.US, "&lat_via_%d=%.6f&lon_via_%d=%.6f", i, p[1], i, p[2]));
        }
        sb.append(String.format(Locale.US, "&lat_to=%.6f&lon_to=%.6f", pts.get(last)[1], pts.get(last)[2]));
        return sb.toString();
    }

    /** Yandex Maps: points as lat,lon separated by ~. */
    static String yandexMapsUrl(List<double[]> pts) {
        StringBuilder sb = new StringBuilder("yandexmaps://maps.yandex.ru/?rtext=").append(ll(pts.get(0)));
        for (int idx : shapePoints(pts, 3)) sb.append('~').append(ll(pts.get(idx)));
        sb.append('~').append(ll(pts.get(pts.size() - 1))).append("&rtt=auto");
        return sb.toString();
    }

    /** 2GIS takes only a start and an end (lon,lat order). */
    static String dgisUrl(List<double[]> pts) {
        double[] a = pts.get(0);
        double[] b = pts.get(pts.size() - 1);
        return String.format(Locale.US, "dgis://2gis.ru/routeSearch/rsType/car/from/%.6f,%.6f/to/%.6f,%.6f",
                a[2], a[1], b[2], b[1]);
    }

    /** Writes the trip as a GPX file into the app's cache (older ones are removed) and returns its name. */
    private static String writeGpx(Context ctx, List<double[]> pts) throws java.io.IOException {
        File dir = TrackProvider.dir(ctx);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        long start = (long) pts.get(0)[0];
        File out = new File(dir, Exporter.gpxFileName(start));
        FileWriter w = new FileWriter(out, false);
        try {
            w.write(Exporter.gpx(start, pts));
        } finally {
            w.close();
        }
        return out.getName();
    }

    /** One line of the list. */
    private static final class Target {
        String label;
        String note;
        Drawable icon;
        Intent intent;
    }

    private static Intent gpxIntent(Uri uri) {
        Intent v = new Intent(Intent.ACTION_VIEW);
        v.setDataAndType(uri, TrackProvider.MIME);
        v.setClipData(ClipData.newRawUri("", uri));
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return v;
    }

    /** The route link for the apps that take one: the intent aimed at the app's own link handler, or null. */
    private static Intent routeIntent(PackageManager pm, String pkg, List<double[]> pts) {
        String url;
        if (MAPS_PACKAGE.equals(pkg)) url = routeUri(pts).toString();
        else if (YANDEX_NAVI.equals(pkg)) url = yandexNaviUrl(pts);
        else if (YANDEX_MAPS.equals(pkg)) url = yandexMapsUrl(pts);
        else if (DGIS.equals(pkg)) url = dgisUrl(pts);
        else return null;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.setPackage(pkg);
        ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        return i;
    }

    /**
     * The list for this phone: every installed map app (one that opens a geo: link) with the best way to give
     * it the trip, exact track first, then the road routes, then "Other apps" for GPX viewers that are not maps.
     */
    private static List<Target> targets(Context ctx, Uri gpx, List<double[]> pts) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, ResolveInfo> gpxHandlers = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(gpxIntent(gpx), 0)) {
            if (ri.activityInfo != null && !gpxHandlers.containsKey(ri.activityInfo.packageName)) {
                gpxHandlers.put(ri.activityInfo.packageName, ri);
            }
        }
        Map<String, ResolveInfo> maps = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), 0)) {
            if (ri.activityInfo != null && !maps.containsKey(ri.activityInfo.packageName)
                    && !ctx.getPackageName().equals(ri.activityInfo.packageName)) {
                maps.put(ri.activityInfo.packageName, ri);
            }
        }
        List<Target> exact = new ArrayList<>();
        List<Target> roads = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : maps.entrySet()) {
            String pkg = e.getKey();
            Target t = new Target();
            t.label = String.valueOf(e.getValue().loadLabel(pm));
            t.icon = e.getValue().loadIcon(pm);
            ResolveInfo g = gpxHandlers.get(pkg);
            if (g != null) {
                Intent v = gpxIntent(gpx);
                v.setComponent(new ComponentName(g.activityInfo.packageName, g.activityInfo.name));
                t.intent = v;
                t.note = L.t("Exact track (GPX)");
                exact.add(t);
            } else {
                t.intent = routeIntent(pm, pkg, pts);
                if (t.intent == null) continue; // a map app that takes neither a GPX file nor a route link we know
                t.note = L.t("Follows roads, not the exact track");
                roads.add(t);
            }
        }
        List<Target> all = new ArrayList<>(exact);
        all.addAll(roads);
        boolean otherViewers = false;
        for (String pkg : gpxHandlers.keySet()) if (!maps.containsKey(pkg)) otherViewers = true;
        if (otherViewers) {
            Target t = new Target();
            t.label = L.t("Other apps...");
            t.note = L.t("Apps that open GPX files");
            t.intent = Intent.createChooser(gpxIntent(gpx), L.t("Open track with"));
            all.add(t);
        }
        return all;
    }

    /**
     * Shows the "Open track with" list. points are {time, lat, lon, ...}; needs at least two. The GPX file is
     * written first, then the list is built from the apps installed right now.
     */
    static void choose(final Context ctx, List<double[]> points) {
        if (points == null || points.size() < 2) {
            Toast.makeText(ctx, L.t("This trip has no GPS track"), Toast.LENGTH_SHORT).show();
            return;
        }
        final List<double[]> pts = new ArrayList<>(points);
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String name = writeGpx(ctx, pts);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            show(ctx, name, pts);
                        }
                    });
                } catch (final Throwable t) {
                    Diag.log("could not prepare the track", t);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(ctx, L.t("Could not open the track"), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static void show(final Context ctx, String fileName, List<double[]> pts) {
        try {
            final List<Target> list = targets(ctx, TrackProvider.uriFor(fileName), pts);
            if (list.isEmpty()) {
                Toast.makeText(ctx, L.t("No map app that opens GPX files is installed. OsmAnd is one."),
                        Toast.LENGTH_LONG).show();
                return;
            }
            final AlertDialog[] dialog = new AlertDialog[1];
            BaseAdapter adapter = new BaseAdapter() {
                @Override public int getCount() {
                    return list.size();
                }

                @Override public Object getItem(int i) {
                    return list.get(i);
                }

                @Override public long getItemId(int i) {
                    return i;
                }

                @Override public View getView(int i, View convert, ViewGroup parent) {
                    Target t = list.get(i);
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 10), Ui.dp(ctx, 20), Ui.dp(ctx, 10));
                    ImageView icon = new ImageView(ctx);
                    if (t.icon != null) icon.setImageDrawable(t.icon);
                    row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)));
                    LinearLayout col = new LinearLayout(ctx);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.addView(Ui.text(ctx, t.label, 20, Ui.TEXT_VALUE));
                    col.addView(Ui.text(ctx, t.note, 15, Ui.TEXT_LABEL));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.setMarginStart(Ui.dp(ctx, 16));
                    row.addView(col, lp);
                    return row;
                }
            };
            dialog[0] = Ui.show(new AlertDialog.Builder(ctx)
                    .setTitle(L.t("Open track with"))
                    .setAdapter(adapter, new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            try {
                                ctx.startActivity(list.get(which).intent);
                            } catch (Throwable t) {
                                Diag.log("open the track failed", t);
                                Toast.makeText(ctx, L.t("Could not open the track"), Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton(L.t("Cancel"), null));
        } catch (Throwable t) {
            Diag.log("could not show the track list", t);
            Toast.makeText(ctx, L.t("Could not open the track"), Toast.LENGTH_LONG).show();
        }
    }

    private static String ll(double[] p) {
        return String.format(Locale.US, "%.6f,%.6f", p[1], p[2]);
    }

    /**
     * Picks up to max interior points, always the one that deviates most from the straight lines
     * between the points chosen so far (a greedy Douglas-Peucker). Returned in track order.
     */
    private static List<Integer> shapePoints(List<double[]> pts, int max) {
        List<Integer> chosen = new ArrayList<>();
        chosen.add(0);
        chosen.add(pts.size() - 1);
        double midLat = (pts.get(0)[1] + pts.get(pts.size() - 1)[1]) / 2.0;
        double kx = Math.cos(Math.toRadians(midLat));
        for (int n = 0; n < max; n++) {
            int bestIdx = -1;
            double bestDev = 0;
            for (int s = 0; s + 1 < chosen.size(); s++) {
                int a = chosen.get(s);
                int b = chosen.get(s + 1);
                for (int i = a + 1; i < b; i++) {
                    double dev = distToSegment(pts.get(i), pts.get(a), pts.get(b), kx);
                    if (dev > bestDev) {
                        bestDev = dev;
                        bestIdx = i;
                    }
                }
            }
            // stop once the rest of the track is within about 30 m of the chosen lines
            if (bestIdx < 0 || bestDev < 0.00027) break;
            int at = 0;
            while (chosen.get(at) < bestIdx) at++;
            chosen.add(at, bestIdx);
        }
        chosen.remove(chosen.size() - 1);
        chosen.remove(0);
        return chosen;
    }

    /** Distance in degrees (longitude scaled by kx) from p to the segment a-b. */
    private static double distToSegment(double[] p, double[] a, double[] b, double kx) {
        double ax = a[2] * kx, ay = a[1], bx = b[2] * kx, by = b[1], px = p[2] * kx, py = p[1];
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        double cx = ax + t * dx, cy = ay + t * dy;
        return Math.hypot(px - cx, py - cy);
    }

    /** Opens a single place (for example where a charge happened) in Google Maps. */
    static void openPlace(Context ctx, double lat, double lon) {
        Uri uri = Uri.parse(String.format(Locale.US,
                "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", lat, lon));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        try {
            intent.setPackage(MAPS_PACKAGE);
            ctx.startActivity(intent);
            return;
        } catch (Throwable t) {
            Diag.log("open place in google maps failed, trying the browser");
        }
        try {
            intent.setPackage(null);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(ctx, L.t("Could not open Google Maps"), Toast.LENGTH_LONG).show();
        }
    }
}
