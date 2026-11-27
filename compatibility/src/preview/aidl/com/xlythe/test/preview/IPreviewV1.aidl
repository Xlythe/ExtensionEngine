package com.xlythe.test.preview;
import android.view.Surface;
import com.xlythe.test.preview.IPreviewEvents;
interface IPreviewV1 {
    void start(IBinder approval, in Surface preview, IPreviewEvents events);
    void stop(IPreviewEvents events);
}
