package com.xlythe.engine.extension;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.util.Pair;

import com.xlythe.engine.extension.v1.ExtensionContextV1;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedList;
import java.util.List;

public class Extension extends ContextWrapper {
    public static final String TAG = "Extension";
    public static final boolean DEBUG = false;

    public static final String ACTION_EXTENSION = ".EXTENSION";
    public static final String KEY_EXTENSION = "extension";
    public static final String KEY_LEGACY_ADAPTER = "com.xlythe.engine.extension.LEGACY_ADAPTER";
    public static final String KEY_ADAPTER_OWNER = "com.xlythe.engine.extension.ADAPTER_OWNER";
    public static final String CLASS_NAME = ".Extension";

    /** Marks an owner-package .Extension class bundled by the host for automatic legacy fallback. */
    public interface LegacyAdapter {}

    /**
     * Returns a list of installed apps that are registered as extensions. Optionally you can pass
     * in a signatures field that lists the signatures the extensions are required to be signed
     * with. Passing in no signatures means all trusted extensions will be listed.
     */
    public static List<App> getApps(Context context, byte[]... signatures) {
        return getApps(context, context.getPackageName(), signatures);
    }

    /**
     * Returns a list of installed apps that are registered as extensions. Optionally you can pass
     * in a signatures field that lists the signatures the extensions are required to be signed
     * with. Passing in no signatures means all trusted extensions will be listed.
     */
    public static List<App> getApps(Context context, String packageName, byte[]... signatures) {
        List<App> apps = new LinkedList<>();
        Set<String> seen = new HashSet<>();
        PackageManager manager = context.getPackageManager();

        Intent intent = new Intent(packageName + ACTION_EXTENSION);
        try {
            for (ResolveInfo info : manager.queryIntentActivities(intent, 0)) {
                ActivityInfo activity = info.activityInfo;
                if (activity == null || !ExtensionResources.isOwnedActivity(activity, activity.packageName)) {
                    continue;
                }
                if (!seen.add(activity.packageName)) continue;
                if (!Security.isTrustedPackage(context, activity.packageName, signatures)) {
                    Log.w(TAG, "Ignoring untrusted extension " + activity.packageName);
                    continue;
                }
                apps.add(new App(info.loadLabel(manager).toString(), activity.packageName, packageName));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query extension apps", e);
        }

        return apps;
    }

    public static Extension getExtension(Context context, App app) throws InvalidExtensionException {
        return getExtension(context, app.getPackageName(),
                app.actionPackage == null ? context.getPackageName() : app.actionPackage);
    }

    public static Extension getExtension(Context context, String packageName) throws InvalidExtensionException {
        return getExtension(context, packageName, context.getPackageName());
    }

    private static Extension getExtension(Context context, String packageName, String actionPackage)
            throws InvalidExtensionException {
        try {
            ExtensionContextV1 packageContext = new ExtensionContextV1(context, packageName, actionPackage);
            Class<?> clazz = packageContext.getClassLoader().loadClass(packageContext.getExtensionClassName());
            Object extension = newInstance(packageContext, clazz);
            return new Extension(packageContext, extension);
        } catch (Exception | LinkageError unavailable) {
            Log.e(TAG, "Failed to load extension", unavailable);
            InvalidExtensionException failure = new InvalidExtensionException();
            failure.initCause(unavailable);
            throw failure;
        }
    }

    /**
     * Attempts to load the class, first looking for a constructor that accepts a Context and
     * falling back to an empty constructor.
     */
    private static Object newInstance(Context context, Class<?> clazz)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        try {
            Constructor<?> constructor = clazz.getConstructor(Context.class);
            return constructor.newInstance(context);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Failed to find extension constructor that takes a Context", e);
        }

        // Fall back to an empty constructor.
        return clazz.newInstance();
    }

    public static class InvalidExtensionException extends Exception {}

    // The object from the other side.
    private final Object mObj;

    protected Extension(Context context, Object object) {
        super(context);
        mObj = object;
    }

    protected Extension(Extension extension) {
        this(extension, extension.mObj);
    }

    protected Object invoke(String methodName, ReflectionPair... parameters) {
        Class[] classes = new Class[parameters.length];
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            classes[i] = (Class) parameters[i].first;
            values[i] = parameters[i].second;
        }
        return invoke(methodName, classes, values);
    }

    private Object invoke(String methodName, Class[] parameterTypes, Object[] values) {
        try {
            Class<?> viewExtractor = mObj.getClass();
            Method m = viewExtractor.getMethod(methodName, parameterTypes);
            m.setAccessible(true);
            return m.invoke(mObj, values);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static class ReflectionPair<T> extends Pair<Class<T>, T> {
        public ReflectionPair(Class<T> first, T second) {
            super(first, second);
        }
    }
}
