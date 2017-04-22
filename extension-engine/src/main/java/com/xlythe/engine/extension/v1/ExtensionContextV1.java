package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.util.Log;

import static com.xlythe.engine.extension.Extension.TAG;

public class ExtensionContextV1 extends ContextWrapper {
    public ExtensionContextV1(Context context, String packageName) throws IllegalArgumentException {
        super(createExtensionContext(context, packageName));
    }

    private static Context createExtensionContext(Context context, String packageName) throws IllegalArgumentException {
        try {
            Context newContext = context.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE + Context.CONTEXT_IGNORE_SECURITY);

            // Update language
            newContext.getResources().updateConfiguration(new Configuration(context.getResources().getConfiguration()), context.getResources().getDisplayMetrics());

            return newContext;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to create extension context", e);
            throw new IllegalArgumentException();
        }
    }
}
