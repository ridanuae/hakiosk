package io.github.ridanuae.hakiosk;

import android.app.admin.DeviceAdminReceiver;

/**
 * Empty on purpose. Its only job is to exist so the app can hold the
 * force-lock policy declared in res/xml/device_admin.xml; see Admin.
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
