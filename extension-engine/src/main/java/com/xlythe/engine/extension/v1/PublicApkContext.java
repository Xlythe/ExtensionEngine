package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.LayoutInflater;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import dalvik.system.DexClassLoader;

/** On legacy ART, a public APK may be readable even when its system-optimized DEX cannot be opened. */
@RequiresApi(21)
final class PublicApkContext extends ContextWrapper {
    private static final WeakHashMap<ClassLoader, WeakReference<Loader>> loaders = new WeakHashMap<>();
    private final ClassLoader loader;
    private LayoutInflater inflater;

    private PublicApkContext(Context extension, ClassLoader loader) {
        super(extension);
        this.loader = loader;
    }

    static synchronized Context load(Context host, Context extension, String className)
            throws ClassNotFoundException, PackageManager.NameNotFoundException {
        PackageInfo installed = host.getPackageManager().getPackageInfo(extension.getPackageName(), 0);
        ApplicationInfo info = installed.applicationInfo;
        if (info == null || !extension.getPackageCodePath().equals(info.publicSourceDir)
                || !new File(info.publicSourceDir).canRead()) {
            throw new ClassNotFoundException(className + " has no readable public APK");
        }
        ClassLoader parent = extension.getClassLoader();
        WeakReference<Loader> previous = loaders.get(parent);
        Loader result = previous == null ? null : previous.get();
        if (result == null || result.updated != installed.lastUpdateTime) {
            String paths = info.publicSourceDir;
            if (info.splitPublicSourceDirs != null && info.splitPublicSourceDirs.length > 0) {
                paths += File.pathSeparator + TextUtils.join(File.pathSeparator, info.splitPublicSourceDirs);
            }
            File root = host.getCodeCacheDir();
            if (root == null) throw new ClassNotFoundException("Host code cache unavailable for " + className);
            File cache = new File(new File(root, info.packageName), Long.toString(installed.lastUpdateTime));
            if (!cache.isDirectory() && !cache.mkdirs() && !cache.isDirectory()) {
                throw new ClassNotFoundException("Cannot create private code cache for " + info.packageName);
            }
            result = new Loader(paths, cache, info.nativeLibraryDir, parent, installed.lastUpdateTime);
        }
        result.loadClass(className);
        loaders.put(parent, new WeakReference<>(result));
        return new PublicApkContext(extension, result);
    }

    private static class Loader extends DexClassLoader {
        final long updated;

        Loader(String paths, File cache, String libraries, ClassLoader parent, long updated) {
            super(paths, cache.getPath(), libraries, parent);
            this.updated = updated;
        }
    }

    @Override
    public ClassLoader getClassLoader() {
        return loader;
    }

    @Override
    public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
            return inflater;
        }
        return super.getSystemService(name);
    }
}
