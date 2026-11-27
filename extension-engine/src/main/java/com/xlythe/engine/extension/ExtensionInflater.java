package com.xlythe.engine.extension;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.Map;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import static com.xlythe.engine.extension.Extension.TAG;

public class ExtensionInflater {
    /**
     * A version of {@link View#inflate(Context, int, ViewGroup)} with a bugfix for multiple
     * classloaders.
     */
    @NonNull
    public static View inflate(Context context, @LayoutRes int resId, @NonNull ViewGroup parent) {
        // Clear once for the extension
        clearLayoutInflaterCache();
        try {
            return LayoutInflater.from(context).inflate(resId, parent);
        } finally {
            // Clear a second time for the main app
            clearLayoutInflaterCache();
        }
    }

    @SuppressLint("SoonBlockedPrivateApi") // Reflection below is confined to API 23 and earlier.
    private static void clearLayoutInflaterCache() {
        // Android N and later verify the class loader of each cached constructor.
        if (Build.VERSION.SDK_INT >= 24) return;
        try {
            // LayoutInflater has an internal String -> View map that can get easily befuddled
            // when there are multiple classloaders. We'll clear it before use.
            Field sConstructorMap = LayoutInflater.class.getDeclaredField("sConstructorMap");
            sConstructorMap.setAccessible(true);
            Map internalHashMap = (Map) sConstructorMap.get(null);
            internalHashMap.clear();
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear layout inflater cache", e);
        }
    }
}
