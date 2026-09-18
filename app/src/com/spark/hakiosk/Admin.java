package com.spark.hakiosk;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Device-administrator rights, used for one thing only: turning the screen off
 * at the exact minute we asked for, with DevicePolicyManager.lockNow().
 *
 * Strictly opt-in from Settings. Without it the panel simply drops
 * FLAG_KEEP_SCREEN_ON and lets the device's own screen timeout do the work,
 * which is the behaviour the vendor app has and needs no permission at all.
 *
 * The cost, and the reason the settings row says so out loud: while this is
 * active Android refuses to uninstall the app until it is turned off again.
 */
final class Admin {

    private Admin() {
    }

    static ComponentName component(Context context) {
        return new ComponentName(context, AdminReceiver.class);
    }

    static DevicePolicyManager policy(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean isActive(Context context) {
        DevicePolicyManager policy = policy(context);
        return policy != null && policy.isAdminActive(component(context));
    }

    static void lockNow(Context context) {
        if (isActive(context)) {
            policy(context).lockNow();
        }
    }

    /** Android shows its own Activate screen; we never grant this ourselves. */
    static Intent activateIntent(Context context) {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.admin_explanation));
        return intent;
    }

    static void deactivate(Context context) {
        if (isActive(context)) {
            policy(context).removeActiveAdmin(component(context));
        }
    }
}
