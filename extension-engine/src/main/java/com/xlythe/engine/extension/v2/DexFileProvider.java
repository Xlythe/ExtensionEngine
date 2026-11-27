package com.xlythe.engine.extension.v2;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import com.xlythe.engine.extension.ExtensionResources;
import com.xlythe.engine.extension.Security;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.xlythe.engine.extension.Extension.TAG;

/**
 * A file provider allows other apps installed on the user's device to request us for files. This one
 * specifically provides the dex files needed to load our class into memory. Extensions should add
 * the following lines of code to their AndroidManifest.xml to enable the provider for older hosts.
 *
 * <pre>{@code
 * <provider
 *   android:authorities="your.package.name.DexFileProvider"
 *   android:name="com.xlythe.engine.extension.v2.DexFileProvider"
 *   android:exported="true" />
 * }</pre>
 */
public class DexFileProvider extends ContentProvider implements ContentProvider.PipeDataWriter<InputStream> {
    public static final String PATH_COUNT = "count";
    public static final String PATH_DEX = "dex";
    public static final String COLUMN_COUNT = "count";

    @Override
    public boolean onCreate() {
        return true;
    }

    static String[] installedApks(ApplicationInfo info) {
        String[] splits = Build.VERSION.SDK_INT >= 21 ? info.splitSourceDirs : null;
        String[] apks = new String[1 + (splits == null ? 0 : splits.length)];
        apks[0] = info.sourceDir;
        if (splits != null) System.arraycopy(splits, 0, apks, 1, splits.length);
        return apks;
    }

    static int fileIndex(Uri uri, int count) {
        List<String> parts = uri.getPathSegments();
        if (parts.size() == 2 && PATH_DEX.equals(parts.get(0))) {
            try {
                int index = Integer.parseInt(parts.get(1));
                if (index >= 0 && index < count) return index;
            } catch (NumberFormatException invalid) {
                // Treat malformed and out-of-range indices as unknown files.
            }
        }
        throw new IllegalArgumentException("Unknown APK: " + uri);
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        // Security
        verifyCaller();

        // Special path for count to allow us to specify how many dex files we're returning.
        String[] files = installedApks(getContext().getApplicationInfo());
        if (("/" + PATH_COUNT).equals(uri.getPath())) {
            if (projection == null || projection.length == 0) {
                projection = new String[] { COLUMN_COUNT };
            }
            if (projection.length != 1 || !projection[0].equals(COLUMN_COUNT)) {
                throw new IllegalArgumentException("Unsupported projection: " + Arrays.toString(projection));
            }
            MatrixCursor cursor = new MatrixCursor(projection);
            cursor.addRow(new Object[] { files.length });
            return cursor;
        }

        // content providers that support open and openAssetFile should support queries for all
        // android.provider.OpenableColumns.
        fileIndex(uri, files.length);
        // If projection is null, return all columns.
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor cursor = new MatrixCursor(projection);
        Object[] result = new Object[projection.length];
        for (int i = 0; i < result.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) {
                result[i] = uri.getPath();
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
        verifyCaller();
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        String[] files = installedApks(getContext().getApplicationInfo());
        try {
            return ParcelFileDescriptor.open(new File(files[fileIndex(uri, files.length)]),
                    ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IllegalArgumentException invalid) {
            throw new FileNotFoundException(invalid.getMessage());
        }
    }

    /** Compatibility callback for older pipe-based clients. openFile returns a read-only descriptor directly. */
    @Override
    public void writeDataToPipe(@NonNull ParcelFileDescriptor output, @NonNull Uri uri, @NonNull String mimeType,
                                Bundle opts, InputStream args) {
        try (InputStream input = args; FileOutputStream stream = new FileOutputStream(output.getFileDescriptor())) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
        } catch (IOException e) {
            Log.e(TAG, "Failed transferring " + uri, e);
        }
    }

    private void verifyCaller() {
        Context context = getContext();
        String[] packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        String callingPackage = Build.VERSION.SDK_INT >= 19 ? getCallingPackage() : null;
        if (packages != null) {
            for (String candidate : packages) {
                if (Build.VERSION.SDK_INT >= 19 && !candidate.equals(callingPackage)) {
                    continue;
                }
                if (ExtensionResources.getActivityInfo(context, context.getPackageName(), candidate) != null) {
                    Security.enforceTrustedPackage(context, candidate);
                    return;
                }
            }
        }
        throw new SecurityException("Caller is not a registered host");
    }
}
