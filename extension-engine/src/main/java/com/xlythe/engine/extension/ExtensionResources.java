package com.xlythe.engine.extension;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;

import static com.xlythe.engine.extension.Extension.TAG;

@SuppressLint("DiscouragedApi")
public class ExtensionResources {
    public static Drawable getDrawable(Context context, String extension, String name) {
        if (name == null) return null;

        // Grab from extension
        int id = getResources(context, extension).getIdentifier(name, "drawable", extension);
        if (id != 0) {
            return getResources(context, extension).getDrawable(id);
        }

        // No drawable in the extension, grab from app
        id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        if (id != 0) {
            return context.getResources().getDrawable(id);
        }

        return null;
    }

    /**
     * Grabs the Resources from packageName
     */
    private static Resources getResources(Context context, String extension) {
        try {
            return context.getPackageManager().getResourcesForApplication(extension);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get extension resources", e);
            return context.getResources();
        }
    }

    /**
     * Returns the extension activity registered by this package, or null if none is visible.
     */
    public static ActivityInfo getActivityInfo(Context context, String packageName) {
        return getActivityInfo(context, packageName, context.getPackageName());
    }

    static boolean isOwnedActivity(ActivityInfo activity, String packageName) {
        return packageName != null && !packageName.isEmpty() && packageName.equals(activity.packageName)
                && activity.applicationInfo != null && packageName.equals(activity.applicationInfo.packageName);
    }

    public static ActivityInfo getActivityInfo(Context context, String packageName, String actionPackage) {
        PackageManager manager = context.getPackageManager();

        Intent intent = new Intent(actionPackage + Extension.ACTION_EXTENSION);
        intent.setPackage(packageName);
        try {
            for (ResolveInfo info : manager.queryIntentActivities(intent, PackageManager.GET_META_DATA)) {
                if (info.activityInfo != null && isOwnedActivity(info.activityInfo, packageName)) {
                    return info.activityInfo;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get activity info", e);
        }

        return null;
    }
}
