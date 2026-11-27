package com.xlythe.engine.extension;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import com.xlythe.engine.extension.v2.DexFileProvider;
import org.robolectric.shadows.ShadowBinder;
import java.io.FileNotFoundException;
import java.util.Arrays;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Process;
import android.os.Bundle;

import com.xlythe.engine.extension.v1.ExtensionContextV1;
import com.xlythe.engine.extension.v2.ExtensionContextV2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSigningInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.shadow.api.Shadow.extract;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = Config.OLDEST_SDK)
public class SecurityTest {
    private static final String PEER = "test.extension";
    private static final Signature HOST_CERT = new Signature(new byte[] {1});
    private static final Signature PARTNER_CERT = new Signature(new byte[] {2});
    // SHA-256 of the single byte 0x02.
    private static final String PARTNER_PIN =
            "DB:C1:B4:C9:00:FF:E4:8D:57:5B:5D:A5:C6:38:04:01:25:F6:5D:B0:FE:3E:24:49:4B:76:EA:98:64:57:D9:86";
    private Context host;

    @Before
    public void setUp() {
        host = RuntimeEnvironment.getApplication();
        install(host.getPackageName(), HOST_CERT);
    }

    private PackageInfo install(String name, Signature... signers) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = name;
        info.applicationInfo.uid = name.equals(host.getPackageName()) ? Process.myUid() : 12345;
        info.signatures = signers;
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = new SigningInfo();
            ShadowSigningInfo signingInfo = extract(info.signingInfo);
            signingInfo.setSignatures(signers);
        }
        shadowOf(host.getPackageManager()).installPackage(info);
        return info;
    }

    @Test
    public void usesCurrentSignerAndRechecksAfterReplacement() {
        install(PEER, HOST_CERT);
        assertTrue(Security.isTrustedPackage(host, PEER));
        assertFalse(Security.isTrustedPackage(host, PEER, PARTNER_CERT.toByteArray()));
        install(PEER, PARTNER_CERT);
        assertFalse(Security.isTrustedPackage(host, PEER, PARTNER_CERT.toByteArray()));
    }

    @Test
    public void pinAppliesOnlyToNamedPackageAndSingleSigner() {
        install(PEER, PARTNER_CERT);
        install("test.sibling", PARTNER_CERT);
        Security.addAllowedSigningCertificate(PEER, PARTNER_PIN);
        assertTrue(Security.isTrustedPackage(host, PEER));
        assertFalse(Security.isTrustedPackage(host, "test.sibling"));
        install(PEER, HOST_CERT, PARTNER_CERT);
        assertFalse(Security.isTrustedPackage(host, PEER));
    }

    @Test
    public void matchesCompleteMultisignerSetRegardlessOfOrder() {
        install(host.getPackageName(), HOST_CERT, PARTNER_CERT);
        install(PEER, PARTNER_CERT, HOST_CERT);
        assertTrue(Security.isTrustedPackage(host, PEER));
        install(PEER, HOST_CERT);
        assertFalse(Security.isTrustedPackage(host, PEER));
    }

    @Test
    public void sameSignerStillRequiresExtensionRegistrationForEveryLoader() {
        install(PEER, HOST_CERT);
        assertThrows(SecurityException.class, () -> new ExtensionContextV1(host, PEER));
        assertThrows(SecurityException.class, () -> new ExtensionContextV2(host, PEER));
        assertThrows(Extension.InvalidExtensionException.class,
                () -> Extension.getExtension(host, new App("claimed extension", PEER)));
    }

    @Test
    public void adapterRequiresMatchingOwnerAndCompleteSignerSet() throws Exception {
        String adapter = "test.adapter";
        install(PEER, HOST_CERT);
        PackageInfo info = install(adapter, HOST_CERT);
        info.applicationInfo.metaData = new Bundle();
        info.applicationInfo.metaData.putString(Extension.KEY_ADAPTER_OWNER, PEER);
        shadowOf(host.getPackageManager()).installPackage(info);
        Security.enforceLegacyAdapter(host, PEER, adapter);

        info.applicationInfo.metaData.putString(Extension.KEY_ADAPTER_OWNER, "test.other");
        shadowOf(host.getPackageManager()).installPackage(info);
        assertThrows(SecurityException.class, () -> Security.enforceLegacyAdapter(host, PEER, adapter));

        info = install(adapter, PARTNER_CERT);
        info.applicationInfo.metaData = new Bundle();
        info.applicationInfo.metaData.putString(Extension.KEY_ADAPTER_OWNER, PEER);
        shadowOf(host.getPackageManager()).installPackage(info);
        Security.addAllowedSigningCertificate(adapter, PARTNER_PIN);
        assertTrue(Security.isTrustedPackage(host, adapter));
        assertThrows(SecurityException.class, () -> Security.enforceLegacyAdapter(host, PEER, adapter));
    }

    @Test
    public void rejectsMissingPackageSignerAndMalformedPin() {
        assertFalse(Security.isTrustedPackage(host, "test.missing"));
        install(PEER);
        assertFalse(Security.isTrustedPackage(host, PEER));
        assertThrows(IllegalArgumentException.class,
                () -> Security.addAllowedSigningCertificate(PEER, "abc"));
    }

    private ResolveInfo resolve(PackageInfo pkg) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.applicationInfo = pkg.applicationInfo;
        info.activityInfo.packageName = pkg.packageName;
        info.activityInfo.name = pkg.packageName + ".Registration";
        info.nonLocalizedLabel = pkg.packageName;
        return info;
    }

    @Test public void discoveryFiltersTrustAndReturnsEachOwnerOnce() {
        ResolveInfo trusted = resolve(install(PEER, HOST_CERT));
        ResolveInfo foreign = resolve(install("other.extension", PARTNER_CERT));
        Intent action = new Intent("alternate.host" + Extension.ACTION_EXTENSION);
        shadowOf(host.getPackageManager()).setResolveInfosForIntent(action, Arrays.asList(trusted, trusted, foreign));
        java.util.List<App> results = Extension.getApps(host, "alternate.host");
        assertEquals(1, results.size());
        assertEquals(PEER, results.get(0).getPackageName());
        assertEquals("alternate.host", results.get(0).actionPackage);
        assertTrue(Extension.getApps(host, "alternate.host", PARTNER_CERT.toByteArray()).isEmpty());
        assertFalse(ExtensionResources.isOwnedActivity(trusted.activityInfo, foreign.activityInfo.packageName));
    }

    @Test public void providerChecksCallerRegistrationAndTrustForQueriesAndFileOpens() throws Exception {
        install(PEER, HOST_CERT);
        DexFileProvider provider = new DexFileProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = host.getPackageName() + ".DexFileProvider";
        provider.attachInfo(host, info);
        ShadowBinder.setCallingUid(12345);
        shadowOf(host.getPackageManager()).setPackagesForUid(12345, PEER);
        shadowOf(provider).setCallingPackage(PEER);
        Uri count = Uri.parse("content://" + info.authority + "/count");
        Uri file = Uri.parse("content://" + info.authority + "/dex/0");
        assertThrows(SecurityException.class, () -> provider.query(count, null, null, null, null));
        Intent registration = new Intent(PEER + Extension.ACTION_EXTENSION).setPackage(host.getPackageName());
        shadowOf(host.getPackageManager()).addResolveInfoForIntent(registration,
                resolve(host.getPackageManager().getPackageInfo(host.getPackageName(), 0)));
        try (Cursor result = provider.query(count, null, null, null, null)) {
            assertTrue(result.moveToFirst());
            assertTrue(result.getInt(0) >= 1);
        }
        try (Cursor metadata = provider.query(file, null, null, null, null)) {
            assertTrue(metadata.moveToFirst());
            assertEquals("/dex/0", metadata.getString(0));
        }
        assertThrows(IllegalArgumentException.class, () -> provider.query(count, new String[]{"unknown"}, null, null, null));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(file, "w"));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(Uri.withAppendedPath(file, "0"), "r"));
        java.io.File apk = java.io.File.createTempFile("peer", ".apk", host.getCacheDir());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(apk)) { out.write(37); }
        ApplicationInfo app = provider.getContext().getApplicationInfo();
        String original = app.sourceDir;
        app.sourceDir = apk.getPath();
        try (android.os.ParcelFileDescriptor.AutoCloseInputStream input =
                     new android.os.ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(file, "r"))) {
            assertEquals(37, input.read());
        } finally {
            app.sourceDir = original;
            apk.delete();
        }
        install(PEER, PARTNER_CERT);
        assertThrows(SecurityException.class, () -> provider.query(count, null, null, null, null));
        assertThrows(SecurityException.class, () -> provider.openFile(file, "r"));
    }
    public static class ContextEntry {
        private final String source;
        public ContextEntry() { source = "default"; }
        public ContextEntry(Context context) { source = context.getPackageName(); }
        public String source() { return source; }
    }

    public static class BrokenEntry {
        static { requireMissingDependency(); }
        private static void requireMissingDependency() { throw new NoClassDefFoundError("fixture dependency"); }
    }

    public static class DefaultEntry {
        public String source() { return "default"; }
    }

    @Test @Config(sdk = 23)
    public void protectedOwnerUsesOnlyHostConfiguredClassAndSupportsBothConstructors() throws Exception {
        PackageInfo owner = install(PEER, HOST_CERT);
        ResolveInfo registration = resolve(owner);
        registration.activityInfo.metaData = new Bundle();
        registration.activityInfo.metaData.putString(Extension.KEY_EXTENSION, "arbitrary.host.Class");
        Intent action = new Intent(host.getPackageName() + Extension.ACTION_EXTENSION).setPackage(PEER);
        shadowOf(host.getPackageManager()).addResolveInfoForIntent(action, registration);
        Context blocked = new ContextWrapper(host) {
            @Override public Context createPackageContext(String name, int flags) {
                throw new Resources.NotFoundException("Protected test owner");
            }
            @Override public ClassLoader getClassLoader() { return ContextEntry.class.getClassLoader(); }
        };
        assertThrows(Extension.InvalidExtensionException.class, () -> Extension.getExtension(blocked, PEER));
        PackageInfo local = install(host.getPackageName(), HOST_CERT);
        local.applicationInfo.metaData = new Bundle();
        local.applicationInfo.metaData.putString(Extension.KEY_LEGACY_ADAPTER + "." + PEER, DefaultEntry.class.getName());
        shadowOf(host.getPackageManager()).installPackage(local);
        assertEquals("default", Extension.getExtension(blocked, PEER).invoke("source"));
        local.applicationInfo.metaData.putString(PEER + ".LEGACY_ADAPTER", ContextEntry.class.getName());
        shadowOf(host.getPackageManager()).installPackage(local);
        assertEquals(host.getPackageName(), Extension.getExtension(blocked, PEER).invoke("source"));
        ExtensionContextV2 compatibility = new ExtensionContextV2(blocked, PEER);
        assertEquals(host.getPackageName(), compatibility.getPackageName());
        assertEquals(blocked.getClassLoader(), compatibility.getClassLoader());
        local.applicationInfo.metaData.putString(PEER + ".LEGACY_ADAPTER", BrokenEntry.class.getName());
        shadowOf(host.getPackageManager()).installPackage(local);
        Extension.InvalidExtensionException failure = assertThrows(Extension.InvalidExtensionException.class,
                () -> Extension.getExtension(blocked, PEER));
        assertTrue(failure.getCause() instanceof LinkageError);
    }

    @Test public void emptySignersWrongHostUidAndUnpinnedKeyFailClosed() {
        install(PEER, PARTNER_CERT);
        assertFalse(Security.isTrustedPackage(host, PEER));
        PackageInfo local = install(host.getPackageName(), PARTNER_CERT);
        local.applicationInfo.uid = Process.myUid() + 1;
        shadowOf(host.getPackageManager()).installPackage(local);
        assertFalse(Security.isTrustedPackage(host, PEER));
        install(host.getPackageName());
        assertFalse(Security.isTrustedPackage(host, PEER));
    }

    @Test @Config(sdk = 28)
    public void baseRegistrationSelectsOnlyTheSplitThatCanLoadItsEntry() throws Exception {
        PackageInfo owner = install(PEER, HOST_CERT);
        owner.applicationInfo.splitNames = new String[]{"config.en", "feature"};
        owner.applicationInfo.splitSourceDirs = new String[]{"language.apk", "feature.apk"};
        shadowOf(host.getPackageManager()).installPackage(owner);
        ResolveInfo registration = resolve(owner);
        registration.activityInfo.metaData = new Bundle();
        registration.activityInfo.metaData.putString(Extension.KEY_EXTENSION, DefaultEntry.class.getName());
        Intent action = new Intent(host.getPackageName() + Extension.ACTION_EXTENSION).setPackage(PEER);
        shadowOf(host.getPackageManager()).addResolveInfoForIntent(action, registration);
        ClassLoader baseLoader = new ClassLoader(null) {};
        Context feature = new ContextWrapper(host) {
            @Override public ClassLoader getClassLoader() { return DefaultEntry.class.getClassLoader(); }
        };
        Context base = new ContextWrapper(host) {
            @Override public ClassLoader getClassLoader() { return baseLoader; }
            @Override public ApplicationInfo getApplicationInfo() { return owner.applicationInfo; }
            @Override public Context createContextForSplit(String name) { return "feature".equals(name) ? feature : this; }
        };
        Context process = new ContextWrapper(host) {
            @Override public Context createPackageContext(String name, int flags) { return base; }
        };
        assertEquals(feature.getClassLoader(), new ExtensionContextV2(process, PEER).getClassLoader());
        assertEquals("default", Extension.getExtension(process, PEER).invoke("source"));
        owner.applicationInfo.splitNames = new String[]{"config.en"};
        shadowOf(host.getPackageManager()).installPackage(owner);
        assertThrows(Extension.InvalidExtensionException.class, () -> Extension.getExtension(process, PEER));
    }
}
