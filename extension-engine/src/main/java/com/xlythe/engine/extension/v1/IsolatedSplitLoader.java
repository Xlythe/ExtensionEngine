package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DelegateLastClassLoader;
import dalvik.system.PathClassLoader;

@RequiresApi(26)
final class IsolatedSplitLoader {
    private static final WeakHashMap<ClassLoader, Cached> loaders = new WeakHashMap<>();

    private IsolatedSplitLoader() {}

    static Context forContext(Context base, Context feature, String split) {
        ClassLoader platform = feature.getClassLoader();
        if (platform == base.getClassLoader() || split == null) return feature;
        ClassLoader loader = forSplit(platform, base.getApplicationInfo(), split);
        return loader == platform ? feature : new LoaderContext(feature, loader);
    }

    private static synchronized ClassLoader forSplit(ClassLoader platform, ApplicationInfo info, String split) {
        boolean delegateLast = Build.VERSION.SDK_INT >= 27 && platform.getClass() == DelegateLastClassLoader.class;
        if (!delegateLast && platform.getClass() != PathClassLoader.class) return platform;
        String[] paths = info.splitPublicSourceDirs;
        if (paths == null || info.splitNames == null || paths.length != info.splitNames.length) return platform;
        int index = Arrays.asList(info.splitNames).indexOf(split);
        if (index < 0) return platform;
        Cached cached = loaders.get(platform);
        if (cached != null && Arrays.equals(paths, cached.paths)) {
            ClassLoader previous = cached.loader.get();
            if (previous != null) return previous;
        }
        ClassLoader result = platform;
        try {
            String search = nativeSearchPath(split, info.splitNames, paths, info.nativeLibraryDir,
                    Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS);
            if (search != null) {
                // System.loadLibrary uses the class's defining loader. Keep the feature's
                // platform parent so its base and dependency classes retain their identity.
                result = delegateLast
                        ? new DelegateLastClassLoader(paths[index], search, platform.getParent())
                        : new PathClassLoader(paths[index], search, platform.getParent());
            }
        } catch (IOException e) {
            Log.w("ExtensionEngine", "Cannot inspect native libraries for split " + split, e);
            return platform;
        }
        loaders.put(platform, new Cached(paths, result));
        return result;
    }

    static String nativeSearchPath(String feature, String[] names, String[] paths, String extracted, String[] abis)
            throws IOException {
        Map<String, Set<String>> directories = new HashMap<>();
        for (String abi : abis) directories.put(abi, new LinkedHashSet<>());
        for (int i = 0; i < names.length; i++) {
            if (!names[i].equals(feature) && !names[i].startsWith(feature + ".config.")) continue;
            try (ZipFile apk = new ZipFile(paths[i])) {
                Enumeration<? extends ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("lib/") || !name.endsWith(".so")) continue;
                    String[] parts = name.split("/");
                    if (parts.length != 3) continue;
                    Set<String> dirs = directories.get(parts[1]);
                    if (dirs == null) continue;
                    if (entry.getMethod() == ZipEntry.STORED) {
                        dirs.add(paths[i] + "!/lib/" + parts[1]);
                    } else if (extracted != null) {
                        dirs.add(extracted);
                    }
                }
            }
        }
        for (String abi : abis) {
            Set<String> dirs = directories.get(abi);
            if (!dirs.isEmpty()) return String.join(File.pathSeparator, dirs);
        }
        return null;
    }

    // A repeated load must share the feature's static state while its installation is current.
    private static final class Cached {
        final String[] paths;
        final WeakReference<ClassLoader> loader;

        Cached(String[] paths, ClassLoader loader) {
            this.paths = paths.clone();
            this.loader = new WeakReference<>(loader);
        }
    }
}
