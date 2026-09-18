package com.spark.hakiosk;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Every setting the panel keeps, with its default, in one place.
 *
 * These panels have no ADB and no web port, so anything that is only a
 * constant in the source can be changed by nothing short of a rebuild and a
 * reinstall through the vendor's download-address flow. Anything worth
 * changing on the device itself belongs here instead.
 */
final class Prefs {

    private static final String FILE = "hakiosk";

    /**
     * Page the panel opens. Only the starting value -- Settings overrides it.
     *
     * The dashboard directly since v1.4. It used to be /local/panel.html, the
     * Stage 1 smoke-test page, which has been retired along with its webhook.
     */
    static final String DEFAULT_URL = "http://homeassistant.local:8123/";

    static final String KEY_URL = "panel_url";
    static final String KEY_WATCHDOG = "watchdog_enabled";
    static final String KEY_AUTOHIDE = "autohide_header";

    /**
     * Stop *drawing* the page while the night clock covers it. See
     * ScreenSleeper.hidePage() for why this is visibility and deliberately not
     * WebView.onPause(): pausing would also stop the page's JavaScript, and the
     * camera Home Assistant pushes to the panel arrives over a websocket that
     * JavaScript is holding open. A paused page never hears it.
     *
     * Default on. It changes nothing a person can see -- the page it stops
     * drawing is behind an opaque clock. Off is the setting to reach for if the
     * clock ever comes back to a stale or blank dashboard.
     */
    static final String KEY_HIDE_UNDER_CLOCK = "hide_under_clock";
    static final String KEY_SLEEP_AFTER = "sleep_after_min";
    static final String KEY_SLEEP_FROM = "sleep_from";
    static final String KEY_SLEEP_TO = "sleep_to";
    static final String KEY_VOLUME = "media_volume";
    static final String KEY_UA = "cached_ua";

    /** Last answer DialogProbe got, shown in Settings. Diagnostic only. */
    static final String KEY_PROBE = "last_probe";

    /**
     * Last WebView renderer death, and how many there have been. Diagnostic
     * only, and the only record of one there is: a renderer crash leaves
     * nothing behind on a panel with no ADB and no logcat, and until v1.20 it
     * took the whole app process with it, so there was not even an app left to
     * notice. Shown on the settings screen. See MainActivity.onRenderGone().
     */
    static final String KEY_RENDER_GONE = "last_render_gone";
    static final String KEY_RENDER_GONE_COUNT = "render_gone_count";

    /**
     * The app's record of its own death, all diagnostic and all written by
     * Vitals -- read that class for why each one exists. They are kept here with
     * everything else, but note they are the one group nothing on the settings
     * screen may edit: a reading the user can change is not evidence.
     *
     * KEY_RUN_OPEN is the load-bearing one. A run that ends deliberately clears
     * it; a process that is killed cannot, so finding it still set at startup is
     * how a death is detected at all.
     */
    static final String KEY_RUN_OPEN = "run_open";
    static final String KEY_ALIVE_NOTE = "alive_note";
    static final String KEY_LAST_DEATH = "last_death";
    static final String KEY_DEATH_COUNT = "death_count";
    static final String KEY_TRIM_WORST = "trim_worst";
    static final String KEY_TRIM_NOTE = "trim_note";

    /**
     * The last few heartbeats of this run, newest first. One reading says how
     * much memory was left; several say whether it fell off a cliff or slid away
     * over twenty minutes, which is the difference between something that ran at
     * 04:00 and something that had been growing all night.
     *
     * KEY_DEATH_BEATS is the copy taken at the next startup when the run turns
     * out to have been killed, and it is the one worth reading: the live list is
     * eight minutes wide, so the restart would otherwise have scrolled the death
     * out of it long before anybody walked up to the panel in the morning.
     */
    static final String KEY_BEATS = "beats";
    static final String KEY_DEATH_BEATS = "death_beats";
    static final String KEY_CRASH = "last_crash";
    static final String KEY_CRASH_COUNT = "crash_count";
    static final String KEY_RECREATE_COUNT = "recreate_count";
    static final String KEY_RECREATE_NOTE = "recreate_note";

    /**
     * Door station, for DoorStation.status(). Editable on the settings screen;
     * what is stored there wins, and these are only the starting values.
     *
     * Only the user is defaulted, because "admin" is the Hikvision factory
     * account and is the same on every one of these stations. The address is
     * yours and nobody else's, so it starts blank.
     *
     * The password stays empty and is typed by hand. Do not be tempted to bake
     * one in: this APK is normally served unauthenticated from HA's /local/, so
     * anybody already on the LAN could pull it and read the string straight out
     * of the dex.
     */
    static final String DEFAULT_DOOR_HOST = "";
    static final String DEFAULT_DOOR_USER = "admin";
    static final String DEFAULT_DOOR_PASS = "";

    static final String KEY_DOOR_HOST = "door_host";
    static final String KEY_DOOR_USER = "door_user";
    static final String KEY_DOOR_PASS = "door_pass";

    /** Idle delays offered in Settings, in minutes. 0 means never sleep. */
    static final int[] SLEEP_CHOICES = { 0, 1, 2, 5, 10, 30 };

    /** Minutes past midnight, or -1 for "no window, any time of day". */
    static final int NO_WINDOW = -1;

    /**
     * "Leave the firmware's own level alone", and the default. Chosen over
     * 100 so that installing this version is not itself a volume change.
     */
    static final int VOLUME_UNSET = -1;

    /** Media volumes offered in Settings, in percent. See Volume.java. */
    static final int[] VOLUME_CHOICES = { VOLUME_UNSET, 25, 50, 75, 100 };

    private Prefs() {
    }

    static SharedPreferences of(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String url(Context context) {
        String url = of(context).getString(KEY_URL, DEFAULT_URL);
        return url == null || url.trim().length() == 0 ? DEFAULT_URL : url.trim();
    }

    /**
     * Each of these falls back to its default only when nothing has been saved.
     * A value the user has cleared on purpose stays cleared -- that is how the
     * password gets removed from a panel without a rebuild.
     */
    static String doorHost(Context context) {
        return stored(context, KEY_DOOR_HOST, DEFAULT_DOOR_HOST).trim();
    }

    static String doorUser(Context context) {
        return stored(context, KEY_DOOR_USER, DEFAULT_DOOR_USER).trim();
    }

    static String doorPassword(Context context) {
        // Not trimmed: a password may legitimately start or end with a space.
        return stored(context, KEY_DOOR_PASS, DEFAULT_DOOR_PASS);
    }

    private static String stored(Context context, String key, String fallback) {
        String value = of(context).getString(key, null);
        return value == null ? fallback : value;
    }

    static boolean watchdogEnabled(Context context) {
        return of(context).getBoolean(KEY_WATCHDOG, true);
    }

    static boolean autoHideHeader(Context context) {
        return of(context).getBoolean(KEY_AUTOHIDE, true);
    }

    static boolean hideUnderClock(Context context) {
        return of(context).getBoolean(KEY_HIDE_UNDER_CLOCK, true);
    }

    static int sleepAfterMinutes(Context context) {
        return of(context).getInt(KEY_SLEEP_AFTER, 0);
    }

    static int sleepFrom(Context context) {
        return of(context).getInt(KEY_SLEEP_FROM, NO_WINDOW);
    }

    static int sleepTo(Context context) {
        return of(context).getInt(KEY_SLEEP_TO, NO_WINDOW);
    }

    static int mediaVolume(Context context) {
        return of(context).getInt(KEY_VOLUME, VOLUME_UNSET);
    }

    static void putString(Context context, String key, String value) {
        of(context).edit().putString(key, value).commit();
    }

    static void putBoolean(Context context, String key, boolean value) {
        of(context).edit().putBoolean(key, value).commit();
    }

    static void putInt(Context context, String key, int value) {
        of(context).edit().putInt(key, value).commit();
    }
}
