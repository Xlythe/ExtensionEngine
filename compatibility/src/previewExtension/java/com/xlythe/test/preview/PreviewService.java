package com.xlythe.test.preview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;

import com.xlythe.engine.extension.operations.ExtensionOperations;
import com.xlythe.engine.extension.operations.OperationApproval;
import java.util.Collections;

/** All camera and session state is confined to the worker looper. */
public final class PreviewService extends Service {
    private final HandlerThread thread = new HandlerThread("Camera preview");
    private Handler worker;
    private Session active;
    private volatile boolean bound;
    private boolean destroying;
    private int opening;

    private final IPreviewV1.Stub endpoint = new IPreviewV1.Stub() {
        @Override public void start(IBinder approval, Surface surface, IPreviewEvents events) {
            try {
                OperationApproval.consume(PreviewService.this, PreviewApproval.HOST, PreviewApproval.LIVE_PREVIEW, approval);
                if (!bound || surface == null || !surface.isValid() || events == null) {
                    throw new IllegalArgumentException("Invalid preview request");
                }
                if (!worker.post(() -> new Session(surface, events).open())) throw new IllegalStateException("Service stopped");
            } catch (RuntimeException e) {
                if (surface != null) surface.release();
                throw e;
            }
        }

        @Override public void stop(IPreviewEvents events) {
            ExtensionOperations.enforceCaller(PreviewService.this, PreviewApproval.HOST);
            worker.post(() -> {
                if (active != null && events != null && active.events.asBinder().equals(events.asBinder())) {
                    active.finish("Stopped");
                }
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        thread.start();
        worker = new Handler(thread.getLooper());
    }
    @Override public IBinder onBind(Intent intent) { bound = true; return endpoint; }
    @Override public boolean onUnbind(Intent intent) {
        bound = false;
        worker.post(() -> { if (active != null) active.finish("Disconnected"); });
        return false;
    }
    @Override public void onDestroy() {
        bound = false;
        worker.post(() -> {
            destroying = true;
            if (active != null) active.finish("Service stopped");
            quitWhenIdle();
        });
        super.onDestroy();
    }

    private void quitWhenIdle() {
        // A late onOpened must still run so it can close its camera.
        if (destroying && opening == 0) thread.quitSafely();
    }

    private final class Session implements IBinder.DeathRecipient {
        final Surface surface;
        final IPreviewEvents events;
        final Runnable timeout = () -> finish("Session expired");
        CameraDevice device;
        CameraCaptureSession capture;
        boolean closed;
        boolean linked;
        boolean waitingForDevice;

        Session(Surface surface, IPreviewEvents events) {
            this.surface = surface;
            this.events = events;
        }

        @SuppressLint("MissingPermission")
        void open() {
            if (!bound || active != null) { finish("Unavailable"); return; }
            active = this;
            try {
                events.asBinder().linkToDeath(this, 0);
                linked = true;
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
            } catch (RemoteException | CameraAccessException | RuntimeException failure) {
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

        @Override public void binderDied() { worker.post(() -> finish("Host disconnected")); }

        void finish(String reason) {
            if (closed) return;
            closed = true;
            worker.removeCallbacks(timeout);
            if (capture != null) capture.close();
            if (device != null) device.close();
            surface.release();
            if (active == this) active = null;
            if (linked) events.asBinder().unlinkToDeath(this, 0);
            try { events.ended(reason); } catch (RemoteException gone) { /* Host already exited. */ }
        }
    }
}
