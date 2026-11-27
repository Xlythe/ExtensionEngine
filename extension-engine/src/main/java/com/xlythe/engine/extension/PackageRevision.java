package com.xlythe.engine.extension;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.RestrictTo;
import androidx.core.content.pm.PackageInfoCompat;
import java.util.Arrays;

/** Identifies the installed APK set even when the publisher reuses a version code. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public final class PackageRevision {
    private final String name;
    private final long updated;
    private final long version;
    private final int uid;
    private final String base;
    private final String[] splits;

    private PackageRevision(PackageInfo info) {
        ApplicationInfo app = info.applicationInfo;
        name = info.packageName;
        updated = info.lastUpdateTime;
        version = PackageInfoCompat.getLongVersionCode(info);
        uid = app.uid;
        base = app.sourceDir;
        splits = Build.VERSION.SDK_INT >= 21 && app.splitSourceDirs != null ? app.splitSourceDirs.clone() : null;
    }

    public static PackageRevision capture(Context context, String name) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(name, 0);
            if (info.applicationInfo != null) return new PackageRevision(info);
        } catch (PackageManager.NameNotFoundException gone) {
            throw new SecurityException("Package disappeared: " + name, gone);
        }
        throw new SecurityException("Missing application info for " + name);
    }

    public boolean isCurrent(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(name, 0);
            ApplicationInfo app = info.applicationInfo;
            return app != null && updated == info.lastUpdateTime && uid == app.uid
                    && version == PackageInfoCompat.getLongVersionCode(info) && TextUtils.equals(base, app.sourceDir)
                    && (Build.VERSION.SDK_INT < 21 || Arrays.equals(splits, app.splitSourceDirs));
        } catch (PackageManager.NameNotFoundException gone) {
            return false;
        }
    }
}
