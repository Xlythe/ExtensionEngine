package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.util.Log;

import com.xlythe.engine.extension.Extension;
import com.xlythe.engine.extension.ExtensionResources;
import com.xlythe.engine.extension.Security;

import static com.xlythe.engine.extension.Extension.TAG;

public class ExtensionContextV1 extends ContextWrapper {
    private final String extensionClassName;

    public ExtensionContextV1(Context context, String packageName) throws IllegalArgumentException {
        this(context, packageName, context.getPackageName());
    }

    public ExtensionContextV1(Context context, String packageName, String actionPackage) {
        super(null);
        ActivityInfo activity = ExtensionResources.getActivityInfo(context, packageName, actionPackage);
        if (activity == null) {
            throw new SecurityException("No registered extension activity in " + packageName);
        }
        extensionClassName = activity.metaData != null && activity.metaData.containsKey(Extension.KEY_EXTENSION)
                ? activity.metaData.getString(Extension.KEY_EXTENSION) : packageName + Extension.CLASS_NAME;
        attachBaseContext(createExtensionContext(context, activity, extensionClassName));
    }

    public String getExtensionClassName() {
        return extensionClassName;
    }

    private static Context createExtensionContext(Context context, ActivityInfo activity, String className) {
        String packageName = activity.packageName;
        int flags = Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY;
        try {
            Security.enforceTrustedPackage(context, packageName);
            Context newContext = context.createPackageContext(packageName, flags);
            String adapter = activity.metaData == null ? null : activity.metaData.getString(Extension.KEY_LEGACY_ADAPTER);
            if (adapter != null) {
                try {
                    // Test the entry point without running its initializer or constructor.
                    newContext.getClassLoader().loadClass(className);
                } catch (ClassNotFoundException unavailable) {
                    Security.enforceLegacyAdapter(context, packageName, adapter);
                    newContext = context.createPackageContext(adapter, flags);
                }
            }

            // Update language
            newContext.getResources().updateConfiguration(new Configuration(context.getResources().getConfiguration()), context.getResources().getDisplayMetrics());

            return newContext;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to create extension context", e);
            throw new IllegalArgumentException("Could not load " + packageName, e);
        }
    }
}
