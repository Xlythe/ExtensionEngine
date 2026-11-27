package com.xlythe.test.host;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.xlythe.engine.extension.Extension;
import com.xlythe.test.BuildConfig;

/** Keeps an instance alive across adb install -r so the test covers warm and cold loads. */
public class UpdateProbe extends Activity {
    private Entry current;

    private static class Entry extends Extension {
        Entry(Extension extension) { super(extension); }
        String revision() { return (String) invoke("revision"); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        probe(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        probe(intent);
    }

    private void probe(Intent intent) {
        try {
            if (current != null && !current.isCurrent()) {
                try {
                    current.revision();
                    throw new AssertionError("Superseded handle remained callable");
                } catch (IllegalStateException expected) { current = null; }
            }
            if (current == null) current = new Entry(Extension.getExtension(this, "com.xlythe.test.extension"));
            Log.i("ExtensionUpdate", intent.getStringExtra("stage") + " host=" + BuildConfig.VERSION_CODE
                    + " extension=" + current.revision());
        } catch (Extension.InvalidExtensionException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
