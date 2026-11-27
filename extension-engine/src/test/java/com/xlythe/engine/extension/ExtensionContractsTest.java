package com.xlythe.engine.extension;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 28})
public class ExtensionContractsTest {
    private final Context host = RuntimeEnvironment.getApplication();

    @Test public void appWithoutLauncherCanBeDescribedButHasNoLaunchIntent() {
        App app = App.getApp(host, host.getPackageName());
        assertEquals(host.getPackageName(), app.getPackageName());
        assertNotNull(app.getName());
        assertTrue(App.doesPackageExists(host, app.getPackageName()));
        assertNull(app.getIntent(host));
        assertFalse(App.doesPackageExists(host, "missing.package"));
        assertNull(App.getApp(host, "missing.package"));
    }

    @Test public void serializedAppKeepsIdentityButDropsAlternateNamespace() throws Exception {
        App discovered = new App("Example", "test.peer", "alternate.host");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(discovered); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            App restored = (App) in.readObject();
            assertEquals("Example", restored.getName());
            assertEquals("test.peer", restored.getPackageName());
            assertNull(restored.actionPackage);
        }
    }

    public static class Entry {
        public String label(int number) { return "int " + number; }
        public String label(String text) { return text; }
        public String broken() { throw new IllegalStateException(); }
    }

    @Test public void copiedWrapperInvokesExactOverloadAndHandlesFailures() {
        Extension wrapper = new Extension(new Extension(host, new Entry()));
        assertEquals("int 4", wrapper.invoke("label", new Extension.ReflectionPair<>(int.class, 4)));
        assertEquals("text", wrapper.invoke("label", new Extension.ReflectionPair<>(String.class, "text")));
        assertNull(wrapper.invoke("missing"));
        assertNull(wrapper.invoke("broken"));
    }

    @Test public void resolvesFrameworkDrawableAndHandlesMissingResourceNames() {
        assertNotNull(ExtensionResources.getDrawable(host, "android", "ic_dialog_info"));
        assertNull(ExtensionResources.getDrawable(host, "android", null));
        assertNull(ExtensionResources.getDrawable(host, "missing.package", "nonexistent_drawable"));
    }

    @Test public void inflaterAttachesToSuppliedParentOnBothSidesOfCacheWorkaround() {
        FrameLayout parent = new FrameLayout(host);
        View result = ExtensionInflater.inflate(host, android.R.layout.simple_list_item_1, parent);
        assertSame(parent, result);
        assertNotNull(result.findViewById(android.R.id.text1));
        assertEquals(1, parent.getChildCount());
    }
}
