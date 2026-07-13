package com.explapp.apkd;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.view.MotionEvent;

/** Touch feedback for the Android 4.4 APK downloader module only. */
public class SoundMainActivity extends MainActivity {
    private ToneGenerator tones;
    private float downX;
    private float downY;
    private long lastSoundAt;

    @Override public void onCreate(Bundle savedInstanceState) {
        tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 47);
        super.onCreate(savedInstanceState);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX();
            downY = event.getRawY();
        } else if (event.getAction() == MotionEvent.ACTION_UP
                && Math.abs(event.getRawX() - downX) < 18f
                && Math.abs(event.getRawY() - downY) < 18f) {
            playClick();
        }
        return super.dispatchTouchEvent(event);
    }

    private void playClick() {
        if (tones == null || System.currentTimeMillis() - lastSoundAt < 75L) return;
        lastSoundAt = System.currentTimeMillis();
        tones.startTone(ToneGenerator.TONE_PROP_BEEP, 55);
    }

    @Override public void onBackPressed() {
        if (tones != null) tones.startTone(ToneGenerator.TONE_PROP_BEEP2, 80);
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (tones != null) {
            tones.release();
            tones = null;
        }
        super.onDestroy();
    }
}
