package com.xlythe.engine.extension.v2;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** The published compatibility class consumes JVM class files. APK loading uses Android's DEX loaders. */
@SuppressWarnings("deprecation")
public class JointClassLoaderTest {
    public static class Payload { public int value() { return 17; } }

    @Test public void definesDelegateBytecodeAndEnumeratesResources() throws Exception {
        ClassLoader source = getClass().getClassLoader();
        JointClassLoader loader = new JointClassLoader(null, new ClassLoader(null) {}, source);
        Class<?> loaded = loader.loadClass(Payload.class.getName());
        assertSame(loader, loaded.getClassLoader());
        assertEquals(17, loaded.getMethod("value").invoke(loaded.getConstructor().newInstance()));
        String resource = Payload.class.getName().replace('.', '/') + ".class";
        assertEquals(Collections.singletonList(source.getResource(resource)), Collections.list(loader.findResources(resource)));
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("missing.Class"));
    }
}
