package com.xlythe.test.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.TextView;

import com.xlythe.engine.extension.App;
import com.xlythe.engine.extension.Extension;
import com.xlythe.engine.extension.Security;
import com.xlythe.engine.extension.v2.ExtensionContextV2;

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
            if ("fr".equals(host.getResources().getConfiguration().locale.getLanguage())
                    && !"true".equals(options.getString("expectBaseLanguage"))) {
                expectedText += " (fr)";
            }
            String fixturePin = options.getString("pin");
            if (fixturePin != null) Security.addAllowedSigningCertificate(EXTENSION, fixturePin);
            ApplicationInfo peer = host.getPackageManager().getApplicationInfo(EXTENSION, 0);
            report.putString("sourceDir", peer.sourceDir);
            report.putString("publicSourceDir", peer.publicSourceDir);
            if (peer.uid == host.getApplicationInfo().uid) {
                throw new AssertionError("Expected separate app UIDs");
            }
            if ("true".equals(options.getString("expectUnavailable"))) {
                try {
                    Extension.getExtension(host, EXTENSION);
                    throw new AssertionError("Loaded an absent feature");
                } catch (Extension.InvalidExtensionException expected) {
                    report.putString("result", "PASS");
                    finish(Activity.RESULT_OK, report);
                    return;
                }
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
                    Context v2 = new ExtensionContextV2(host, EXTENSION);
                    String layoutName = expectedPackage.equals(host.getPackageName()) ? "bundled_label" : "label";
                    String resourcePackage = options.getString("resourcePackage");
                    int id = v2.getResources().getIdentifier(layoutName, "layout",
                            resourcePackage == null ? expectedPackage : resourcePackage);
                    TextView label = (TextView) LayoutInflater.from(v2).inflate(id, null);
                    if (v2.getClassLoader() != loader || !expectedText.contentEquals(label.getText())) {
                        throw new AssertionError("V2 did not inflate the selected package's layout");
                    }
                    if ("true".equals(options.getString("verifyFeatureConfig"))) {
                        Class<?> probe = loader.loadClass("com.xlythe.test.feature.NativeProbe");
                        try {
                            if (!Integer.valueOf(37).equals(probe.getMethod("read").invoke(null))) {
                                throw new AssertionError("Feature JNI call failed");
                            }
                            report.putString("native", "PASS");
                        } catch (UnsatisfiedLinkError unavailable) {
                            if (!"false".equals(options.getString("requireNative"))) throw unavailable;
                            report.putString("native", unavailable.getMessage());
                        }
                        int drawable = v2.getResources().getIdentifier("density_probe", "drawable", resourcePackage);
                        android.graphics.drawable.ColorDrawable color =
                                (android.graphics.drawable.ColorDrawable) v2.getResources().getDrawable(drawable);
                        if (color.getColor() != 0xff00cc55) throw new AssertionError("Expected mdpi drawable");
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
                // Check the direct V2 constructor independently of discovery and the default loader.
            }
            try {
                new ExtensionContextV2(host, EXTENSION);
                throw new AssertionError("V2 accepted an untrusted owner");
            } catch (SecurityException expected) {
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
