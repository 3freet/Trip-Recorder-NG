package org.triprecorderng;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Manual update check and install, from the GitHub releases of this project. Nothing here runs by itself:
 * it only runs when the user taps Check for updates (or confirms an install).
 *
 * <p>Every release is a build named "&lt;channel&gt;-&lt;number&gt;" ("dev-9", "stable-8"); CI puts the same name
 * in the APK's version name and in the release tag, and the number counts up across both channels. The
 * version code is always 1 on purpose: Android refuses to install a lower version code over a higher one,
 * and switching from dev back to stable has to work.
 *
 * <p>An install replaces this app through the car's loopback debugging (needs the Vehicle data link), after
 * checking the file's checksum and that it is signed with the same key as the installed app.
 */
final class Updates {
    private Updates() {}

    static final String STABLE = "stable";
    static final String DEV = "dev";

    private static final String REPO = "3freet/Trip-Recorder-NG";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases?per_page=50";
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    private static final int MAX_JSON_BYTES = 2 << 20;
    private static final long MAX_APK_BYTES = 40L << 20;

    // ---- build names -------------------------------------------------------------------------

    /** A build identity: channel and build number, as in "dev-9". */
    static final class Build {
        final String channel;
        final int number;

        Build(String channel, int number) {
            this.channel = channel;
            this.number = number;
        }

        String name() {
            return channel + "-" + number;
        }
    }

    /** "dev-9" or "stable-8" as a build; null for anything else (a local build is named "0.1"). */
    static Build parse(String name) {
        if (name == null) return null;
        int dash = name.lastIndexOf('-');
        if (dash <= 0 || dash == name.length() - 1) return null;
        String channel = name.substring(0, dash);
        if (!STABLE.equals(channel) && !DEV.equals(channel)) return null;
        String digits = name.substring(dash + 1);
        if (digits.length() > 9) return null;
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return null;
        }
        int n = Integer.parseInt(digits);
        return n > 0 ? new Build(channel, n) : null;
    }

    /** The version name of the running app ("dev-9", or "0.1" for a build made on a computer). */
    static String installedName(Context ctx) {
        try {
            String v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (Throwable t) {
            return "?";
        }
    }

    static Build installed(Context ctx) {
        return parse(installedName(ctx));
    }

    /** The channel to follow: the saved choice, else the channel of the installed build, else stable. */
    static String channel(Context ctx) {
        String saved = Prefs.updateChannel(ctx);
        if (STABLE.equals(saved) || DEV.equals(saved)) return saved;
        Build b = installed(ctx);
        return b != null ? b.channel : STABLE;
    }

    static String channelTitle(String channel) {
        return DEV.equals(channel) ? L.t("Dev") : L.t("Stable");
    }

    // ---- releases ----------------------------------------------------------------------------

    static final class Release {
        Build build;
        long publishedMs;
        String assetName;
        String assetUrl;
        String sha256; // lower-case hex, or null when GitHub gave none
        long size;
    }

    /** What GitHub's release list says, as releases that carry an APK (drafts and odd tags are skipped). */
    static List<Release> parseReleases(String json) throws JSONException {
        List<Release> out = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.optBoolean("draft")) continue;
            Build b = parse(o.optString("tag_name", ""));
            if (b == null) continue;
            JSONArray assets = o.optJSONArray("assets");
            if (assets == null) continue;
            for (int a = 0; a < assets.length(); a++) {
                JSONObject as = assets.optJSONObject(a);
                if (as == null) continue;
                String name = as.optString("name", "");
                if (!name.endsWith(".apk")) continue;
                Release r = new Release();
                r.build = b;
                r.assetName = name;
                r.assetUrl = as.optString("browser_download_url", "");
                r.size = as.optLong("size", 0);
                String digest = as.optString("digest", "");
                r.sha256 = digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.US) : null;
                try {
                    r.publishedMs = iso.parse(o.optString("published_at", "")).getTime();
                } catch (Throwable t) {
                    r.publishedMs = 0;
                }
                out.add(r);
                break;
            }
        }
        return out;
    }

    /** The newest release of a channel (highest build number), or null. */
    static Release latest(List<Release> all, String channel) {
        Release best = null;
        for (Release r : all) {
            if (!r.build.channel.equals(channel)) continue;
            if (best == null || r.build.number > best.build.number) best = r;
        }
        return best;
    }

    /** Positive when the release is newer than the installed build, 0 when it is the same, negative when older. */
    static int compare(Build installed, Release release) {
        if (installed == null) return 1; // a build made on a computer: any release is an update
        return Integer.compare(release.build.number, installed.number);
    }

    // ---- check -------------------------------------------------------------------------------

    static final class Check {
        Release latest; // null when the channel has no release yet
        String error;   // null when the check worked
    }

    /** Asks GitHub for the releases of a channel. Blocking: call it from a background thread. */
    static Check check(Context ctx, String channel) {
        Check c = new Check();
        try {
            byte[] body = httpGet(ctx, API_URL);
            c.latest = latest(parseReleases(new String(body, "UTF-8")), channel);
        } catch (IOException e) {
            c.error = describe(e);
            Diag.log("update check failed: " + e);
        } catch (Throwable t) {
            c.error = L.t("GitHub's answer could not be read");
            Diag.log("update check failed", t);
        }
        return c;
    }

    private static String describe(IOException e) {
        if (e instanceof UnknownHostException || e instanceof java.net.ConnectException
                || e instanceof SocketTimeoutException) {
            return L.t("No internet connection (GitHub cannot be reached)");
        }
        String m = e.getMessage();
        return m != null && m.startsWith("HTTP ")
                ? L.f("GitHub answered %s", m) : L.t("The connection to GitHub failed");
    }

    private static HttpURLConnection open(Context ctx, String url, String accept) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("not https");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "TripRecorderNG/" + installedName(ctx));
        c.setRequestProperty("Accept", accept);
        return c;
    }

    private static byte[] httpGet(Context ctx, String url) throws IOException {
        HttpURLConnection c = open(ctx, url, "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_JSON_BYTES) throw new IOException("answer too large");
            }
            return buf.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    // ---- download and checks -------------------------------------------------------------------

    interface Progress {
        void onProgress(long done, long total);

        boolean cancelled();
    }

    static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    /** Where downloaded builds go: the app's own folder on shared storage, which the shell user can read. */
    static File updateDir(Context ctx) {
        return ctx.getExternalFilesDir("update");
    }

    /**
     * Downloads a release's APK into the update folder (older downloads are removed first) and checks
     * its size and checksum. Blocking: call it from a background thread.
     */
    static File download(Context ctx, Release r, Progress progress) throws IOException {
        if (r.assetUrl == null || !r.assetUrl.startsWith(DOWNLOAD_PREFIX)) {
            throw new IOException(L.t("The download address is not from this project's releases"));
        }
        File dir = updateDir(ctx);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException(L.t("No storage is available"));
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        String safe = r.assetName.replaceAll("[^A-Za-z0-9._-]", "_");
        File part = new File(dir, safe + ".part");
        File done = new File(dir, safe);
        HttpURLConnection c = open(ctx, r.assetUrl, "application/octet-stream");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException(L.f("GitHub answered %s", "HTTP " + code));
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.size;
            if (total > MAX_APK_BYTES) throw new IOException(L.t("The file is larger than expected"));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long got = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (progress != null && progress.cancelled()) throw new Cancelled();
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    got += n;
                    if (got > MAX_APK_BYTES) throw new IOException(L.t("The file is larger than expected"));
                    if (progress != null) progress.onProgress(got, total);
                }
            }
            if ((r.size > 0 && got != r.size) || (total > 0 && got != total)) {
                throw new IOException(L.t("The download is incomplete"));
            }
            if (r.sha256 != null && !r.sha256.equals(hex(sha.digest()))) {
                throw new IOException(L.t("The downloaded file is damaged (its checksum differs)"));
            }
            if (!part.renameTo(done)) throw new IOException(L.t("The file could not be saved"));
            return done;
        } catch (IOException e) {
            part.delete();
            throw e;
        } catch (java.security.NoSuchAlgorithmException e) {
            part.delete();
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    /** Null when the file is this app signed with the same key as the installed one, else the reason it is not. */
    static String verifyApk(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo a = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (a == null) return L.t("The file is not a valid app");
            if (!ctx.getPackageName().equals(a.packageName)) return L.t("The file is a different app");
            PackageInfo mine = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (a.signingInfo == null || mine.signingInfo == null) return L.t("The file's signature could not be read");
            Signature[] theirs = a.signingInfo.getApkContentsSigners();
            Signature[] ours = mine.signingInfo.getApkContentsSigners();
            if (theirs == null || ours == null || theirs.length == 0
                    || !new HashSet<>(Arrays.asList(theirs)).equals(new HashSet<>(Arrays.asList(ours)))) {
                return L.t("The file is signed with a different key, so Android would not install it over this app");
            }
            return null;
        } catch (Throwable t) {
            Diag.log("could not verify the downloaded app", t);
            return L.t("The file could not be checked");
        }
    }

    // ---- install -----------------------------------------------------------------------------

    /**
     * Replaces this app with the downloaded file, then starts it again, in one shell command through the car's
     * loopback debugging. On success the system stops this process, so the result is only seen when it failed.
     */
    static AdbLoopback.Result install(Context ctx, File apk) {
        String path = apk.getPath();
        if (!path.matches("[A-Za-z0-9/._-]+")) {
            return new AdbLoopback.Result(AdbLoopback.Status.ERROR, "", "unexpected characters in the file path");
        }
        String cmd = "pm install -r " + path + " && am start -n " + ctx.getPackageName() + "/.MainActivity";
        Diag.log("update install: " + cmd);
        return new AdbLoopback(ctx).runShell(cmd, 20_000);
    }
}
