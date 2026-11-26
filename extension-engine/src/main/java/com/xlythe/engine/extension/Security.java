package com.xlythe.engine.extension;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;
import android.util.Pair;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class Security {
    private static final Set<Pair<String, String>> allowedCertificates =
            Collections.synchronizedSet(new HashSet<>());

    /**
     * Trusts the current APK signing certificate for this exact package in this process.
     * Register pins from trusted host configuration, for example in Application.onCreate().
     * Accepts a SHA-256 fingerprint as 64 hex digits or 32 colon-separated hex pairs.
     */
    public static void addAllowedSigningCertificate(String packageName, String sha256) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("A package name is required");
        }
        if (sha256 == null || !sha256.matches("(?i)([0-9a-f]{64}|([0-9a-f]{2}:){31}[0-9a-f]{2})")) {
            throw new IllegalArgumentException("Expected a SHA-256 certificate fingerprint");
        }
        allowedCertificates.add(Pair.create(packageName, sha256.replace(":", "").toLowerCase(Locale.ROOT)));
    }

    /** Checks a peer against the local application's current signers and package-specific pins. */
    public static void enforceTrustedPackage(Context context, String packageName) {
        if (!isTrustedPackage(context, packageName)) {
            throw new SecurityException("Untrusted package: " + packageName);
        }
    }

    static boolean isTrustedPackage(Context context, String packageName, byte[]... signatureFilter) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        PackageManager manager = context.getPackageManager();
        try {
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo host = manager.getPackageInfo(context.getPackageName(), flags);
            PackageInfo peer = manager.getPackageInfo(packageName, flags);
            if (!context.getPackageName().equals(host.packageName) || !packageName.equals(peer.packageName)
                    || host.applicationInfo == null
                    || host.applicationInfo.uid != Process.myUid()) {
                return false;
            }
            Set<Signature> hostSigners = currentSigners(host);
            Set<Signature> peerSigners = currentSigners(peer);
            if (hostSigners.isEmpty() || peerSigners.isEmpty()) {
                return false;
            }
            if (peerSigners.size() != 1) {
                return signatureFilter.length == 0 && hostSigners.equals(peerSigners);
            }
            byte[] certificate = peerSigners.iterator().next().toByteArray();
            if (signatureFilter.length > 0 && !matchesFilter(certificate, signatureFilter)) {
                return false;
            }
            return hostSigners.equals(peerSigners)
                    || allowedCertificates.contains(Pair.create(packageName, fingerprint(certificate)));
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<Signature> currentSigners(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || Arrays.asList(signatures).contains(null)) {
            return Collections.emptySet();
        }
        return new HashSet<>(Arrays.asList(signatures));
    }

    private static boolean matchesFilter(byte[] certificate, byte[][] filter) {
        for (byte[] candidate : filter) {
            if (Arrays.equals(candidate, certificate)) {
                return true;
            }
        }
        return false;
    }

    private static String fingerprint(byte[] certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b & 0xff) >>> 4, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // SHA-256 is required on Android.
        }
    }
}
