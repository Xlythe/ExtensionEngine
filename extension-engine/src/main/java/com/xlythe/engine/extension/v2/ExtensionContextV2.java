package com.xlythe.engine.extension.v2;

import android.content.Context;
import android.content.ContextWrapper;

import com.xlythe.engine.extension.v1.ExtensionContextV1;

import java.io.IOException;

/** Compatibility entry point using the verified extension context for code, resources and services. */
public class ExtensionContextV2 extends ContextWrapper {
    public ExtensionContextV2(Context context, String packageName) throws InvalidCursorException, IOException {
        super(new ExtensionContextV1(context, packageName));
    }

    public boolean isCurrent() { return ((ExtensionContextV1) getBaseContext()).isCurrent(); }

    public static class InvalidCursorException extends Exception {}
}
