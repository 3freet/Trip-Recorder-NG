package org.triprecorderng;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opens a recorded trip in Google Maps. Maps cannot draw an arbitrary polyline from a link, so the
 * trip is sent as a driving route: start, end and the few points that best keep the track's shape as
 * waypoints (Maps accepts at most 9). Maps then follows the roads between them.
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

    static void open(Context ctx, List<double[]> pts) {
        Uri uri = routeUri(pts);
        if (uri == null) {
            Toast.makeText(ctx, L.t("This trip has no GPS track"), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        try {
            intent.setPackage(MAPS_PACKAGE);
            ctx.startActivity(intent);
            return;
        } catch (ActivityNotFoundException e) {
            Diag.log("google maps app not available, trying the browser");
        } catch (Throwable t) {
            Diag.log("open google maps failed", t);
        }
        try {
            intent.setPackage(null);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Diag.log("open map link failed", t);
            Toast.makeText(ctx, L.t("Could not open Google Maps"), Toast.LENGTH_LONG).show();
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
