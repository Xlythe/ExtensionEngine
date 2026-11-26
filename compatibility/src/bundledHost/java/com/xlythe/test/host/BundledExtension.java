package com.xlythe.test.host;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.TextView;

import com.xlythe.test.R;

public class BundledExtension {
    private final Context host;

    public BundledExtension(Context host) {
        this.host = host;
    }

    public String readLabel() {
        TextView view = (TextView) LayoutInflater.from(host).inflate(R.layout.bundled_label, null);
        return view.getText().toString();
    }
}
