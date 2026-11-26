package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import dalvik.system.DexClassLoader;

/** Loads a verified public APK with a host-private DEX cache on legacy runtimes. */
final class PublicApkContext {
    private static final WeakHashMap<ClassLoader, WeakReference<Loader>> loaders = new WeakHashMap<>();
    private PublicApkContext() {}

    static synchronized Context load(Context host, Context extension)
            throws ClassNotFoundException, PackageManager.NameNotFoundException {
        PackageInfo installed = host.getPackageManager().getPackageInfo(extension.getPackageName(),
                PackageManager.GET_SHARED_LIBRARY_FILES);
        ApplicationInfo info = installed.applicationInfo;
        if (info == null || !extension.getPackageCodePath().equals(info.publicSourceDir)
                || !new File(info.publicSourceDir).canRead()) {
            return extension;
        }
        ClassLoader platform = extension.getClassLoader();
        WeakReference<Loader> previous = loaders.get(platform);
        Loader result = previous == null ? null : previous.get();
        if (result == null || result.updated != installed.lastUpdateTime || !info.sourceDir.equals(result.apk)) {
            File root = Build.VERSION.SDK_INT >= 21 ? host.getCodeCacheDir() : host.getCacheDir();
            if (root == null) throw new ClassNotFoundException("Host cache unavailable for " + info.packageName);
            File cache = new File(new File(root, info.packageName), Long.toString(installed.lastUpdateTime));
            String paths = info.publicSourceDir;
            ClassLoader parent = platform;
            try {
                if (Build.VERSION.SDK_INT < 21) {
                    paths = DalvikDexPath.forMultidexApk(info, cache);
                    if (paths == null) return extension;
                    // The primary class must see the secondary DEX through its own defining loader.
                    parent = platform.getParent();
                } else if (info.splitPublicSourceDirs != null && info.splitPublicSourceDirs.length > 0) {
                    paths += File.pathSeparator + TextUtils.join(File.pathSeparator, info.splitPublicSourceDirs);
                }
                if (!cache.isDirectory() && !cache.mkdirs() && !cache.isDirectory()) {
                    throw new IOException("Cannot create private code cache " + cache);
                }
            } catch (IOException e) {
                throw new ClassNotFoundException("Cannot prepare DEX for " + info.packageName, e);
            }
            result = new Loader(paths, cache, info, parent, installed.lastUpdateTime);
            loaders.put(platform, new WeakReference<>(result));
        }
        return new LoaderContext(extension, result);
    }

    private static class Loader extends DexClassLoader {
        final String apk;
        final long updated;

        Loader(String paths, File cache, ApplicationInfo info, ClassLoader parent, long updated) {
            super(paths, cache.getPath(), info.nativeLibraryDir, parent);
            this.apk = info.sourceDir;
            this.updated = updated;
        }
    }
}
