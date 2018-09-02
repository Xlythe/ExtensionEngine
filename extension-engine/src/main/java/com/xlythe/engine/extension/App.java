package com.xlythe.engine.extension;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.Serializable;

public class App implements Serializable {
    private static final long serialVersionUID = -7796311962836649402L;
    private final String name;
    private final String packageName;

    public App(String name, String packageName) {
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

    public static App getApp(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        ResolveInfo info = manager.resolveActivity(manager.getLaunchIntentForPackage(packageName), 0);
        return new App(info.loadLabel(manager).toString(), packageName);
    }

    public String getName() {
        return name;
    }

    public Intent getIntent(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        int flags = Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
        intent.setFlags(flags);
        return intent;
    }

    public String getPackageName() {
        return packageName;
    }
}
