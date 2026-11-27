package com.xlythe.engine.extension.operations;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;

import com.xlythe.engine.extension.ExtensionResources;
import com.xlythe.engine.extension.Security;

/** Explicit IPC endpoints advertised on a trusted extension's registration activity. */
public final class ExtensionOperations {
    public static final String SERVICE = "com.xlythe.engine.extension.OPERATION_SERVICE";
    public static final String APPROVAL = "com.xlythe.engine.extension.OPERATION_APPROVAL";

    private ExtensionOperations() {}

    /**
     * Returns the explicit activity advertised by a registered, trusted extension for this host.
     * Launch it for result so the extension can verify the calling package. Any operation-specific
     * extras and the result payload are part of the contract between the two apps.
     * @throws SecurityException if the installed extension fails the signing policy
     * @throws IllegalArgumentException if it has no registered approval endpoint
     */
    public static Intent approvalIntent(Activity host, String extension) {
        return endpoint(host, extension, APPROVAL);
    }

    /** Bind with foreground process importance for the lifetime of a visible operation. */
    public static boolean bind(Activity host, String extension, ServiceConnection connection) {
        return bind(host, extension, connection, /* shareWhileInUseCapabilities= */ false);
    }

    /**
     * Opt into Android's while-in-use capability propagation only for an operation that needs it.
     * The extension must hold its own permissions. Both overloads tie process importance to the
     * host activity. Passing true additionally sets {@link Context#BIND_INCLUDE_CAPABILITIES}
     * on API 29+. Use it only while the user is actively using the selected feature. Release the
     * connection with the same activity's {@link Activity#unbindService(ServiceConnection)}.
     */
    public static boolean bind(Activity host, String extension, ServiceConnection connection,
                               boolean shareWhileInUseCapabilities) {
        int flags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_ADJUST_WITH_ACTIVITY;
        if (shareWhileInUseCapabilities && Build.VERSION.SDK_INT >= 29) flags |= Context.BIND_INCLUDE_CAPABILITIES;
        return host.bindService(endpoint(host, extension, SERVICE), connection, flags);
    }

    private static Intent endpoint(Activity host, String extension, String key) {
        Security.enforceTrustedPackage(host, extension);
        ActivityInfo registration = ExtensionResources.getActivityInfo(host, extension);
        String name = registration == null || registration.metaData == null ? null : registration.metaData.getString(key);
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Missing extension endpoint " + key);
        ComponentName component = new ComponentName(extension, name.startsWith(".") ? extension + name : name);
        return new Intent().setComponent(component);
    }

    /** Invoke on the incoming Binder thread, before scheduling any work. */
    public static void enforceCaller(Context extension, String expectedHost) {
        enforceHost(extension, expectedHost, Binder.getCallingUid());
    }

    static void enforceHost(Context extension, String host, int uid) {
        Security.enforceTrustedPackage(extension, host);
        try {
            PackageInfo info = extension.getPackageManager().getPackageInfo(host, 0);
            if (info.applicationInfo != null && info.applicationInfo.uid == uid && info.sharedUserId == null) return;
        } catch (PackageManager.NameNotFoundException disappeared) {
            throw new SecurityException("Host is no longer installed", disappeared);
        }
        throw new SecurityException("Expected the dedicated UID of " + host);
    }
}
