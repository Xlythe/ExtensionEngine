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
import com.xlythe.engine.extension.v2.ExtensionContextV2;
import com.xlythe.engine.theme.App;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;

public class Extension extends ContextWrapper {
    public static final String TAG = "Extension";
    public static final boolean DEBUG = false;

    // ExtensionContext is not ready yet. :( The idea was to more properly build a Context.
    // Unfortunately, it has bugs with loading resources on from the extension and, despite
    // efforts, does not support multidex. If/when this method is perfected, we'll be able to
    // use paid apps as extensions.
    private static final boolean USE_LEGACY_STRATEGY = true;

    public static final String ACTION_EXTENSION = ".EXTENSION";
    public static final String KEY_EXTENSION = "extension";
    public static final String CLASS_NAME = ".Extension";

    /**
     * Returns a list of installed apps that are registered as extensions. Optionally you can pass
     * in a signatures field that lists the signatures the extensions are required to be signed
     * with. Passing in no signatures means all extensions will be listed.
     */
    public static List<App> getApps(Context context, byte[]... signatures) {
        return getApps(context, context.getPackageName(), signatures);
    }

    /**
     * Returns a list of installed apps that are registered as extensions. Optionally you can pass
     * in a signatures field that lists the signatures the extensions are required to be signed
     * with. Passing in no signatures means all extensions will be listed.
     */
    public static List<App> getApps(Context context, String packageName, byte[]... signatures) {
        LinkedList<App> apps = new LinkedList<>();
        PackageManager manager = context.getPackageManager();

        Intent intent = new Intent(packageName + ACTION_EXTENSION);
        try {
            for (ResolveInfo info : manager.queryIntentActivities(intent, 0)) {
                // Only check signatures if we were given any.
                if (signatures.length > 0) {
                    if (!Security.validateSignature(manager, info.activityInfo.applicationInfo.packageName, signatures)) {
                        Log.w(TAG, info.activityInfo.applicationInfo.packageName + " failed the security check");
                        continue;
                    }
                }
                apps.add(new App(info.loadLabel(manager).toString(), info.activityInfo.applicationInfo.packageName));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query extension apps", e);
        }

        return apps;
    }

    public static Extension getExtension(Context context, App app) throws InvalidExtensionException {
        return getExtension(context, app.getPackageName());
    }

    public static Extension getExtension(Context context, String packageName) throws InvalidExtensionException {
        try {
            Context packageContext;
            if (USE_LEGACY_STRATEGY) {
                packageContext = new ExtensionContextV1(context, packageName);
            } else {
                packageContext = new ExtensionContextV2(context, packageName);
            }
            ClassLoader loader = packageContext.getClassLoader();

            // By default, we look for .Extension in the root package, but this may be overridden with metadata.
            String clazz = packageName + CLASS_NAME;
            ActivityInfo ai = ExtensionResources.getActivityInfo(context, packageName);
            if (ai != null && ai.metaData != null && ai.metaData.containsKey(KEY_EXTENSION)) {
                clazz = ai.metaData.getString(KEY_EXTENSION);
            }

            Object extension = newInstance(packageContext, loader.loadClass(clazz));
            if (DEBUG) {
                printMethods(extension.getClass());
            }

            return new Extension(packageContext, extension);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load extension", e);
        }

        throw new InvalidExtensionException();
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

    private static void printMethods(Class<?> clazz) {
        try {
            Log.d(TAG, "-----------------");
            Log.d(TAG, clazz.getName() + " methods");
            for (Method m : clazz.getDeclaredMethods()) {
                Log.d(TAG, m.getName());
            }
            Log.d(TAG, "-----------------");
            if (clazz.getSuperclass() != null) {
                printMethods(clazz.getSuperclass());
            }
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    public static class InvalidExtensionException extends Exception {}

    // The object from the other side.
    private final Object mObj;

    protected Extension(Context context, Object object) {
        super(context);
        mObj = object;
    }

    protected Extension(Extension extension) {
        super(extension);
        mObj = extension.getObject();
    }

    private Object getObject() {
        return mObj;
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
        ReflectionPair(Class<T> first, T second) {
            super(first, second);
        }
    }
}
