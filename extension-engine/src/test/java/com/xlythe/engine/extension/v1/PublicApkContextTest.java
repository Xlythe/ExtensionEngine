package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.view.LayoutInflater;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23)
public class PublicApkContextTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private final Context host = RuntimeEnvironment.getApplication();
    private final PackageInfo peer = new PackageInfo();
    private Context extension;

    @Before public void install() throws Exception {
        peer.packageName = "test.cache.peer";
        peer.applicationInfo = new ApplicationInfo();
        peer.applicationInfo.packageName = peer.packageName;
        peer.applicationInfo.sourceDir = files.newFile("base.apk").getPath();
        peer.applicationInfo.publicSourceDir = peer.applicationInfo.sourceDir;
        peer.applicationInfo.splitPublicSourceDirs = new String[]{files.newFile("feature.apk").getPath()};
        peer.lastUpdateTime = 1;
        shadowOf(host.getPackageManager()).installPackage(peer);
        extension = new ContextWrapper(host) {
            @Override public String getPackageName() { return peer.packageName; }
            @Override public String getPackageCodePath() { return peer.applicationInfo.sourceDir; }
        };
    }

    @Test public void keepsClassIdentityAcrossLoadsButRefreshesAfterPackageUpdate() throws Exception {
        Context first = PublicApkContext.load(host, extension);
        assertNotSame(extension.getClassLoader(), first.getClassLoader());
        assertSame(first.getClassLoader(), PublicApkContext.load(host, extension).getClassLoader());
        assertSame(first, LayoutInflater.from(first).getContext());
        assertSame(LayoutInflater.from(first), LayoutInflater.from(first));
        assertSame(host.getSystemService(Context.ACTIVITY_SERVICE), first.getSystemService(Context.ACTIVITY_SERVICE));
        peer.lastUpdateTime++;
        shadowOf(host.getPackageManager()).installPackage(peer);
        assertNotSame(first.getClassLoader(), PublicApkContext.load(host, extension).getClassLoader());
    }

    @Test public void separatePublicResourceArchiveDoesNotBecomeACodeSource() throws Exception {
        peer.applicationInfo.publicSourceDir = files.newFile("res.zip").getPath();
        shadowOf(host.getPackageManager()).installPackage(peer);
        assertSame(extension, PublicApkContext.load(host, extension));
    }

    @Test public void missingCacheFailsBeforeCreatingLoader() {
        Context noCache = new ContextWrapper(host) {
            @Override public File getCodeCacheDir() { return null; }
        };
        assertThrows(ClassNotFoundException.class, () -> PublicApkContext.load(noCache, extension));
    }
}
