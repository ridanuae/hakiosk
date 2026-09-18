package io.github.ridanuae.hakiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the panel back up on its own after a power cut or reboot. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent start = new Intent(context, MainActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(start);
    }
}
