package com.genymobile.scrcpy.video;

import android.media.MediaCodec;

public class CaptureReset implements SurfaceCapture.CaptureListener {

    private boolean reset;
    private MediaCodec runningMediaCodec;

    // Tambahkan method ini untuk memperbaiki error "cannot find symbol" di
    // SurfaceEncoder
    public synchronized MediaCodec getRunningMediaCodec() {
        return runningMediaCodec;
    }

    public synchronized void setRunningMediaCodec(MediaCodec codec) {
        this.runningMediaCodec = codec;
    }

    public synchronized boolean consumeReset() {
        boolean r = reset;
        reset = false;
        return r;
    }

    public synchronized void reset() {
        reset = true;
        if (runningMediaCodec != null) {
            try {
                // Memberi tahu encoder untuk berhenti memproses input secepat mungkin
                runningMediaCodec.signalEndOfInputStream();
            } catch (IllegalStateException e) {
                // MediaCodec mungkin sudah berhenti atau dalam keadaan error, abaikan
            }
        }
    }

    @Override
    public void onInvalidated() {
        reset();
    }
}