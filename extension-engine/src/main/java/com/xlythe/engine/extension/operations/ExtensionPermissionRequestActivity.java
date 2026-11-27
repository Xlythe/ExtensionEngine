package com.xlythe.engine.extension.operations;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.annotation.RequiresApi;
import java.util.Arrays;

/** Manifest-merged framework activity for an extension-owned runtime permission request. */
@RequiresApi(18)
public final class ExtensionPermissionRequestActivity extends OperationPermissionActivity {
    @Override protected String permission() {
        return getIntent().getStringExtra(ExtensionPermissions.PERMISSION);
    }

    @Override protected String operation() { return permission(); }

    @Override protected void onRequestOperation() {
        try {
            PackageInfo app = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (permission() != null && app.requestedPermissions != null
                    && Arrays.asList(app.requestedPermissions).contains(permission())) {
                requestOperationPermission();
                return;
            }
        } catch (PackageManager.NameNotFoundException missing) {
            // The package is no longer available.
        }
        finish();
    }

    @Override protected void onAuthorized() {
        setResult(RESULT_OK);
        finish();
    }
}
