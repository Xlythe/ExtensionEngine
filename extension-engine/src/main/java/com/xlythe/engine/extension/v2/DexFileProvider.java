package com.xlythe.engine.extension.v2;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.UnsupportedSchemeException;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.xlythe.engine.extension.Extension;
import com.xlythe.engine.theme.App;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.xlythe.engine.extension.Extension.TAG;
import static com.xlythe.engine.extension.Extension.DEBUG;

public class DexFileProvider extends ContentProvider implements ContentProvider.PipeDataWriter<InputStream> {
    public static final String PATH_COUNT = "count";
    public static final String PATH_DEX = "dex";
    public static final String COLUMN_COUNT = "count";

    @Override
    public boolean onCreate() {
        return true;
    }

    private static String[] getDexFiles(Context context) {
        // Multidex only exists on API 21+
        if (Build.VERSION.SDK_INT >= 21) {
            String[] splitSourceDirs = context.getApplicationInfo().splitSourceDirs;
            if (splitSourceDirs != null && splitSourceDirs.length > 0) {
                return splitSourceDirs;
            }
        }
        return new String[] { context.getApplicationInfo().sourceDir };
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        // Security
        verifyCaller();

        // Special path for count to allow us to specify how many dex files we're returning.
        if (PATH_COUNT.equals(uri.getPath().substring(1))) {
            if (projection == null || projection.length == 0) {
                projection = new String[] { COLUMN_COUNT };
            }
            if (projection.length != 1 || !projection[0].equals(COLUMN_COUNT)) {
                throw new IllegalArgumentException("Unsupported projection: " + Arrays.toString(projection));
            }
            MatrixCursor cursor = new MatrixCursor(projection);
            cursor.addRow(new Object[] { getDexFiles(getContext()).length });
            return cursor;
        }

        // content providers that support open and openAssetFile should support queries for all
        // android.provider.OpenableColumns.
        int displayNameIndex = -1;
        int sizeIndex = -1;
        // If projection is null, return all columns.
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) {
                displayNameIndex = i;
            }
            if (OpenableColumns.SIZE.equals(projection[i])) {
                sizeIndex = i;
            }
        }
        MatrixCursor cursor = new MatrixCursor(projection);
        Object[] result = new Object[projection.length];
        for (int i = 0; i < result.length; i++) {
            if (i == displayNameIndex) {
                result[i] = uri.getPath();
            }
            if (i == sizeIndex) {
                result[i] = null; // Size is unknown, so null, if it was known, it would go here.
            }
        }
        cursor.addRow(result);
        return cursor;
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        // Don't support inserts.
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        // Don't support deletes.
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // Don't support updates.
        return 0;
    }

    @NonNull
    @Override
    public String getType(@NonNull Uri uri) {
        return "*/*";
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        // Try to open an asset with the given name.
        if (DEBUG) Log.d(TAG, "openFile: " + uri);
        try {
            // Determine which dex file to return
            int count = Integer.parseInt(uri.getLastPathSegment());
            String[] dexFiles = getDexFiles(getContext());
            Log.d(TAG, String.format(Locale.US, "Loading dex file %d/%d", count, dexFiles.length));
            Log.d(TAG, String.format(Locale.US, "Dex file located at %s", dexFiles[count]));

            // Start a new thread that pipes the stream data back to the caller.
            return ParcelFileDescriptor.open(new File(dexFiles[count]), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            Log.e(TAG, "Unable to open " + uri, e);
            throw new FileNotFoundException("Unable to open " + uri);
        }
    }

    @Override
    public void writeDataToPipe(@NonNull ParcelFileDescriptor output, @NonNull Uri uri, @NonNull String mimeType, Bundle opts, InputStream args) {
        // Transfer data from the asset to the pipe the client is reading.
        byte[] buffer = new byte[8192];
        int n;
        FileOutputStream outputStream = new FileOutputStream(output.getFileDescriptor());
        try {
            while ((n = args.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, n);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed transferring", e);
        } finally {
            try {
                args.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close input stream", e);
            }
            try {
                outputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close output stream", e);
            }
        }
    }

    /**
     * Only available on API 19+. Verifies that the caller is a supported theme.
     */
    private void verifyCaller() throws SecurityException {
        if (Build.VERSION.SDK_INT < 19) {
            return;
        }

        // We'll query for ourselves to see if we show up as an available extension for this caller.
        List<App> apps = Extension.getApps(getContext(), getCallingPackage());
        for (App app : apps) {
            if (app.getPackageName().equals(getContext().getPackageName())) {
                return;
            }
        }

        // If we got here, then we couldn't verify the caller.
        throw new SecurityException("Caller [" + getCallingPackage() + "] is not a registered extension.");
    }
}
