package com.xlythe.test.preview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import com.xlythe.engine.extension.operations.ExtensionPermissions;
import com.xlythe.engine.extension.operations.OperationSession;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xlythe.engine.extension.operations.ExtensionOperations;

/** A two-APK fixture. The host manifest has no CAMERA permission. */
public final class PreviewActivity extends ComponentActivity implements TextureView.SurfaceTextureListener {
    private static final String PROVIDER = "com.xlythe.test.previewextension";
    private static final int APPROVE = 7;
    private static final int SCAN = 8;
    private TextureView preview;
    private TextView status;
    private Button action;
    private boolean resumed;
    private Intent approval;
    private OperationSession connection;
    private Surface surface;
    private int frames;
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ExtensionPermissions.RequestPermission(PROVIDER), granted -> {
                if (granted) scan();
                else status.setText("Scan canceled");
            });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("Camera preview from extension");
        action = new Button(this);
        action.setText("Share live preview");
        action.setOnClickListener(v -> {
            if (connection != null) { stop("Stopped"); return; }
            try {
                startActivityForResult(ExtensionOperations.approvalIntent(this, PROVIDER), APPROVE);
            } catch (RuntimeException unavailable) { stop("Provider unavailable"); }
        });
        Button scan = new Button(this);
        scan.setText("Scan barcode in extension");
        scan.setOnClickListener(v -> {
            try {
                if (ExtensionPermissions.checkPermission(this, PROVIDER, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) scan();
                else cameraPermission.launch(Manifest.permission.CAMERA);
            } catch (RuntimeException unavailable) { stop("Provider unavailable"); }
        });
        preview = new TextureView(this);
        preview.setVisibility(View.INVISIBLE);
        preview.setSurfaceTextureListener(this);
        layout.addView(status);
        layout.addView(action);
        layout.addView(scan);
        layout.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
    }

    private void scan() {
        try {
            Intent intent = ExtensionOperations.approvalIntent(this, PROVIDER);
            startActivityForResult(intent.putExtra("operation", "scan"), SCAN);
        } catch (RuntimeException unavailable) { stop("Provider unavailable"); }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == SCAN) {
            status.setText(result == RESULT_OK && data != null ? data.getStringExtra("barcode") : "Scan canceled");
            return;
        }
        if (code != APPROVE) return;
        approval = result == RESULT_OK ? data : null;
        if (approval == null) stop("Sharing declined");
        else connect();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; connect(); }
    @Override protected void onPause() { resumed = false; stop("Stopped"); super.onPause(); }

    private void connect() {
        if (!resumed || approval == null || connection != null) return;
        preview.setVisibility(View.VISIBLE);
        if (!preview.isAvailable()) return;
        SurfaceTexture texture = preview.getSurfaceTexture();
        if (texture == null) return;
        texture.setDefaultBufferSize(640, 480);
        surface = new Surface(texture);
        Bundle arguments = new Bundle();
        arguments.putParcelable("surface", surface);
        try {
            frames = 0;
            connection = OperationSession.open(this, PROVIDER, "camera.preview.v1", approval, arguments,
                    /* shareWhileInUseCapabilities= */ true, this::stop);
            approval = null;
            action.setText("Stop sharing");
            status.setText("Connecting");
        } catch (RuntimeException denied) { stop("Binding denied"); }
    }

    private void stop(String reason) {
        approval = null;
        if (connection != null) connection.close();
        connection = null;
        if (surface != null) surface.release();
        surface = null;
        preview.setVisibility(View.INVISIBLE);
        action.setText("Share live preview");
        status.setText(reason);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) { connect(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (connection != null) stop("Preview closed");
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (connection != null && ++frames == 5) status.setText("Live camera preview");
    }
}
