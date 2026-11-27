package com.xlythe.test.extension;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.TextView;

import com.xlythe.test.R;

public class Extension {
    private final Context context;

    public Extension(Context context) {
        this.context = context;
    }

    public String revision() {
        return com.xlythe.test.BuildConfig.VERSION_CODE + "/" + context.getString(R.string.fixture_revision);
    }

    public String readLabel() {
        TextView view = (TextView) LayoutInflater.from(context).inflate(R.layout.label, null);
        return view.getText().toString();
    }
}
