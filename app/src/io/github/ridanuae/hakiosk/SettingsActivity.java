package io.github.ridanuae.hakiosk;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * The gear screen. Everything here exists because these panels have no ADB,
 * no SSH and no web port -- anything not adjustable from this screen can only
 * be changed by a rebuild and a reinstall through the vendor downloader.
 *
 * One listener class per kind of widget, switching on the view id, rather than
 * a dozen tiny classes: d8 8.2.2 cannot read anonymous classes emitted by
 * javac 21, so every listener here has to be a named class anyway.
 */
public class SettingsActivity extends Activity implements Updater.Callback {

    /** Offered when a time has never been set: a sensible night. */
    private static final int DEFAULT_FROM = 23 * 60;
    private static final int DEFAULT_TO = 7 * 60;

    private EditText urlInput;
    private Switch watchdogSwitch;
    private Switch autoHideSwitch;
    private Switch hideUnderClockSwitch;
    private TextView[] segments;
    private TextView[] volumeSegments;
    private TextView timeFrom;
    private TextView timeTo;
    private TextView updateStatus;
    private View windowRow;

    /** Set once a newer APK has been downloaded and is waiting to be installed. */
    private File readyApk;

    /** True while we are setting switch states ourselves, so listeners stay quiet. */
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        urlInput = (EditText) findViewById(R.id.url_input);
        watchdogSwitch = (Switch) findViewById(R.id.sw_watchdog);
        autoHideSwitch = (Switch) findViewById(R.id.sw_autohide);
        hideUnderClockSwitch = (Switch) findViewById(R.id.sw_hide_under_clock);
        timeFrom = (TextView) findViewById(R.id.time_from);
        timeTo = (TextView) findViewById(R.id.time_to);
        updateStatus = (TextView) findViewById(R.id.update_status);
        windowRow = findViewById(R.id.window_row);
        segments = new TextView[] {
                (TextView) findViewById(R.id.seg_0),
                (TextView) findViewById(R.id.seg_1),
                (TextView) findViewById(R.id.seg_2),
                (TextView) findViewById(R.id.seg_3),
                (TextView) findViewById(R.id.seg_4),
                (TextView) findViewById(R.id.seg_5),
        };
        volumeSegments = new TextView[] {
                (TextView) findViewById(R.id.vol_0),
                (TextView) findViewById(R.id.vol_1),
                (TextView) findViewById(R.id.vol_2),
                (TextView) findViewById(R.id.vol_3),
                (TextView) findViewById(R.id.vol_4),
        };

        urlInput.setText(Prefs.url(this));
        ((EditText) findViewById(R.id.door_host)).setText(Prefs.doorHost(this));
        ((EditText) findViewById(R.id.door_user)).setText(Prefs.doorUser(this));
        ((EditText) findViewById(R.id.door_pass)).setText(Prefs.doorPassword(this));
        showSleepChoice(Prefs.sleepAfterMinutes(this));
        showVolumeChoice(Prefs.mediaVolume(this));
        showWindow();
        ((TextView) findViewById(R.id.version_value)).setText(versionText());
        ((TextView) findViewById(R.id.info_text)).setText(infoText());

        Tap tap = new Tap(this);
        findViewById(R.id.nav_back).setOnClickListener(tap);
        findViewById(R.id.save_url).setOnClickListener(tap);
        findViewById(R.id.reload).setOnClickListener(tap);
        findViewById(R.id.clear_cache).setOnClickListener(tap);
        findViewById(R.id.check_update).setOnClickListener(tap);
        findViewById(R.id.home_app).setOnClickListener(tap);
        findViewById(R.id.door_save).setOnClickListener(tap);
        timeFrom.setOnClickListener(tap);
        timeTo.setOnClickListener(tap);
        for (int i = 0; i < segments.length; i++) {
            segments[i].setOnClickListener(tap);
        }
        for (int i = 0; i < volumeSegments.length; i++) {
            volumeSegments[i].setOnClickListener(tap);
        }

        Toggle toggle = new Toggle(this);
        watchdogSwitch.setOnCheckedChangeListener(toggle);
        autoHideSwitch.setOnCheckedChangeListener(toggle);
        hideUnderClockSwitch.setOnCheckedChangeListener(toggle);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Values, not listeners: a switch set from stored state must not fire
        // the handler that stores it.
        binding = true;
        watchdogSwitch.setChecked(Prefs.watchdogEnabled(this));
        autoHideSwitch.setChecked(Prefs.autoHideHeader(this));
        hideUnderClockSwitch.setChecked(Prefs.hideUnderClock(this));
        binding = false;
        // The audio line in here is a live reading, so it has to be re-taken
        // on every visit, not just the first.
        ((TextView) findViewById(R.id.info_text)).setText(infoText());
    }

    /**
     * A door call can land on this screen too, so it arms the watchdog exactly
     * like the panel does. Leaving for one of our own screens -- or for
     * Android's Activate screen -- suppresses that first.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) {
            PanelWatchdog.arm(this);
        }
    }

    void onTap(int id) {
        if (id == R.id.nav_back) {
            finish();
        } else if (id == R.id.save_url) {
            Prefs.putString(this, Prefs.KEY_URL, urlInput.getText().toString().trim());
            MainActivity.requestReload(false);
            finish();
        } else if (id == R.id.reload) {
            MainActivity.requestReload(false);
            finish();
        } else if (id == R.id.clear_cache) {
            MainActivity.requestReload(true);
            finish();
        } else if (id == R.id.time_from) {
            pickTime(true);
        } else if (id == R.id.time_to) {
            pickTime(false);
        } else if (id == R.id.check_update) {
            checkOrInstall();
        } else if (id == R.id.home_app) {
            openHomeSettings();
        } else if (id == R.id.door_save) {
            saveAndTestDoorStation();
        } else {
            for (int i = 0; i < segments.length; i++) {
                if (segments[i].getId() == id) {
                    Prefs.putInt(this, Prefs.KEY_SLEEP_AFTER, Prefs.SLEEP_CHOICES[i]);
                    showSleepChoice(Prefs.SLEEP_CHOICES[i]);
                    return;
                }
            }
            for (int i = 0; i < volumeSegments.length; i++) {
                if (volumeSegments[i].getId() == id) {
                    Prefs.putInt(this, Prefs.KEY_VOLUME, Prefs.VOLUME_CHOICES[i]);
                    showVolumeChoice(Prefs.VOLUME_CHOICES[i]);
                    // Straight away, not on the way back to the dashboard, so
                    // the next press of a camera is already at the new level
                    // and you can judge it without leaving this screen.
                    Volume.apply(this);
                    return;
                }
            }
        }
    }

    void onToggle(int id, boolean checked) {
        if (binding) {
            return;
        }
        if (id == R.id.sw_watchdog) {
            Prefs.putBoolean(this, Prefs.KEY_WATCHDOG, checked);
            if (!checked) {
                PanelWatchdog.cancel(this);
            }
        } else if (id == R.id.sw_autohide) {
            Prefs.putBoolean(this, Prefs.KEY_AUTOHIDE, checked);
        } else if (id == R.id.sw_hide_under_clock) {
            // Picked up by ScreenSleeper.applyPrefs() when the panel comes back.
            Prefs.putBoolean(this, Prefs.KEY_HIDE_UNDER_CLOCK, checked);
        }
    }

    /**
     * Android's own home-app picker, and the way back off this panel.
     *
     * The escape hatch for making HAKiosk the home app: once it is, the Home
     * button no longer reaches the vendor launcher, and on a device with no ADB
     * and no web port that would otherwise be one-way. This ships a version
     * ahead of the manifest change on purpose -- the way out has to be on the
     * wall, and proved, before the thing it undoes.
     *
     * ACTION_HOME_SETTINGS is API 21 against a minSdk of 19, which is safe
     * because it is a compile-time String constant; on anything older it simply
     * resolves to nothing and says so rather than throwing.
     */
    private void openHomeSettings() {
        Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(getPackageManager()) == null) {
            setStatus(getString(R.string.home_app_missing));
            return;
        }
        // Another of our deliberate jumps off this screen -- see onStop().
        PanelWatchdog.suppressNext();
        startActivity(intent);
    }

    /**
     * Store the door-station details and immediately say whether they work.
     *
     * The test matters more than the saving. Without it the only way to find out
     * that the password was wrong would be to have a door call and notice the
     * panel took four minutes instead of four seconds -- and every earlier
     * version of this feature failed silently in exactly that shape.
     */
    private void saveAndTestDoorStation() {
        Prefs.putString(this, Prefs.KEY_DOOR_HOST,
                ((EditText) findViewById(R.id.door_host)).getText().toString().trim());
        Prefs.putString(this, Prefs.KEY_DOOR_USER,
                ((EditText) findViewById(R.id.door_user)).getText().toString().trim());
        Prefs.putString(this, Prefs.KEY_DOOR_PASS,
                ((EditText) findViewById(R.id.door_pass)).getText().toString());

        TextView line = (TextView) findViewById(R.id.door_status);
        if (!DoorStation.configured(this)) {
            line.setText(R.string.door_note);
            return;
        }
        line.setText(R.string.checking);
        new Thread(new DoorTest(this)).start();
    }

    /** Back on the UI thread with whatever the door station said. */
    void onDoorResult(String status) {
        TextView line = (TextView) findViewById(R.id.door_status);
        line.setText(status == null
                ? "No answer. Check the address, user and password."
                : "Door station says: " + status);
    }

    private static final class DoorTest implements Runnable {
        private final SettingsActivity activity;

        DoorTest(SettingsActivity activity) {
            this.activity = activity;
        }

        public void run() {
            final String status = DoorStation.status(activity);
            activity.runOnUiThread(new DoorResult(activity, status));
        }
    }

    private static final class DoorResult implements Runnable {
        private final SettingsActivity activity;
        private final String status;

        DoorResult(SettingsActivity activity, String status) {
            this.activity = activity;
            this.status = status;
        }

        public void run() {
            activity.onDoorResult(status);
        }
    }

    private void checkOrInstall() {
        if (readyApk != null) {
            PanelWatchdog.suppressNext();
            setStatus(Updater.install(this, readyApk));
            return;
        }
        setStatus(getString(R.string.checking));
        Updater.check(this, this);
    }

    public void onResult(String message, File newerApk) {
        readyApk = newerApk;
        setStatus(message + (newerApk != null ? " Tap again to install." : ""));
    }

    private void setStatus(String message) {
        updateStatus.setText(message);
        updateStatus.setVisibility(View.VISIBLE);
    }

    private void pickTime(boolean isFrom) {
        int current = isFrom ? Prefs.sleepFrom(this) : Prefs.sleepTo(this);
        if (current == Prefs.NO_WINDOW) {
            current = isFrom ? DEFAULT_FROM : DEFAULT_TO;
        }
        new TimePickerDialog(this, new TimeSet(this, isFrom),
                current / 60, current % 60, false).show();
    }

    void onTimePicked(boolean isFrom, int hour, int minute) {
        Prefs.putInt(this, isFrom ? Prefs.KEY_SLEEP_FROM : Prefs.KEY_SLEEP_TO,
                hour * 60 + minute);
        showWindow();
    }

    private void showSleepChoice(int minutes) {
        for (int i = 0; i < segments.length; i++) {
            segments[i].setSelected(Prefs.SLEEP_CHOICES[i] == minutes);
        }
        windowRow.setVisibility(minutes == 0 ? View.GONE : View.VISIBLE);
    }

    private void showVolumeChoice(int percent) {
        for (int i = 0; i < volumeSegments.length; i++) {
            volumeSegments[i].setSelected(Prefs.VOLUME_CHOICES[i] == percent);
        }
    }

    private void showWindow() {
        timeFrom.setText(ScreenSleeper.formatTime(Prefs.sleepFrom(this)));
        timeTo.setText(ScreenSleeper.formatTime(Prefs.sleepTo(this)));
    }

    private String versionText() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /**
     * The UA line is the point of this block: the device's own web port is
     * closed, so this screen is now the only place its WebView version can be
     * read -- see "What the UA string decides" in the README.
     */
    private String infoText() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return "IP: " + ipAddress()
                + "\nScreen: " + metrics.widthPixels + "x" + metrics.heightPixels
                + " @" + metrics.density + "x"
                + "\nAndroid: " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")"
                + "\nDevice: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\nUpdate from: " + Updater.apkUrl(this)
                // Read fresh every time this screen opens -- see Audio.describe().
                + "\nAudio: " + Audio.describe(this)
                // What the clock actually goes on, since v1.11. See DialogProbe.
                + "\nDialog: "
                + Prefs.of(this).getString(Prefs.KEY_PROBE, "(not probed yet)")
                // Since v1.21, and the reason that version exists: the app's
                // record of its own death. "Last run" is the one to read first
                // -- it carries the time and the memory reading from the last
                // minute the previous run was alive. See Vitals.
                + "\nLast run: " + Vitals.lastRunText(this)
                + beatsLine()
                + "\nMemory: " + Vitals.memoryText(this)
                + "\nPressure: " + Vitals.trimText(this)
                + guardsLines()
                + "\n\nUA: " + Prefs.of(this).getString(Prefs.KEY_UA, "(not read yet)");
    }

    /**
     * The minutes leading into the last death, and left out entirely on a panel
     * that has not had one -- an empty label on a screen this size is worse than
     * no label. See Vitals.deathBeatsText() for how to read it.
     */
    private String beatsLine() {
        String beats = Vitals.deathBeatsText(this);
        return beats.length() == 0 ? "" : "\nInto death: " + beats;
    }

    /**
     * The three ways of dying that are *not* happening, on one line while they
     * are all zero and on three the moment any of them is not.
     *
     * v1.22 folds them up. They were three full lines each saying "no", which is
     * the answer they have given every time they have been read, and the screen
     * is 552px tall with a UA string still to fit on it. None of them is dropped:
     * a zero here is evidence, and the count that produced it is what rules these
     * out. It is only the wording that shrinks.
     */
    private String guardsLines() {
        int renderer = Prefs.of(this).getInt(Prefs.KEY_RENDER_GONE_COUNT, 0);
        int crashes = Prefs.of(this).getInt(Prefs.KEY_CRASH_COUNT, 0);
        int rebuilds = Prefs.of(this).getInt(Prefs.KEY_RECREATE_COUNT, 0);
        if (renderer == 0 && crashes == 0 && rebuilds == 0) {
            return "\nAlso: renderer 0, crash 0, rebuilt 0";
        }
        return "\nRenderer: " + rendererText()
                + "\nCrash: " + Vitals.crashText(this)
                + "\nRebuilt: " + Vitals.recreateText(this);
    }

    /**
     * "OK" is the answer we want here. Anything else is worth knowing about:
     * repeated renderer deaths mean the page is too heavy for this hardware,
     * which on these panels usually means a camera stream. See
     * MainActivity.onRenderGone().
     */
    private String rendererText() {
        int count = Prefs.of(this).getInt(Prefs.KEY_RENDER_GONE_COUNT, 0);
        if (count == 0) {
            return "OK (no crashes)";
        }
        return count + "x, last " + Prefs.of(this).getString(
                Prefs.KEY_RENDER_GONE, "?");
    }

    /** NetworkInterface rather than WifiManager: these panels may be wired. */
    private String ipAddress() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (network.isLoopback() || !network.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // Falls through to "unknown"; this line is information, not function.
        }
        return "unknown";
    }

    private static final class Tap implements View.OnClickListener {
        private final SettingsActivity activity;

        Tap(SettingsActivity activity) {
            this.activity = activity;
        }

        public void onClick(View view) {
            activity.onTap(view.getId());
        }
    }

    private static final class Toggle implements CompoundButton.OnCheckedChangeListener {
        private final SettingsActivity activity;

        Toggle(SettingsActivity activity) {
            this.activity = activity;
        }

        public void onCheckedChanged(CompoundButton button, boolean checked) {
            activity.onToggle(button.getId(), checked);
        }
    }

    private static final class TimeSet implements TimePickerDialog.OnTimeSetListener {
        private final SettingsActivity activity;
        private final boolean isFrom;

        TimeSet(SettingsActivity activity, boolean isFrom) {
            this.activity = activity;
            this.isFrom = isFrom;
        }

        public void onTimeSet(TimePicker view, int hour, int minute) {
            activity.onTimePicked(isFrom, hour, minute);
        }
    }
}
