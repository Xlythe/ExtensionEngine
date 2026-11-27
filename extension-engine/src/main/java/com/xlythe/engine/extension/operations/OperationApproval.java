package com.xlythe.engine.extension.operations;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;

/**
 * Approval for one operation by a specific host. The host transports the opaque Binder returned
 * by {@link #token(Intent)} to the extension's typed service method. The issuer and service must
 * run in the same extension process. Process death invalidates outstanding approvals.
 */
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

    /**
     * Verify the transaction caller and consume its approval before posting work to another thread.
     * The token must have been issued in this process for the expected host and operation within
     * the last 60 seconds. Successful consumption is atomic and a second use fails. The operation
     * must still check its current Android permission and handle revocation.
     * @throws SecurityException if caller verification or token validation fails
     */
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
