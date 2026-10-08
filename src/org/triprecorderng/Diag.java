package org.triprecorderng;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny rolling diagnostics log kept next to the app's other files. */
final class Diag {
    private static final String TAG = "TripRec";
    private static final long MAX_BYTES = 512 * 1024;
    private static File file;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private Diag() {}

    static synchronized void init(Context ctx) {
        if (file != null) return;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        file = new File(dir, "diag.log");
    }

    static File file() {
        return file;
    }

    static synchronized void log(String msg) {
        Log.i(TAG, msg);
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) {
                File old = new File(file.getPath() + ".1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            FileWriter w = new FileWriter(file, true);
            w.write(FMT.format(new Date()) + "  " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
            // logging must never break recording
        }
    }

    static void log(String msg, Throwable t) {
        log(msg + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}
