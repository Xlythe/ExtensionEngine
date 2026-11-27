package com.xlythe.engine.extension.operations;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 24})
public class OperationApprovalTest {
    private static final String HOST = "test.host";
    private static final int HOST_UID = 21345;
    private Activity extension;

    @Before public void setUp() {
        extension = Robolectric.buildActivity(Activity.class).setup().get();
        install(extension.getPackageName(), Process.myUid(), 1, null);
        install(HOST, HOST_UID, 1, null);
        shadowOf(extension).setCallingPackage(HOST);
        ShadowBinder.setCallingUid(HOST_UID);
    }

    private void install(String pkg, int uid, int signer, String sharedUid) {
        PackageInfo info = new PackageInfo();
        info.packageName = pkg;
        info.sharedUserId = sharedUid;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.uid = uid;
        info.applicationInfo.packageName = pkg;
        info.signatures = new Signature[]{new Signature(new byte[]{(byte) signer})};
        shadowOf(extension.getPackageManager()).installPackage(info);
    }

    private IBinder grant() {
        return OperationApproval.token(OperationApproval.approved(extension, HOST, "preview"));
    }

    @Test public void singleUseBoundToOperationAndCaller() {
        IBinder token = grant();
        assertThrows(SecurityException.class, () -> OperationApproval.consume(extension, HOST, "photo", token));
        ShadowBinder.setCallingUid(HOST_UID + 1);
        assertThrows(SecurityException.class, () -> OperationApproval.consume(extension, HOST, "preview", token));
        ShadowBinder.setCallingUid(HOST_UID);
        OperationApproval.consume(extension, HOST, "preview", token);
        assertThrows(SecurityException.class, () -> OperationApproval.consume(extension, HOST, "preview", token));
    }

    @Test public void rejectsExpiredForeignAndReplacedPeers() {
        IBinder expired = grant();
        SystemClock.sleep(60_000);
        assertThrows(SecurityException.class, () -> OperationApproval.consume(extension, HOST, "preview", expired));
        assertThrows(SecurityException.class, () -> OperationApproval.consume(extension, HOST, "preview", new Binder()));
        IBinder pending = grant();
        install(HOST, HOST_UID, 2, null);
        assertThrows(SecurityException.class, () -> OperationApproval.consume(extension, HOST, "preview", pending));
    }

    @Test public void reinstallWithSameSignerUidAndVersionRequiresNewApproval() throws Exception {
        IBinder old = grant();
        PackageInfo update = extension.getPackageManager().getPackageInfo(HOST, android.content.pm.PackageManager.GET_SIGNATURES);
        update.lastUpdateTime++;
        shadowOf(extension.getPackageManager()).installPackage(update);
        assertThrows(SecurityException.class, () -> OperationApproval.consume(extension, HOST, "preview", old));
        OperationApproval.consume(extension, HOST, "preview", grant());
    }

    public static class RecordingService extends OperationService {
        int opened;
        int closed;

        @Override protected void onOpenSession(String operation, Bundle args, Session session) {
            opened++;
        }
        @Override protected void onCloseSession(Session session) { closed++; }
    }

    @Test public void serviceChecksApprovalAndClosesEachSessionOnce() throws Exception {
        RecordingService service = Robolectric.buildService(RecordingService.class).create().get();
        IOperationService endpoint = IOperationService.Stub.asInterface(service.onBind(new Intent()));
        IOperationEvents events = new IOperationEvents.Stub() {
            @Override public void ended(String reason) {}
        };
        IBinder approval = grant();
        assertThrows(SecurityException.class, () -> endpoint.open("other", approval, new Bundle(), events));
        IOperationSession session = endpoint.open("preview", approval, new Bundle(), events);
        assertThrows(SecurityException.class, () -> endpoint.open("preview", approval, new Bundle(), events));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, service.opened);
        ShadowBinder.setCallingUid(HOST_UID + 1);
        assertThrows(SecurityException.class, session::close);
        ShadowBinder.setCallingUid(HOST_UID);
        session.close();
        session.close();
        shadowOf(Looper.getMainLooper()).idle();
        service.onDestroy();
        assertEquals(1, service.closed);
    }

    @Test public void rejectsWrongLaunchAndSharedUid() {
        shadowOf(extension).setCallingPackage("test.sibling");
        assertThrows(SecurityException.class, this::grant);
        shadowOf(extension).setCallingPackage(HOST);
        install(HOST, HOST_UID, 1, "test.shared");
        assertThrows(SecurityException.class, this::grant);
    }
}
