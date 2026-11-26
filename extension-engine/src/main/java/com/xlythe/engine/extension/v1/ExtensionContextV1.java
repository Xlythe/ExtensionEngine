package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import com.xlythe.engine.extension.Extension;
import com.xlythe.engine.extension.ExtensionResources;
import com.xlythe.engine.extension.Security;

public class ExtensionContextV1 extends ContextWrapper {
    private static final int CODE_FLAGS = Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY;
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
        Security.enforceTrustedPackage(context, packageName);
        String className = activity.metaData != null && activity.metaData.containsKey(Extension.KEY_EXTENSION)
                ? activity.metaData.getString(Extension.KEY_EXTENSION) : packageName + Extension.CLASS_NAME;
        String adapter = activity.metaData == null ? null : activity.metaData.getString(Extension.KEY_LEGACY_ADAPTER);
        boolean legacy = Build.VERSION.SDK_INT < 26;
        Context selected;
        try {
            selected = loadInstalled(context, packageName, className, legacy || adapter != null);
        } catch (ClassNotFoundException | PackageManager.NameNotFoundException | Resources.NotFoundException unavailable) {
            // Android can reject the ASEC resource directory before it even attempts to load code.
            Security.enforceTrustedPackage(context, packageName);
            try {
                String bundled = legacy ? getBundledAdapter(context, packageName) : null;
                if (bundled != null) {
                    selected = context;
                    className = bundled;
                } else if (adapter != null && (legacy || unavailable instanceof ClassNotFoundException)) {
                    Security.enforceLegacyAdapter(context, packageName, adapter);
                    selected = loadInstalled(context, adapter, className, true);
                } else {
                    throw new IllegalArgumentException("Could not load " + packageName, unavailable);
                }
            } catch (PackageManager.NameNotFoundException | ClassNotFoundException missing) {
                throw new IllegalArgumentException("Could not load adapter for " + packageName, missing);
            }
        }

        if (selected != context) {
            selected.getResources().updateConfiguration(new Configuration(context.getResources().getConfiguration()),
                    context.getResources().getDisplayMetrics());
        }
        extensionClassName = className;
        attachBaseContext(selected);
    }

    private static Context loadInstalled(Context host, String packageName, String className, boolean checkEntry)
            throws PackageManager.NameNotFoundException, ClassNotFoundException {
        Context extension = host.createPackageContext(packageName, CODE_FLAGS);
        if (Build.VERSION.SDK_INT < 21) {
            // A primary-DEX entry may first reference a secondary class long after construction.
            extension = PublicApkContext.load(host, extension);
        }
        if (checkEntry) {
            try {
                extension.getClassLoader().loadClass(className);
            } catch (ClassNotFoundException missing) {
                if (Build.VERSION.SDK_INT >= 21 && Build.VERSION.SDK_INT < 26) {
                    Context retry = PublicApkContext.load(host, extension);
                    retry.getClassLoader().loadClass(className);
                    return retry;
                }
                throw missing;
            }
        }
        return extension;
    }

    private static String getBundledAdapter(Context host, String owner) throws PackageManager.NameNotFoundException {
        ApplicationInfo app = host.getPackageManager().getApplicationInfo(host.getPackageName(), PackageManager.GET_META_DATA);
        // Only the host can choose which of its own classes may be constructed.
        return app.metaData == null ? null : app.metaData.getString(Extension.KEY_LEGACY_ADAPTER + "." + owner);
    }

    public String getExtensionClassName() {
        return extensionClassName;
    }
}
