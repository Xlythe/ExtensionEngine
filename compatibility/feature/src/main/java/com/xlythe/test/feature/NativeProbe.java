package com.xlythe.test.feature;

public final class NativeProbe {
    static { System.loadLibrary("extension_fixture"); }
    public static native int read();
}
