package com.xlythe.test.preview;

import android.Manifest;
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
    @Override protected String disclosure() {
        if (resultOnly()) return "Scan a barcode and share its text with Preview host?";
        return "Share live camera images with Preview host for this session? The host can save these images.";
    }
}
