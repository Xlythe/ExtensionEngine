package com.xlythe.test.preview;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import com.xlythe.engine.extension.operations.OperationPermissionActivity;

public final class PreviewApproval extends OperationPermissionActivity {
    @Override protected String operation() {
        String operation = getIntent().getStringExtra(PreviewContract.OPERATION);
        // Older hosts requested the live preview without an operation extra.
        return operation == null ? PreviewContract.LIVE_PREVIEW : operation;
    }

    @Override protected String permission() {
        return PreviewContract.VIBRATE.equals(operation()) ? Manifest.permission.VIBRATE : Manifest.permission.CAMERA;
    }

    @Override protected void onAuthorized() {
        switch (operation()) {
            case PreviewContract.VIBRATE:
                pulse();
                finish();
                break;
            case PreviewContract.SCAN:
                startActivity(new Intent(this, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT));
                finish();
                break;
            case PreviewContract.LIVE_PREVIEW:
                super.onAuthorized();
                break;
            default:
                finish();
        }
    }

    private void pulse() {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = getSystemService(VibratorManager.class);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(100);
        }
        setResult(RESULT_OK);
    }

    @Override protected void onRequestOperation() {
        switch (operation()) {
            case PreviewContract.VIBRATE:
            case PreviewContract.SCAN:
                requestOperationPermission();
                return;
            case PreviewContract.LIVE_PREVIEW:
                new AlertDialog.Builder(this)
                        .setTitle(hostPackage())
                        .setMessage("Share live camera images with Preview host for this session? The host can save these images.")
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                        .setOnCancelListener(dialog -> finish())
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> requestOperationPermission())
                        .show();
                return;
            default:
                finish();
        }
    }
}
