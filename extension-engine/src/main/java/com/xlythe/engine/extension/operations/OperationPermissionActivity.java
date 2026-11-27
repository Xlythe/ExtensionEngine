package com.xlythe.engine.extension.operations;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.RequiresApi;

/** Extension-owned consent and runtime permission UI for a single operation. */
@RequiresApi(18)
public abstract class OperationPermissionActivity extends Activity {
    protected abstract String hostPackage();
    protected abstract String operation();
    protected abstract String permission();
    /** Describe exactly what the host will receive. */
    protected abstract String disclosure();

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
        if (!requesting) {
            new AlertDialog.Builder(this)
                    .setTitle(hostPackage())
                    .setMessage(disclosure())
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                    .setOnCancelListener(dialog -> finish())
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> authorize())
                    .show();
        }
    }

    private boolean granted() {
        return getPackageManager().checkPermission(permission(), getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    private void authorize() {
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
        if (requesting && results.length == 1 && results[0] == PackageManager.PERMISSION_GRANTED && granted()) {
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
