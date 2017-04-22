package com.xlythe.engine.extension;

import java.util.LinkedList;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import com.xlythe.engine.theme.App;
import com.xlythe.engine.theme.Theme;

public class Extension {
    /**
     * Returns a list of installed apps that are registered as extensions
     * */
    public static List<App> getApps(Context context) {
        LinkedList<App> apps = new LinkedList<App>();
        PackageManager manager = context.getPackageManager();

        Intent mainIntent = new Intent(context.getPackageName() + ".EXTENSION", null);

        final List<ResolveInfo> infos;
        try {
            infos = manager.queryIntentActivities(mainIntent, 0);
        }
        catch(Exception e) {
            e.printStackTrace();
            return apps;
        }

        for(ResolveInfo info : infos) {
            App app = new App();
            apps.add(app);

            app.setName(info.loadLabel(manager).toString());
            app.setPackageName(info.activityInfo.applicationInfo.packageName);
        }
        return apps;
    }

    public static ExtensionInterface getExtension(Context context, String packageName) {
        try {
            Context packageContext = context.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE + Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader = packageContext.getClassLoader();
            Class<?> viewExtractor = loader.loadClass(packageName + ".Extension");

            return new ExtensionImpl(viewExtractor.newInstance());
        }
        catch(IllegalAccessException e) {
            e.printStackTrace();
        }
        catch(NameNotFoundException e) {
            e.printStackTrace();
        }
        catch(ClassNotFoundException e) {
            e.printStackTrace();
        }
        catch(InstantiationException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Grabs the Resources from packageName
     * */
    private static Resources getResources(Context context, String extension) {
        try {
            return context.getPackageManager().getResourcesForApplication(extension);
        }
        catch(NameNotFoundException e) {
            e.printStackTrace();
            return context.getResources();
        }
    }

    public static Drawable getDrawable(Context context, String extension, String name) {
        int id = Theme.getId(context, Theme.DRAWABLE, name);
        if(id != 0) {
            return Theme.getResources(context).getDrawable(id);
        }

        // No drawable in the theme, grab from extension
        id = getResources(context, extension).getIdentifier(name, Theme.DRAWABLE, extension);
        if(id != 0) {
            return getResources(context, extension).getDrawable(id);
        }

        // No drawable in the extension, grab from app
        id = context.getResources().getIdentifier(name, Theme.DRAWABLE, context.getPackageName());
        if(id != 0) {
            return context.getResources().getDrawable(id);
        }

        return null;
    }

    public static Context createContext(Context context, String packageName) {
        // Create context for app
        try {
            return context.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE + Context.CONTEXT_IGNORE_SECURITY);
        }
        catch(NameNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }
}
