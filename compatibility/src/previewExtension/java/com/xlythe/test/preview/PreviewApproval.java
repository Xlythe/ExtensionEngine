package com.xlythe.test.preview;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import com.xlythe.engine.extension.operations.OperationPermissionActivity;

public final class PreviewApproval extends OperationPermissionActivity {
    static final String HOST = "com.xlythe.test.previewhost";
    static final String LIVE_PREVIEW = "camera.preview.v1";

    private boolean resultOnly() { return "scan".equals(getIntent().getStringExtra("operation")); }

    @Override protected void onAuthorized() {
        if (resultOnly()) {
            startActivity(new Intent(this, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT));
            finish();
        } else {
            super.onAuthorized();
        }
    }

    @Override protected String hostPackage() { return HOST; }
    @Override protected String operation() { return resultOnly() ? "barcode.scan.v1" : LIVE_PREVIEW; }
    @Override protected String permission() { return Manifest.permission.CAMERA; }
    @Override protected void onRequestOperation() {
        if (resultOnly()) {
            requestOperationPermission();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(HOST)
                .setMessage("Share live camera images with Preview host for this session? The host can save these images.")
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> requestOperationPermission())
                .show();
    }
}
