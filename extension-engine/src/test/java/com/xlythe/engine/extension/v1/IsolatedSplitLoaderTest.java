package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;

import dalvik.system.DelegateLastClassLoader;
import dalvik.system.PathClassLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class IsolatedSplitLoaderTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    private String apk(boolean stored, String... entries) throws IOException {
        File file = directory.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (String name : entries) {
                ZipEntry entry = new ZipEntry(name);
                if (stored) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(0);
                    entry.setCrc(0);
                }
                zip.putNextEntry(entry);
                zip.closeEntry();
            }
        }
        return file.getPath();
    }

    @Test public void selectsOneCompatibleAbiAcrossTheFeatureAndItsConfigs() throws Exception {
        String main = apk(true, "lib/x86/liba.so", "lib/x86_64/liba.so", "lib/x86_64/libb.so");
        String config = apk(true, "lib/x86_64/libc.so");
        String[] names = {"feature", "feature.config.x86_64", "sibling", "featureTwo.config.x86_64"};
        String[] paths = {main, config, "unrelated.apk", "unrelated-config.apk"};
        assertEquals(main + "!/lib/x86_64" + File.pathSeparator + config + "!/lib/x86_64",
                IsolatedSplitLoader.nativeSearchPath("feature", names, paths, null, new String[]{"x86_64", "x86"}));
        assertNull(IsolatedSplitLoader.nativeSearchPath("feature", names, paths, null, new String[]{"arm64-v8a"}));
    }

    @Test public void compressedLibrariesUseThePackageManagersExtractedDirectory() throws Exception {
        String[] paths = {apk(false, "lib/x86/liba.so", "lib/x86/libb.so")};
        String[] abis = {"x86"};
        String[] names = {"feature"};
        assertEquals("/installed/lib/x86",
                IsolatedSplitLoader.nativeSearchPath("feature", names, paths, "/installed/lib/x86", abis));
        assertNull(IsolatedSplitLoader.nativeSearchPath("feature", names, paths, null, abis));
    }

    @Test public void ignoresOrdinaryResourcesAndNestedLibraryNames() throws Exception {
        String[] paths = {apk(true, "classes.dex", "lib/x86/data.txt", "assets/lib/x86/liba.so",
                "lib/x86/subdir/libb.so")};
        assertNull(IsolatedSplitLoader.nativeSearchPath("feature", new String[]{"feature"}, paths,
                "/installed/lib/x86", new String[]{"x86"}));
    }

    @Test public void retainsParentDelegationAndSharesTheLoaderUntilTheSplitPathsChange() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        info.splitNames = new String[]{"feature"};
        info.splitPublicSourceDirs = new String[1];
        ShadowBuild.setSupported32BitAbis(new String[]{"x86"});
        ShadowBuild.setSupported64BitAbis(new String[]{"x86_64"});
        Context base = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public ApplicationInfo getApplicationInfo() { return info; }
        };
        for (ClassLoader platform : new ClassLoader[]{
                new PathClassLoader("", base.getClassLoader()),
                new DelegateLastClassLoader("", base.getClassLoader())}) {
            Context feature = new ContextWrapper(base) {
                @Override public ClassLoader getClassLoader() { return platform; }
            };
            info.splitPublicSourceDirs[0] = apk(true, "lib/x86/liba.so", "lib/x86_64/liba.so");
            ClassLoader first = IsolatedSplitLoader.forContext(base, feature, "feature").getClassLoader();
            assertNotSame(platform, first);
            assertEquals(platform.getClass(), first.getClass());
            assertSame(platform.getParent(), first.getParent());
            assertSame(first, IsolatedSplitLoader.forContext(base, feature, "feature").getClassLoader());

            info.splitPublicSourceDirs[0] = apk(true, "classes.dex");
            assertSame(feature, IsolatedSplitLoader.forContext(base, feature, "feature"));
        }
        assertSame(base, IsolatedSplitLoader.forContext(base, base, null));
    }
}
