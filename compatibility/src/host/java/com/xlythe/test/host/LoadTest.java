package com.xlythe.test.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.xlythe.engine.extension.App;
import com.xlythe.engine.extension.Extension;

import java.util.Locale;

public class LoadTest extends Instrumentation {
    private static final String EXTENSION = "com.xlythe.test.extension";

    private Bundle options;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        options = arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle report = new Bundle();
        try {
            Context host = getTargetContext();
            boolean french = "fr".equals(options.getString("language"));
            if (Build.VERSION.SDK_INT >= 17) {
                Configuration config = new Configuration(host.getResources().getConfiguration());
                config.setLocale(french ? Locale.FRENCH : Locale.ENGLISH);
                host = host.createConfigurationContext(config);
            }
            String expectedText = "Extension layout loaded";
            if ("fr".equals(host.getResources().getConfiguration().locale.getLanguage())) {
                expectedText += " (fr)";
            }
            ApplicationInfo peer = host.getPackageManager().getApplicationInfo(EXTENSION, 0);
            report.putString("sourceDir", peer.sourceDir);
            report.putString("publicSourceDir", peer.publicSourceDir);
            if (peer.uid == host.getApplicationInfo().uid) {
                throw new AssertionError("Expected separate app UIDs");
            }
            boolean expectUntrusted = "true".equals(options.getString("expectUntrusted"));
            for (App app : Extension.getApps(host)) {
                if (EXTENSION.equals(app.getPackageName())) {
                    if (expectUntrusted) throw new AssertionError("Discovered an untrusted owner");
                    Adapter adapter = new Adapter(Extension.getExtension(host, app));
                    String expectedPackage = options.getString("codePackage");
                    if (expectedPackage == null) expectedPackage = EXTENSION;
                    report.putString("codePackage", adapter.getPackageName());
                    ClassLoader loader = adapter.getClassLoader();
                    report.putString("classLoader", loader.getClass().getName());
                    if (loader != Extension.getExtension(host, app).getClassLoader()) {
                        throw new AssertionError("Two loads of the same installation used different class loaders");
                    }
                    if (!expectedPackage.equals(adapter.getPackageName())) {
                        throw new AssertionError("Expected code from " + expectedPackage);
                    }
                    if (host.getPackageName().equals(expectedPackage)) expectedText = "Host adapter loaded";
                    if (!expectedText.equals(adapter.readLabel())) {
                        throw new AssertionError("Extension layout text did not match");
                    }
                    report.putString("result", "PASS");
                    finish(Activity.RESULT_OK, report);
                    return;
                }
            }
            if (!expectUntrusted) throw new AssertionError("Extension was missing from discovery");
            try {
                Extension.getExtension(host, EXTENSION);
                throw new AssertionError("Loaded an adapter for an untrusted owner");
            } catch (Extension.InvalidExtensionException expected) {
                report.putString("result", "PASS");
                finish(Activity.RESULT_OK, report);
            }
        } catch (Throwable error) {
            report.putString("failure", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private static class Adapter extends Extension {
        Adapter(Extension extension) {
            super(extension);
        }

        String readLabel() {
            return (String) invoke("readLabel");
        }
    }
}
