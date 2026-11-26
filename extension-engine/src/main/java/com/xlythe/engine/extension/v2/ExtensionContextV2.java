package com.xlythe.engine.extension.v2;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import com.xlythe.engine.extension.v1.ExtensionContextV1;

import java.io.IOException;

/**
 * TODO: This doesn't properly load resources from the other class.
 */
public class ExtensionContextV2 extends ContextWrapper {
    private final String mPackageName;
    private final ClassLoader mClassLoader;

    public ExtensionContextV2(Context context, String packageName) throws InvalidCursorException, IOException {
        super(context);
        mPackageName = packageName;
        // Use the installed APK verified by the same loader as V1.
        mClassLoader = new ExtensionContextV1(context, packageName).getClassLoader();
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

    public static class InvalidCursorException extends Exception {}
}
