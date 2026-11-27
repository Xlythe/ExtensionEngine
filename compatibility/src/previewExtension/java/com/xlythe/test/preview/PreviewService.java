package com.xlythe.test.preview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import com.xlythe.engine.extension.operations.OperationService;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.Collections;

/** All camera and session state is confined to the worker looper. */
public final class PreviewService extends OperationService {
    private final HandlerThread thread = new HandlerThread("Camera preview");
    private Handler worker;
    private Preview active;
    private boolean destroying;
    private int opening;

    @Override protected void onOpenSession(String operation, Bundle arguments, Session session) {
        Surface surface = arguments.getParcelable("surface");
        if (!PreviewApproval.LIVE_PREVIEW.equals(operation) || surface == null || !surface.isValid()) {
            if (surface != null) surface.release();
            session.finish("Invalid preview request");
            return;
        }
        worker.post(() -> new Preview(surface, session).open());
    }

    @Override protected void onCloseSession(Session session) {
        worker.post(() -> {
            if (active != null && active.session == session) active.finish("Stopped");
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        thread.start();
        worker = new Handler(thread.getLooper());
    }
    @Override public void onDestroy() {
        super.onDestroy();
        worker.post(() -> {
            destroying = true;
            if (active != null) active.finish("Service stopped");
            quitWhenIdle();
        });
    }

    private void quitWhenIdle() {
        // A late onOpened must still run so it can close its camera.
        if (destroying && opening == 0) thread.quitSafely();
    }

    private final class Preview {
        final Surface surface;
        final Session session;
        final Runnable timeout = () -> finish("Session expired");
        CameraDevice device;
        CameraCaptureSession capture;
        boolean closed;
        boolean waitingForDevice;

        Preview(Surface surface, Session session) {
            this.surface = surface;
            this.session = session;
        }

        @SuppressLint("MissingPermission")
        void open() {
            if (destroying || active != null) { finish("Unavailable"); return; }
            active = this;
            try {
                if (getPackageManager().checkPermission(Manifest.permission.CAMERA, getPackageName())
                        != PackageManager.PERMISSION_GRANTED) { finish("Camera permission required"); return; }
                CameraManager cameras = (CameraManager) getSystemService(CAMERA_SERVICE);
                String[] ids = cameras.getCameraIdList();
                if (ids.length == 0) { finish("No camera"); return; }
                worker.postDelayed(timeout, 60_000);
                waitingForDevice = true;
                opening++;
                cameras.openCamera(ids[0], new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice opened) {
                        openFinished();
                        if (closed) { opened.close(); return; }
                        device = opened;
                        configure();
                    }
                    @Override public void onDisconnected(CameraDevice lost) { openFinished(); lost.close(); finish("Camera disconnected"); }
                    @Override public void onError(CameraDevice failed, int error) { openFinished(); failed.close(); finish("Camera error " + error); }
                }, worker);
            } catch (CameraAccessException | RuntimeException failure) {
                openFinished();
                finish("Camera unavailable");
            }
        }

        void openFinished() {
            if (waitingForDevice) {
                waitingForDevice = false;
                opening--;
                quitWhenIdle();
            }
        }

        void configure() {
            try {
                device.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession ready) {
                        if (closed) { ready.close(); return; }
                        capture = ready;
                        try {
                            CaptureRequest.Builder preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            preview.addTarget(surface);
                            capture.setRepeatingRequest(preview.build(), null, worker);
                        } catch (CameraAccessException | RuntimeException failure) { finish("Preview unavailable"); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession failed) { failed.close(); finish("Unsupported surface"); }
                }, worker);
            } catch (CameraAccessException | RuntimeException failure) { finish("Configuration failed"); }
        }

        void finish(String reason) {
            if (closed) return;
            closed = true;
            worker.removeCallbacks(timeout);
            if (capture != null) capture.close();
            if (device != null) device.close();
            surface.release();
            if (active == this) active = null;
            session.finish(reason);
        }
    }
}
