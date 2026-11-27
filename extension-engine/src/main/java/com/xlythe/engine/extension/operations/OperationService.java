package com.xlythe.engine.extension.operations;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Runs approved, stateful operations. Both lifecycle hooks run on the extension main thread. */
@RequiresApi(18)
public abstract class OperationService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Session> active = new HashSet<>();
    private volatile boolean bound;

    /**
     * Receives the operation name and inputs from the host's OperationSession.open call.
     * Implement the Android calls here with this service's context and the extension's
     * permissions. Keep live resources in this process until the session closes.
     */
    protected abstract void onOpenSession(String operation, Bundle arguments, Session session);

    /** Release resources acquired in onOpenSession, including on host death or disconnection. */
    protected abstract void onCloseSession(Session session);

    private final IOperationService.Stub endpoint = new IOperationService.Stub() {
        @Override public IOperationSession open(String operation, IBinder proof, Bundle arguments,
                IOperationEvents events) {
            if (!bound) throw new IllegalStateException("Service is unbound");
            if (arguments == null || events == null) throw new IllegalArgumentException("Missing session arguments");
            OperationApproval.consume(OperationService.this, operation, proof);
            int owner = Binder.getCallingUid();
            Session session = new Session(events);
            main.post(() -> {
                if (!bound) {
                    try { events.ended("Service stopped"); } catch (RemoteException gone) { /* Host exited. */ }
                    return;
                }
                try {
                    events.asBinder().linkToDeath(session.deathRecipient, 0);
                } catch (RemoteException hostGone) { return; }
                active.add(session);
                try {
                    onOpenSession(operation, arguments, session);
                } catch (RuntimeException failed) {
                    session.finish("Operation failed");
                }
            });
            return new IOperationSession.Stub() {
                @Override public void close() {
                    if (Binder.getCallingUid() != owner) throw new SecurityException("Unexpected session owner");
                    session.finish("Stopped");
                }
            };
        }
    };

    /** Identity of one open operation. May be finished on any thread. */
    public final class Session {
        private final IOperationEvents events;
        private Session(IOperationEvents events) { this.events = events; }
        private final IBinder.DeathRecipient deathRecipient = () -> finish("Host disconnected");
        public void finish(String reason) { main.post(() -> end(this, reason)); }
    }

    private void end(Session session, String reason) {
        if (!active.remove(session)) return;
        session.events.asBinder().unlinkToDeath(session.deathRecipient, 0);
        try {
            onCloseSession(session);
        } finally {
            try { session.events.ended(reason); } catch (RemoteException gone) { /* Host exited. */ }
        }
    }

    @Override public final IBinder onBind(Intent intent) { bound = true; return endpoint; }

    @Override public final boolean onUnbind(Intent intent) {
        bound = false;
        for (Session session : new ArrayList<>(active)) end(session, "Host disconnected");
        return false;
    }

    @Override public void onDestroy() {
        bound = false;
        for (Session session : new ArrayList<>(active)) end(session, "Service stopped");
        super.onDestroy();
    }
}
