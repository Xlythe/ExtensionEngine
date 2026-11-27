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
            selected = loadInstalled(context, packageName, className, legacy || adapter != null,
                    Build.VERSION.SDK_INT >= 26 ? activity.splitName : null);
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
                    selected = loadInstalled(context, adapter, className, true, null);
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

    private static Context loadInstalled(Context host, String packageName, String className, boolean checkEntry,
                                         String splitName)
            throws PackageManager.NameNotFoundException, ClassNotFoundException {
        Context extension = host.createPackageContext(packageName, CODE_FLAGS);
        if (Build.VERSION.SDK_INT >= 26 && (splitName != null || extension.getApplicationInfo().splitSourceDirs != null)) {
            return findSplitEntry(extension, splitName, className);
        }
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

    @androidx.annotation.RequiresApi(26)
    private static Context findSplitEntry(Context base, String splitName, String className)
            throws ClassNotFoundException, PackageManager.NameNotFoundException {
        Context selected = splitName == null ? base : base.createContextForSplit(splitName);
        try {
            selected.getClassLoader().loadClass(className);
        } catch (ClassNotFoundException missing) {
            // Bundle delivery can advertise the feature activity in the base manifest.
            String[] splits = base.getApplicationInfo().splitNames;
            if (splits == null) throw missing;
            for (String split : splits) {
                Context candidate = base.createContextForSplit(split);
                if (candidate.getClassLoader() == selected.getClassLoader()) continue;
                try {
                    candidate.getClassLoader().loadClass(className);
                    return IsolatedSplitLoader.forContext(base, candidate, split);
                } catch (ClassNotFoundException absentFromSplit) {
                    // Only the context that can resolve the entry supplies its resources.
                }
            }
            throw missing;
        }
        return IsolatedSplitLoader.forContext(base, selected, splitName);
    }

    private static String getBundledAdapter(Context host, String owner) throws PackageManager.NameNotFoundException {
        ApplicationInfo app = host.getPackageManager().getApplicationInfo(host.getPackageName(), PackageManager.GET_META_DATA);
        // Only the host can choose which of its own classes may be constructed.
        if (app.metaData != null) {
            String named = app.metaData.getString(owner + ".LEGACY_ADAPTER");
            if (named == null) named = app.metaData.getString(Extension.KEY_LEGACY_ADAPTER + "." + owner);
            if (named != null) return named;
        }
        String conventional = owner + Extension.CLASS_NAME;
        try {
            Class<?> type = host.getClassLoader().loadClass(conventional);
            return Extension.LegacyAdapter.class.isAssignableFrom(type) ? conventional : null;
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    public String getExtensionClassName() {
        return extensionClassName;
    }
}
