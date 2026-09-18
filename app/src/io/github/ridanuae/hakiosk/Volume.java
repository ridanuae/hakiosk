package io.github.ridanuae.hakiosk;

import android.content.Context;
import android.media.AudioManager;

/**
 * Puts the panel's media volume where the user set it.
 *
 * Why the app has to do this: a camera popup pushed to the panel plays at
 * whatever the device's media volume happens to be, and these panels have no
 * volume keys and no reachable Android sound screen. In the page itself there
 * is nothing to turn up -- HTML5 audio is already at its maximum, 1.0 -- so the
 * only lever left is the Android stream, and only an app can move it.
 *
 * Applied on every onStart() rather than once at install: a door call takes the
 * panel away and the intercom firmware is free to leave the stream wherever it
 * likes, so the level is re-asserted each time the dashboard comes back.
 */
final class Volume {

    private Volume() {
    }

    static void apply(Context context) {
        int percent = Prefs.mediaVolume(context);
        if (percent == Prefs.VOLUME_UNSET) {
            // Never chosen means never touched. The default deliberately
            // leaves the firmware's own level alone, so installing this
            // version changes nothing until someone picks a level.
            return;
        }

        AudioManager audio =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return;
        }

        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int level = Math.round(max * percent / 100f);
        // Round anything above zero up to one step. These panels report as few
        // as 7 steps, and asking for 25% of that and getting silence would
        // look like a broken setting rather than a quiet one.
        if (level < 1 && percent > 0) {
            level = 1;
        }

        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
        } catch (SecurityException e) {
            // Some firmwares refuse a volume change without notification-policy
            // access, which there is no screen here to grant. Not worth a crash
            // over a volume setting -- the popup still plays, just not louder.
        }
    }
}
