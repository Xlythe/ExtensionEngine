package com.xlythe.engine.extension.operations;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;

/** One-use user approval. The issuing activity and receiving service must run in the same process. */
@RequiresApi(18)
public final class OperationApproval extends Binder {
    private static final String EXTRA = "com.xlythe.engine.extension.APPROVAL_TOKEN";
    private final String host;
    private final String operation;
    private final long issuedAt = SystemClock.elapsedRealtime();
    private boolean used;

    private OperationApproval(String host, String operation) {
        this.host = host;
        this.operation = operation;
    }

    public static void verifyLaunch(Activity activity, String expectedHost) {
        if (!expectedHost.equals(activity.getCallingPackage())) throw new SecurityException("Unexpected caller");
        try {
            int uid = activity.getPackageManager().getApplicationInfo(expectedHost, 0).uid;
            ExtensionOperations.enforceHost(activity, expectedHost, uid);
        } catch (android.content.pm.PackageManager.NameNotFoundException gone) {
            throw new SecurityException("Host disappeared", gone);
        }
    }

    /** Call only from the extension's approval UI after the user accepts this operation. */
    public static Intent approved(Activity activity, String expectedHost, String operation) {
        verifyLaunch(activity, expectedHost);
        Bundle extras = new Bundle();
        extras.putBinder(EXTRA, new OperationApproval(expectedHost, operation));
        return new Intent().putExtras(extras);
    }

    public static IBinder token(Intent result) {
        Bundle extras = result == null ? null : result.getExtras();
        return extras == null ? null : extras.getBinder(EXTRA);
    }

    /** Consume on the incoming Binder thread. Foreign and pre-restart tokens cannot be local instances. */
    public static void consume(Context extension, String expectedHost, String operation, IBinder token) {
        ExtensionOperations.enforceCaller(extension, expectedHost);
        if (!(token instanceof OperationApproval)) throw new SecurityException("Missing local approval");
        OperationApproval approval = (OperationApproval) token;
        synchronized (approval) {
            if (approval.used || !approval.host.equals(expectedHost) || !approval.operation.equals(operation)
                    || SystemClock.elapsedRealtime() - approval.issuedAt >= 60_000) {
                throw new SecurityException("Invalid or expired approval");
            }
            approval.used = true;
        }
    }
}
