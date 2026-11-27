package com.xlythe.test.preview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.journeyapps.barcodescanner.BarcodeView;

/** Private result-only scanner. Only decoded text crosses the APK boundary. */
public final class ScannerActivity extends Activity {
    private BarcodeView scanner;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        scanner = new BarcodeView(this);
        setContentView(scanner);
        scanner.decodeSingle(result -> {
            String text = result.getText();
            if (text != null && text.length() <= 8192) {
                setResult(RESULT_OK, new Intent().putExtra("barcode", text));
            }
            finish();
        });
    }
    @Override protected void onResume() { super.onResume(); scanner.resume(); }
    @Override protected void onPause() { scanner.pause(); super.onPause(); }
}
