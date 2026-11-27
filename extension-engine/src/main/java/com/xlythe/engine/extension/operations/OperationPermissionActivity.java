package com.xlythe.engine.extension.operations;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.RequiresApi;
import com.xlythe.engine.extension.ExtensionResources;

/**
 * Verifies an activity-for-result caller before an extension handles a permission-gated operation.
 * Override {@link #onRequestOperation()} to present the extension's own UI before continuing a
 * user-initiated action. This activity supplies no layout. Android owns the runtime permission
 * dialog and applies the user's choice to the extension package.
 *
 * <p>Declare the permission in the extension manifest. Override {@link #onAuthorized()} to do
 * work in the extension activity, or use its default result to authorize one Binder operation.
 * Canceling the activity or denying the permission returns {@link #RESULT_CANCELED}.
 */
@RequiresApi(18)
public abstract class OperationPermissionActivity extends Activity {
    /** The framework supplies the caller identity. Only hosts registered by this extension qualify. */
    protected String hostPackage() {
        String caller = getCallingPackage();
        if (caller == null || ExtensionResources.getActivityInfo(
                this, getPackageName(), caller) == null) {
            throw new SecurityException("Unregistered host");
        }
        return caller;
    }
    protected abstract String operation();
    protected abstract String permission();
    /**
     * Called after caller verification, including after recreation unless a system request is
     * pending. Call {@link #requestOperationPermission()} when the user chooses the operation.
     * For sensitive data sharing, explain what the host will receive before continuing.
     */
    protected void onRequestOperation() { requestOperationPermission(); }

    private boolean requesting;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        try {
            OperationApproval.verifyLaunch(this, hostPackage());
        } catch (SecurityException rejected) {
            finish();
            return;
        }
        requesting = state != null && state.getBoolean("requesting");
        if (!requesting) onRequestOperation();
    }

    private boolean granted() {
        return getPackageManager().checkPermission(permission(), getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Continue the selected operation. An existing grant proceeds immediately. A missing grant
     * on API 23+ requests the declared permission through Android. On older releases a missing
     * install-time grant cancels the operation. Duplicate calls during a system request are ignored.
     */
    protected final void requestOperationPermission() {
        if (requesting || isFinishing()) return;
        if (granted()) {
            complete();
        } else if (Build.VERSION.SDK_INT >= 23) {
            requesting = true;
            requestPermissions(new String[]{permission()}, 1);
        } else {
            finish();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 1) return;
        if (!requesting) return;
        requesting = false;
        if (results.length == 1 && results[0] == PackageManager.PERMISSION_GRANTED && granted()) {
            complete();
        } else {
            finish();
        }
    }

    private void complete() {
        try {
            OperationApproval.verifyLaunch(this, hostPackage());
            onAuthorized();
        } catch (SecurityException hostChanged) {
            finish();
        }
    }

    /** Override to execute a visible operation entirely within the extension. */
    protected void onAuthorized() {
        setResult(RESULT_OK, OperationApproval.approved(this, hostPackage(), operation()));
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("requesting", requesting);
    }
}
