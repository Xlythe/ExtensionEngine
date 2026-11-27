package com.xlythe.engine.extension;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.Nullable;

import java.io.Serializable;

public class App implements Serializable {
    private static final long serialVersionUID = -7796311962836649402L;
    private final String name;
    private final String packageName;

    // Only an in-process discovery result can select an alternate action namespace.
    final transient String actionPackage;

    public App(String name, String packageName) {
        this(name, packageName, null);
    }

    App(String name, String packageName, String actionPackage) {
        this.actionPackage = actionPackage;
        this.name = name;
        this.packageName = packageName;
    }

    public static boolean doesPackageExists(Context context, String targetPackage) {
        try {
            context.getPackageManager().getApplicationInfo(targetPackage, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Returns the installed application, including extensions without a launcher activity. */
    @Nullable
    public static App getApp(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        Intent launch = manager.getLaunchIntentForPackage(packageName);
        ResolveInfo activity = launch == null ? null : manager.resolveActivity(launch, 0);
        if (activity != null) return new App(activity.loadLabel(manager).toString(), packageName);
        try {
            return new App(manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString(), packageName);
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        }
    }

    public String getName() {
        return name;
    }

    /** Returns null if this package has no launchable activity. */
    @Nullable
    public Intent getIntent(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) return null;
        int flags = Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
        intent.setFlags(flags);
        return intent;
    }

    public String getPackageName() {
        return packageName;
    }
}
