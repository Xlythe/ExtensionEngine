package com.xlythe.engine.extension.v2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.Vector;

/**
 * A class loader that combines multiple class loaders into one.<br>
 * The classes loaded by this class loader are associated with this class loader,
 * i.e. Class.getClassLoader() points to this class loader.
 * @deprecated Retained for callers of the original Java bytecode loader. Android extensions use their installed DEX.
 */
@Deprecated
public class JointClassLoader extends ClassLoader {

    private final ClassLoader[] delegateClassLoaders;

    public JointClassLoader(ClassLoader parent, ClassLoader... delegateClassLoaders) {
        super(parent);
        this.delegateClassLoaders = delegateClassLoaders.clone();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // It would be easier to call the loadClass() methods of the delegateClassLoaders
        // here, but we have to load the class from the byte code ourselves, because we
        // need it to be associated with our class loader.
        String path = name.replace('.', '/') + ".class";
        URL url = findResource(path);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }
        ByteBuffer byteCode;
        try {
            byteCode = loadResource(url);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        return defineClass(name, byteCode, null);
    }

    private ByteBuffer loadResource(URL url) throws IOException {
        try (InputStream stream = url.openStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) bytes.write(chunk, 0, count);
            return ByteBuffer.wrap(bytes.toByteArray());
        }
    }

    @Override
    protected URL findResource(String name) {
        for (ClassLoader delegate : delegateClassLoaders) {
            URL resource = delegate.getResource(name);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        Vector<URL> vector = new Vector<>();
        for (ClassLoader delegate : delegateClassLoaders) {
            Enumeration<URL> enumeration = delegate.getResources(name);
            while (enumeration.hasMoreElements()) {
                vector.add(enumeration.nextElement());
            }
        }
        return vector.elements();
    }

}
