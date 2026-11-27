package com.xlythe.test.extension;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.TextView;

import com.xlythe.test.R;

public class Extension implements com.xlythe.engine.extension.Extension.LegacyAdapter {
    private final Context host;

    public Extension(Context host) {
        this.host = host;
    }

    public String readLabel() {
        TextView view = (TextView) LayoutInflater.from(host).inflate(R.layout.bundled_label, null);
        return view.getText().toString();
    }
}
