package com.xlythe.engine.extension;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Process;

import com.xlythe.engine.extension.v1.ExtensionContextV1;
import com.xlythe.engine.extension.v2.ExtensionContextV2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSigningInfo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.shadow.api.Shadow.extract;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {27, 35}, manifest = Config.NONE)
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

    private void install(String name, Signature... signers) {
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
    public void rejectsMissingPackageSignerAndMalformedPin() {
        assertFalse(Security.isTrustedPackage(host, "test.missing"));
        install(PEER);
        assertFalse(Security.isTrustedPackage(host, PEER));
        assertThrows(IllegalArgumentException.class,
                () -> Security.addAllowedSigningCertificate(PEER, "abc"));
    }
}
