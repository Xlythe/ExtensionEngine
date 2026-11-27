package com.xlythe.engine.extension.operations;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import androidx.annotation.MainThread;
import androidx.annotation.RequiresApi;
import com.xlythe.engine.extension.Security;

/** Host-side handle for one approved operation. Open and close on the host activity's main thread. */
@RequiresApi(18)
public final class OperationSession {
    public interface Listener { void onEnded(String reason); }

    private final Activity host;
    private final String extension;
    private final String operation;
    private final Bundle arguments;
    private final Listener listener;
    private IBinder approval;
    private IOperationSession remote;
    private boolean bound;
    private boolean closed;

    private OperationSession(Activity host, String extension, String operation, Intent result,
            Bundle arguments, Listener listener) {
        this.host = host;
        this.extension = extension;
        this.operation = operation;
        this.approval = OperationApproval.token(result);
        if (approval == null) throw new SecurityException("Missing operation approval");
        this.arguments = new Bundle(arguments);
        this.listener = listener;
    }

    /**
     * Start a session once the approving activity returns RESULT_OK and the host is visible.
     * Keep any Surface or other parcelable input valid until close or onEnded. If the service
     * cannot bind, this method throws. Later service failures arrive through the listener.
     */
    @MainThread
    public static OperationSession open(Activity host, String extension, String operation, Intent result,
            Bundle arguments, boolean shareWhileInUseCapabilities, Listener listener) {
        OperationSession session = new OperationSession(host, extension, operation, result, arguments, listener);
        session.bound = ExtensionOperations.bind(host, extension, session.connection, shareWhileInUseCapabilities);
        if (!session.bound) throw new IllegalStateException("Operation service unavailable");
        return session;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed) return;
            try {
                Security.enforceTrustedPackage(host, extension);
                remote = IOperationService.Stub.asInterface(service).open(operation, approval, arguments,
                        new IOperationEvents.Stub() {
                            @Override public void ended(String reason) {
                                ExtensionOperations.enforceCaller(host, extension);
                                host.runOnUiThread(() -> disconnected(reason));
                            }
                        });
                approval = null;
            } catch (RemoteException | RuntimeException failure) { disconnected("Session rejected"); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { disconnected("Provider disconnected"); }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };

    private void disconnected(String reason) {
        if (closed) return;
        close();
        listener.onEnded(reason);
    }

    /** End the operation and release foreground importance. Safe to call multiple times. */
    @MainThread public void close() {
        if (closed) return;
        closed = true;
        approval = null;
        try {
            if (remote != null) remote.close();
        } catch (RemoteException | RuntimeException gone) { /* Service exited. */ }
        finally {
            remote = null;
            if (bound) host.unbindService(connection);
        }
    }
}
