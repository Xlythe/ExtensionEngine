package com.xlythe.engine.extension.v2;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

import dalvik.system.DexClassLoader;

import static com.xlythe.engine.extension.Extension.DEBUG;
import static com.xlythe.engine.extension.Extension.TAG;

/**
 * TODO: This doesn't properly load resources from the other class.
 */
public class ExtensionContextV2 extends ContextWrapper {
    private static final String PATH_COUNT = "content://%s.DexFileProvider/" + DexFileProvider.PATH_COUNT;
    private static final String PATH_DEX = "content://%s.DexFileProvider/" + DexFileProvider.PATH_DEX + "/%d";
    private static final String OUTPUT_FILE = "%s.classes_%d.dex";
    private final String mPackageName;
    private final ClassLoader mClassLoader;

    public ExtensionContextV2(Context context, String packageName) throws InvalidCursorException, IOException {
        super(context);
        mPackageName = packageName;
        mClassLoader = openDexFile(context, packageName);
    }

    @Override
    public Resources getResources() {
        try {
            return getPackageManager().getResourcesForApplication(mPackageName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return super.getResources();
        }
    }

    @Override
    public String getPackageName() {
        return mPackageName;
    }

    @Override
    public ClassLoader getClassLoader() {
        return mClassLoader;
    }

    private static ClassLoader openDexFile(Context context, String packageName) throws InvalidCursorException, IOException {
        // Woe is us. With the advent of multi-dex, we need to first query the extension for
        // how many dex files it has.
        Cursor cursor = context.getContentResolver().query(Uri.parse(String.format(Locale.US, PATH_COUNT, packageName)), null, null, null, null);
        if (cursor == null || !cursor.moveToFirst()) {
            close(cursor);
            throw new InvalidCursorException();
        }
        int count = cursor.getInt(cursor.getColumnIndexOrThrow(DexFileProvider.COLUMN_COUNT));
        close(cursor);
        if (DEBUG) Log.v(TAG, String.format(Locale.US, "Found %d dex file(s) for package %s", count, packageName));

        // Now that we know how many dex files we need to load, we can start grabbing them from the DexFileProvider one by one.
        ClassLoader[] classLoaders = new ClassLoader[count];
        for (int i = 0; i < count; i++) {
            Uri uri = Uri.parse(String.format(Locale.US, PATH_DEX, packageName, i));
            if (DEBUG) Log.d(TAG, String.format(Locale.US, "Loading class %d with path %s", count, uri));
            ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "*/*");
            if (pfd == null) {
                throw new InvalidCursorException();
            }
            File file = saveToDisc(context, pfd, String.format(Locale.US, OUTPUT_FILE, packageName, count));
            classLoaders[i] = createClassLoader(context, file);
        }

        // TODO Figure out how to load class file from a DexClassLoader... So close, and yet so far.
        return classLoaders[0];
    }

    private static void close(@Nullable Cursor cursor) {
        if (cursor != null) {
            cursor.close();
        }
    }

    public static class InvalidCursorException extends Exception {}

    private static File saveToDisc(Context context, ParcelFileDescriptor input, String output) throws IOException {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(input.getFileDescriptor());
            File file = new File(getOutputFolder(context), output);
            file.createNewFile();
            out = new FileOutputStream(file);
            byte[] dataBuffer = new byte[1024];
            int readLength = 0;
            while ((readLength = in.read(dataBuffer)) != -1) {
                out.write(dataBuffer, 0, readLength);
            }
            return file;
        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        }
    }

    private static File getOutputFolder(Context context) {
        if (Build.VERSION.SDK_INT >= 21) {
            return context.getCodeCacheDir();
        }
        return context.getCacheDir();
    }

    private static ClassLoader createClassLoader(Context context, File dexFile) {
        return new DexClassLoader(dexFile.getAbsolutePath(), dexFile.getAbsolutePath(), null, context.getClassLoader());
    }
}
