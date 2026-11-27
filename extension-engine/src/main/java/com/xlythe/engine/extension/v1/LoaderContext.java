package com.xlythe.engine.extension.v1;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.LayoutInflater;

/** Keeps the package resources and inflates custom views with their defining loader. */
final class LoaderContext extends ContextWrapper {
    private final ClassLoader loader;
    private LayoutInflater inflater;

    LoaderContext(Context extension, ClassLoader loader) {
        super(extension);
        this.loader = loader;
    }

    @Override
    public ClassLoader getClassLoader() {
        return loader;
    }

    @Override
    public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
            return inflater;
        }
        return super.getSystemService(name);
    }
}
