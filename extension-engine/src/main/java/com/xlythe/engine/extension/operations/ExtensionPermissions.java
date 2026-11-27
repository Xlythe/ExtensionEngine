package com.xlythe.engine.extension.operations;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.xlythe.engine.extension.ExtensionResources;
import com.xlythe.engine.extension.Security;

/** Check or request a runtime permission belonging to a registered extension. */
public final class ExtensionPermissions {
    static final String PERMISSION = "com.xlythe.engine.extension.PERMISSION";

    private ExtensionPermissions() {}

    private static void verifyExtension(Context context, String extension) {
        Security.enforceTrustedPackage(context, extension);
        if (ExtensionResources.getActivityInfo(context, extension) == null) {
            throw new IllegalArgumentException("Package is not registered for this host");
        }
    }

    /** Returns PackageManager.PERMISSION_GRANTED or PERMISSION_DENIED for the extension UID. */
    public static int checkPermission(Context host, String extension, String permission) {
        verifyExtension(host, extension);
        return host.getPackageManager().checkPermission(permission, extension);
    }

    /**
     * Register in the host with registerForActivityResult and launch with a Manifest.permission
     * name. Android records the user's decision against the extension. A successful result does
     * not authorize a host to receive sensitive data or to start a protected service operation.
     */
    @RequiresApi(18)
    public static final class RequestPermission extends ActivityResultContract<String, Boolean> {
        private final String extension;

        public RequestPermission(String extension) { this.extension = extension; }

        @NonNull @Override public Intent createIntent(@NonNull Context host, @NonNull String permission) {
            verifyExtension(host, extension);
            return new Intent().setComponent(new ComponentName(extension,
                    ExtensionPermissionRequestActivity.class.getName())).putExtra(PERMISSION, permission);
        }

        @Override public Boolean parseResult(int code, @Nullable Intent result) {
            return code == Activity.RESULT_OK;
        }
    }
}
