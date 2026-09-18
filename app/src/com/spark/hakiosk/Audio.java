package com.spark.hakiosk;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

import java.util.List;

/**
 * "Is this panel playing something right now?" -- asked by ScreenSleeper so the
 * night clock stays off a camera Home Assistant has pushed to the screen.
 *
 * v1.8 asked `isMusicActive()` and that was the wrong question. These panels
 * leave it stuck true after a stream ends: PanelWatchdog already works around
 * the same firmware behaviour for door-call audio, which is what
 * GIVE_UP_AFTER_MS is for over there. The result was that once a camera had
 * played, the clock never came back at all.
 *
 * getActivePlaybackConfigurations() is the accurate reading -- players that are
 * actually playing this instant, and an empty list once the WebView lets the
 * stream go. It arrived in API 26 and these panels run Android 10, so that is
 * the path they take; isMusicActive() is kept for anything older, where a
 * sticky reading still beats no reading.
 *
 * The return type is deliberately held as a wildcard List: naming
 * AudioPlaybackConfiguration in our own code would put an API-26 class in the
 * dex for a build whose minSdk is 19.
 */
final class Audio {

    private Audio() {
    }

    private static AudioManager manager(Context context) {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    static boolean playing(Context context) {
        AudioManager audio = manager(context);
        if (audio == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            List<?> active = audio.getActivePlaybackConfigurations();
            return active != null && !active.isEmpty();
        }
        return audio.isMusicActive();
    }

    /**
     * Both readings side by side, for the INFO block on the settings screen.
     *
     * This exists because there is no other way to see it: no ADB, no logcat,
     * no web port. When the clock misbehaves around a camera, this line is the
     * evidence -- open Settings right after the camera closes and read it.
     */
    static String describe(Context context) {
        AudioManager audio = manager(context);
        if (audio == null) {
            return "(no audio service)";
        }
        String players = "n/a";
        if (Build.VERSION.SDK_INT >= 26) {
            List<?> active = audio.getActivePlaybackConfigurations();
            players = String.valueOf(active == null ? 0 : active.size());
        }
        // rec= is the candidate replacement for the stuck isMusicActive() in
        // PanelWatchdog: a door call is two-way, so the mic must be live while
        // one is up and idle after it. Read it on a live panel during a ringing
        // call and during an answered one before anything is built on it.
        // Held as List<?> for the same reason as above -- naming
        // AudioRecordingConfiguration would put an API-24 class in a minSdk 19 dex.
        String recorders = "n/a";
        if (Build.VERSION.SDK_INT >= 24) {
            List<?> rec = audio.getActiveRecordingConfigurations();
            recorders = String.valueOf(rec == null ? 0 : rec.size());
        }
        return "players=" + players
                + " rec=" + recorders
                + " musicActive=" + audio.isMusicActive()
                + " mode=" + audio.getMode()
                + " vol=" + audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }
}
