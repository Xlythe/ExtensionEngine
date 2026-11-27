package com.xlythe.engine.extension.v1;

import android.content.pm.ApplicationInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DalvikDexPathTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    private ApplicationInfo apk(int count) throws IOException {
        File apk = directory.newFile("test.apk");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            for (int i = 1; i <= count; i++) {
                zip.putNextEntry(new ZipEntry(i == 1 ? "classes.dex" : "classes" + i + ".dex"));
                zip.write(i);
                zip.closeEntry();
            }
        }
        ApplicationInfo info = new ApplicationInfo();
        info.publicSourceDir = apk.getPath();
        return info;
    }

    @Test public void singleDexDoesNotCreateACache() throws Exception {
        File cache = new File(directory.getRoot(), "cache");
        assertNull(DalvikDexPath.forMultidexApk(apk(1), cache));
        assertFalse(cache.exists());
    }

    @Test public void concurrentLoadsPublishCompleteDexInClassPathOrder() throws Exception {
        ApplicationInfo info = apk(3);
        info.sharedLibraryFiles = new String[]{"shared.jar"};
        File cache = directory.newFolder();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> DalvikDexPath.forMultidexApk(info, cache));
            Future<String> b = pool.submit(() -> DalvikDexPath.forMultidexApk(info, cache));
            String path = a.get();
            assertEquals(path, b.get());
            String[] parts = path.split(File.pathSeparator);
            assertEquals(4, parts.length);
            assertEquals("shared.jar", parts[0]);
            assertEquals(info.publicSourceDir, parts[1]);
            assertArrayEquals(new byte[]{2}, Files.readAllBytes(new File(parts[2]).toPath()));
            assertArrayEquals(new byte[]{3}, Files.readAllBytes(new File(parts[3]).toPath()));
            assertEquals(2, new File(cache, "inputs").list().length);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void unusableCacheFailsWithoutPublishingPartialInputs() throws Exception {
        ApplicationInfo info = apk(2);
        File file = directory.newFile("occupied");
        assertThrows(IOException.class, () -> DalvikDexPath.forMultidexApk(info, file));
    }
}
