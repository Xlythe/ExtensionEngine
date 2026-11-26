package com.xlythe.engine.extension.v2;

import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProviderContractTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void legacyPipeCallbackTransfersBytes() throws Exception {
        File file = directory.newFile();
        byte[] bytes = new byte[]{4, 5, 6};
        try (ParcelFileDescriptor output = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY)) {
            new DexFileProvider().writeDataToPipe(output, Uri.parse("content://peer/dex/0"), "*/*", null,
                    new ByteArrayInputStream(bytes));
        }
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }

    @Test public void baseIsFileZeroWithAndWithoutSplits() {
        ApplicationInfo info = new ApplicationInfo();
        info.sourceDir = "base.apk";
        assertArrayEquals(new String[]{"base.apk"}, DexFileProvider.installedApks(info));
        info.splitSourceDirs = new String[]{"feature.apk", "fr.apk"};
        assertArrayEquals(new String[]{"base.apk", "feature.apk", "fr.apk"}, DexFileProvider.installedApks(info));
    }

    @Test public void acceptsOnlyAnExistingIndexedFile() {
        assertEquals(1, DexFileProvider.fileIndex(Uri.parse("content://peer/dex/1"), 2));
        for (String path : new String[]{"/", "/count", "/other/0", "/dex/-1", "/dex/2", "/dex/invalid", "/dex/0/1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> DexFileProvider.fileIndex(Uri.parse("content://peer" + path), 2));
        }
    }
}
