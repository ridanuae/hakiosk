package com.spark.hakiosk;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the downloaded APK to the system installer as a content:// URI.
 *
 * From API 24 a file:// URI in an intent throws FileUriExposedException at
 * targetSdk 24+, and the usual answer -- support-library FileProvider -- is
 * not available here: this app links no support libraries at all (see the
 * build notes in README). So this is that, in the twenty lines we actually
 * need: read-only, one file, not exported.
 */
public class ApkProvider extends ContentProvider {

    static final String AUTHORITY = "com.spark.hakiosk.apk";

    static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return Updater.MIME;
    }

    /** Some installers ask for the name and size before opening the file. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor cursor =
                new MatrixCursor(new String[] { "_display_name", "_size" }, 1);
        cursor.addRow(new Object[] { file.getName(), Long.valueOf(file.length()) });
        return cursor;
    }

    /**
     * Only ever the one file in our own cache dir. Named explicitly rather
     * than built from the path, so no request can walk out of it.
     */
    private File fileFor(Uri uri) throws FileNotFoundException {
        if (!Updater.APK_NAME.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return Updater.apkFile(getContext());
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
