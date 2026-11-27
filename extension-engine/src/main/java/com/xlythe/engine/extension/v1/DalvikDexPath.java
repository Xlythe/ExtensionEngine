package com.xlythe.engine.extension.v1;

import android.content.pm.ApplicationInfo;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Assembles one Dalvik class path from the already verified, publicly readable installation. */
final class DalvikDexPath {
    private DalvikDexPath() {}

    static String forMultidexApk(ApplicationInfo info, File cache) throws IOException {
        try (ZipFile apk = new ZipFile(info.publicSourceDir)) {
            ZipEntry secondary = apk.getEntry("classes2.dex");
            if (secondary == null) return null;

            File inputs = new File(cache, "inputs");
            if (!inputs.isDirectory() && !inputs.mkdirs() && !inputs.isDirectory()) {
                throw new IOException("Cannot create private DEX directory " + inputs);
            }
            StringBuilder paths = new StringBuilder();
            if (info.sharedLibraryFiles != null && info.sharedLibraryFiles.length > 0) {
                paths.append(TextUtils.join(File.pathSeparator, info.sharedLibraryFiles)).append(File.pathSeparator);
            }
            paths.append(info.publicSourceDir);
            for (int index = 2; secondary != null; index++) {
                File dex = extract(apk, secondary, inputs, index);
                paths.append(File.pathSeparator).append(dex.getPath());
                secondary = apk.getEntry("classes" + (index + 1) + ".dex");
            }
            return paths.toString();
        }
    }

    private static File extract(ZipFile apk, ZipEntry entry, File directory, int index) throws IOException {
        File dex = new File(directory, "secondary-" + index + "-" + entry.getCrc() + ".dex");
        if (dex.isFile() && dex.length() == entry.getSize()) return dex;

        // Rename only complete inputs. Another host process may be loading the same install.
        File pending = File.createTempFile("dex-", ".tmp", directory);
        try {
            CRC32 checksum = new CRC32();
            try (InputStream in = apk.getInputStream(entry); FileOutputStream out = new FileOutputStream(pending)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    checksum.update(buffer, 0, count);
                }
            }
            if (pending.length() != entry.getSize() || checksum.getValue() != entry.getCrc()) {
                throw new IOException("Incomplete " + entry.getName());
            }
            if (!pending.setReadOnly() || !pending.renameTo(dex)) {
                throw new IOException("Cannot publish " + dex);
            }
            return dex;
        } finally {
            pending.delete();
        }
    }
}
