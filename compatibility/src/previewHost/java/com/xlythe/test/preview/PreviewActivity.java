package com.xlythe.test.preview;

import android.app.Activity;
import android.content.*;
import android.graphics.SurfaceTexture;
import android.os.*;
import android.view.*;
import android.widget.*;

import com.xlythe.engine.extension.Security;
import com.xlythe.engine.extension.operations.ExtensionOperations;
import com.xlythe.engine.extension.operations.OperationApproval;

/** A two-APK fixture. The host manifest has no CAMERA permission. */
public final class PreviewActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String PROVIDER = "com.xlythe.test.previewextension";
    private static final int APPROVE = 7;
    private static final int SCAN = 8;
    private TextureView preview;
    private TextView status;
    private Button action;
    private boolean resumed;
    private IBinder approval;
    private Connection connection;

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
                Intent intent = ExtensionOperations.approvalIntent(this, PROVIDER);
                startActivityForResult(intent.putExtra("operation", "scan"), SCAN);
            } catch (RuntimeException unavailable) { stop("Provider unavailable"); }
        });
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        layout.addView(status);
        layout.addView(action);
        layout.addView(scan);
        layout.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == SCAN) {
            status.setText(result == RESULT_OK && data != null ? data.getStringExtra("barcode") : "Scan canceled");
            return;
        }
        if (code != APPROVE) return;
        approval = result == RESULT_OK ? OperationApproval.token(data) : null;
        if (approval == null) stop("Sharing declined");
        else connect();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; connect(); }
    @Override protected void onPause() { resumed = false; stop("Stopped"); super.onPause(); }

    private void connect() {
        if (!resumed || approval == null || connection != null || !preview.isAvailable()) return;
        Connection next = new Connection();
        connection = next;
        try {
            next.bound = ExtensionOperations.bind(this, PROVIDER, next, true);
            if (!next.bound) { stop("Provider unavailable"); return; }
            action.setText("Stop sharing");
            status.setText("Connecting");
        } catch (RuntimeException denied) { stop("Binding denied"); }
    }

    private void stop(String reason) {
        approval = null;
        Connection old = connection;
        connection = null;
        if (old != null) {
            try { if (old.remote != null) old.remote.stop(old.events); }
            catch (RemoteException | RuntimeException gone) { /* Session was already closed. */ }
            if (old.bound) unbindService(old);
        }
        action.setText("Share live preview");
        status.setText(reason);
    }

    private final class Connection implements ServiceConnection {
        boolean bound;
        IPreviewV1 remote;
        int frames;
        final IPreviewEvents events = new IPreviewEvents.Stub() {
            @Override public void ended(String reason) {
                ExtensionOperations.enforceCaller(PreviewActivity.this, PROVIDER);
                runOnUiThread(() -> { if (connection == Connection.this) stop(reason); });
            }
        };

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (connection != this) return;
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) { stop("No visible surface"); return; }
            texture.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(texture);
            try {
                Security.enforceTrustedPackage(PreviewActivity.this, name.getPackageName());
                remote = IPreviewV1.Stub.asInterface(binder);
                remote.start(approval, surface, events);
                approval = null;
            } catch (RemoteException | RuntimeException failure) { stop("Session rejected"); }
            finally { surface.release(); }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (connection == this) stop("Provider disconnected");
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) { connect(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { stop("Preview closed"); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (connection != null && ++connection.frames == 5) status.setText("Live camera preview");
    }
}
