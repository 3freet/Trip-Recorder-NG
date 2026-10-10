package org.triprecorderng;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands a trip's GPX file to the map app the user picks. It is not exported: an app only gets to read the one
 * file it was given a URI for (Intent.FLAG_GRANT_READ_URI_PERMISSION), and only plain *.gpx names inside the
 * app's own cache folder are ever served.
 */
public final class TrackProvider extends ContentProvider {
    static final String AUTHORITY = "org.triprecorderng.tracks";
    static final String MIME = "application/gpx+xml";

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "tracks");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static Uri uriFor(String fileName) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(fileName).build();
    }

    /** A single plain file name ending in .gpx: no slashes, so no way out of the folder. */
    static boolean isValidName(String n) {
        return n != null && n.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,78}\\.gpx");
    }

    private File fileFor(Uri uri) {
        Context c = getContext();
        if (c == null || uri.getPathSegments().size() != 1) return null;
        String name = uri.getLastPathSegment();
        if (!isValidName(name)) return null;
        File f = new File(dir(c), name);
        return f.isFile() ? f : null;
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        return MIME;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = fileFor(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null || !"r".equals(mode)) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
