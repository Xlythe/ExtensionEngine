package com.xlythe.engine.extension;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.Arrays;

public class Security {
    static boolean validateSignature(PackageManager manager, String packageName, byte[]... signatures) throws PackageManager.NameNotFoundException {
        // Grab them from the package manager
        PackageInfo packageInfo = manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);

        // Ignore anyone with multiple signatures. We don't have time to verify them all!
        if (packageInfo.signatures.length != 1) {
            return false;
        }

        // Alright, we need to decide if this guy was one of our given signatures.
        byte[] packageSignature = packageInfo.signatures[0].toByteArray();

        // Loop over all of our trusted signatures.
        for (byte[] signature : signatures) {
            if (Arrays.equals(signature, packageSignature)) {
                return true;
            }
        }
        return false;
    }
}
