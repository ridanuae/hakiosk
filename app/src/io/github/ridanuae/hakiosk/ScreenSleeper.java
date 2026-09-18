package io.github.ridanuae.hakiosk;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

import java.util.Calendar;

/**
 * Shows the night clock over the page when nobody has touched the panel for a
 * while, and takes it away again on a touch, when a camera opens behind it, or
 * when the window ends.
 *
 * Until v1.6 this turned the screen off instead: it dropped FLAG_KEEP_SCREEN_ON
 * and, with device-administrator rights, called lockNow() to go dark on the
 * exact minute. That worked, but a dark screen can only be undone by a touch --
 * the panel stayed off all morning after the window had ended, until somebody
 * walked up to it. So the screen now stays on, dimmed hard, with ClockScreen
 * over the page. Nothing here needs device admin any more.
 *
 * The window is a gate, not an alarm: the clock appears when the idle timer
 * expires *and* the clock is inside the window, which means it can appear up to
 * one idle period after the window opens. That is deliberate -- an alarm on the
 * window edge would light the clock up under someone still standing there.
 *
 * The camera half of it, added in v1.8 and only right in v1.11: the clock is a
 * view over the page and not a paused WebView, so a camera Home Assistant
 * pushes to the panel opens and plays *behind* the clock -- audible, invisible.
 * Whether one is open is asked of the page itself, through DialogProbe. Read
 * that class before reaching for AudioManager here; three versions were spent
 * on that road.
 *
 * v1.22 stops the covered page being *drawn* (hidePage) without stopping it
 * running, which is the distinction the paragraph above turns on: the page must
 * stay live or Home Assistant cannot reach it, and the probe cannot answer.
 */
final class ScreenSleeper {

    /**
     * Backlight while the clock is up: low enough not to light a dark room,
     * high enough that the panel does not look broken. Tune here -- these
     * panels have no ADB, so this is a rebuild either way.
     *
     * Worth knowing when reading a bug report: at this level, grey on black,
     * the clock is hard to tell from a screen that has switched itself off.
     */
    private static final float DIM = 0.06f;

    private static final long MINUTE_MS = 60000L;

    /**
     * How often to ask the page about the dialog. The minute tick is far too
     * slow to catch a 20-second popup, hence a second, faster watch that runs
     * only while the clock is showing or while a camera is holding it off.
     */
    private static final long DIALOG_WATCH_MS = 2000L;

    private final Activity activity;
    private final ClockScreen clock;
    private final Handler handler = new Handler();
    /** Not final: a renderer crash replaces the WebView, and so the probe. */
    private DialogProbe probe;
    /** The same WebView the probe holds, kept for hidePage(). */
    private WebView web;
    private final SleepTask sleepTask = new SleepTask(this);
    private final TickTask tickTask = new TickTask(this);
    private final DialogWatchTask dialogWatchTask = new DialogWatchTask(this);
    private final SleepAnswer sleepAnswer = new SleepAnswer(this);
    private final WatchAnswer watchAnswer = new WatchAnswer(this);

    private int afterMinutes;
    private int windowFrom;
    private int windowTo;

    /**
     * Window.setFlags always fires a window-attributes change, even when the
     * flags did not move, so setting this on every touch would ask for a
     * relayout on every touch. Track it and only speak up on a real change.
     */
    private boolean keepingScreenOn;

    /** Set while a sleep is being skipped because a camera is on screen. */
    private boolean dialogHeldOff;

    /** Re-read from Prefs on every applyPrefs(). See hidePage(). */
    private boolean hideUnderClock;

    ScreenSleeper(Activity activity, ClockScreen clock, WebView web) {
        this.activity = activity;
        this.clock = clock;
        this.web = web;
        this.probe = new DialogProbe(web, handler);
    }

    /**
     * Point the clock's probe at a new WebView, after a renderer crash forced
     * MainActivity to build one. Everything in flight against the old view is
     * dropped first -- see DialogProbe.cancel() for why that answer must not be
     * allowed to land.
     */
    void rebind(WebView web) {
        // stop() first, while the field still points at the old view: it puts
        // that view's visibility back before anything else touches it.
        stop();
        probe.cancel();
        this.web = web;
        probe = new DialogProbe(web, handler);
        dialogHeldOff = false;
    }

    /** Re-read settings, so a change on the settings screen lands immediately. */
    void applyPrefs() {
        afterMinutes = Prefs.sleepAfterMinutes(activity);
        windowFrom = Prefs.sleepFrom(activity);
        windowTo = Prefs.sleepTo(activity);
        hideUnderClock = Prefs.hideUnderClock(activity);
        onUserActivity();
    }

    /** Every touch anywhere on the panel takes the clock away and restarts it. */
    void onUserActivity() {
        dialogHeldOff = false;
        restartIdle();
    }

    /** The idle countdown on its own, without clearing the camera state. */
    private void restartIdle() {
        wake();
        keepScreenOn(true);
        handler.removeCallbacks(sleepTask);
        if (afterMinutes > 0) {
            handler.postDelayed(sleepTask, afterMinutes * MINUTE_MS);
        }
    }

    /** Leaving the panel: no clock, no timers, and give the backlight back. */
    void stop() {
        handler.removeCallbacks(sleepTask);
        wake();
    }

    void sleepNow() {
        if (!insideWindow()) {
            // Outside the hours the user picked, so just look again later.
            handler.postDelayed(sleepTask, afterMinutes * MINUTE_MS);
            return;
        }
        probe.ask(sleepAnswer);
    }

    /** The idle timer has expired and the page has told us about the dialog. */
    void onSleepAnswer(boolean dialogOpen) {
        if (dialogOpen) {
            // A camera is on screen. Covering it with the clock is the bug
            // this whole path exists to avoid, so look again shortly.
            dialogHeldOff = true;
            handler.postDelayed(sleepTask, DIALOG_WATCH_MS);
            return;
        }
        if (dialogHeldOff) {
            // It has just closed. v1.8 raised the clock right here, which made
            // the panel look like it had switched itself off the instant the
            // camera went: the idle timer had already expired while the camera
            // was up, so there was nothing left to wait for. Start the idle
            // period again instead -- watching a camera counts as using the
            // panel, so the clock should be as far off as it is after a touch.
            dialogHeldOff = false;
            restartIdle();
            return;
        }
        showClock();
    }

    private void showClock() {
        if (!clock.showing()) {
            clock.show();
            hidePage();
            setBrightness(DIM);
            // The clock is no use if the device's own timeout blacks it out.
            keepScreenOn(true);
        }
        scheduleTick();
        scheduleDialogWatch();
    }

    /**
     * Stop drawing the page while the clock covers it -- v1.22, and the one
     * memory saving available that costs nothing anybody can see.
     *
     * The clock is an opaque view laid over the WebView, and Android does not
     * skip a covered sibling: the dashboard was being redrawn behind it every
     * frame, all night, for nobody. An INVISIBLE view is not drawn at all.
     *
     * INVISIBLE and deliberately not GONE. GONE takes the view out of layout,
     * which resizes the WebView to nothing and makes the page reflow -- so the
     * dashboard would come back rebuilt, the opposite of what this is for.
     *
     * Deliberately not WebView.onPause() either, which would be the bigger
     * saving: pausing stops the page's JavaScript, and the camera Home Assistant
     * pushes to this panel arrives on a websocket that JavaScript is holding
     * open. A paused page never hears it, and DialogProbe -- the thing that
     * takes the clock away when a camera opens -- would go deaf with it. Drawing
     * is the only part that can be dropped safely.
     */
    private void hidePage() {
        if (web != null && hideUnderClock) {
            web.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Unconditional, unlike hidePage(): a page left invisible is a black panel,
     * so nothing here may depend on a setting that could have been turned off
     * while the clock was up.
     */
    private void showPage() {
        if (web != null) {
            web.setVisibility(View.VISIBLE);
        }
    }

    /** Every couple of seconds while the clock is up: has a camera opened? */
    void onDialogWatch() {
        if (!clock.showing()) {
            return;
        }
        probe.ask(watchAnswer);
    }

    void onWatchAnswer(boolean dialogOpen) {
        if (!clock.showing()) {
            return;
        }
        if (dialogOpen) {
            // Opened behind the clock. Treat it exactly like a touch: clock
            // away, brightness back, idle timer restarted.
            restartIdle();
            return;
        }
        scheduleDialogWatch();
    }

    private void scheduleDialogWatch() {
        handler.removeCallbacks(dialogWatchTask);
        handler.postDelayed(dialogWatchTask, DIALOG_WATCH_MS);
    }

    /** Once a minute while the clock is up: repaint it, or drop it and go back. */
    void onTick() {
        if (!clock.showing()) {
            return;
        }
        if (!insideWindow()) {
            // The window ended. Back to the dashboard without anyone tapping.
            onUserActivity();
            return;
        }
        clock.tick();
        scheduleTick();
    }

    /** Land on the minute, so the displayed time is never a minute stale. */
    private void scheduleTick() {
        handler.removeCallbacks(tickTask);
        handler.postDelayed(tickTask,
                MINUTE_MS - System.currentTimeMillis() % MINUTE_MS + 50L);
    }

    private void wake() {
        handler.removeCallbacks(tickTask);
        handler.removeCallbacks(dialogWatchTask);
        if (clock.showing()) {
            clock.hide();
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        }
        // Outside the clock.showing() test on purpose. This is the only path
        // that gives the page back, and it has to run even if the two ever
        // disagree -- an invisible WebView with no clock over it is a panel that
        // looks broken and cannot be fixed without a restart.
        showPage();
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        attributes.screenBrightness = value;
        activity.getWindow().setAttributes(attributes);
    }

    private void keepScreenOn(boolean on) {
        if (on == keepingScreenOn) {
            return;
        }
        keepingScreenOn = on;
        if (on) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** No window set means any time of day. A window may run past midnight. */
    private boolean insideWindow() {
        if (windowFrom == Prefs.NO_WINDOW || windowTo == Prefs.NO_WINDOW
                || windowFrom == windowTo) {
            return true;
        }
        Calendar now = Calendar.getInstance();
        int minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (windowFrom < windowTo) {
            return minutes >= windowFrom && minutes < windowTo;
        }
        return minutes >= windowFrom || minutes < windowTo;
    }

    static String formatTime(int minutesPastMidnight) {
        if (minutesPastMidnight == Prefs.NO_WINDOW) {
            return "--:--";
        }
        int hour = minutesPastMidnight / 60;
        int minute = minutesPastMidnight % 60;
        // Midnight and noon are the 12 o'clock hours, not 0.
        int shown = hour % 12 == 0 ? 12 : hour % 12;
        return shown + ":" + (minute < 10 ? "0" : "") + minute
                + (hour < 12 ? " AM" : " PM");
    }

    private static final class SleepTask implements Runnable {
        private final ScreenSleeper sleeper;

        SleepTask(ScreenSleeper sleeper) {
            this.sleeper = sleeper;
        }

        public void run() {
            sleeper.sleepNow();
        }
    }

    private static final class TickTask implements Runnable {
        private final ScreenSleeper sleeper;

        TickTask(ScreenSleeper sleeper) {
            this.sleeper = sleeper;
        }

        public void run() {
            sleeper.onTick();
        }
    }

    private static final class DialogWatchTask implements Runnable {
        private final ScreenSleeper sleeper;

        DialogWatchTask(ScreenSleeper sleeper) {
            this.sleeper = sleeper;
        }

        public void run() {
            sleeper.onDialogWatch();
        }
    }

    private static final class SleepAnswer implements DialogProbe.Answer {
        private final ScreenSleeper sleeper;

        SleepAnswer(ScreenSleeper sleeper) {
            this.sleeper = sleeper;
        }

        public void onDialogOpen(boolean open) {
            sleeper.onSleepAnswer(open);
        }
    }

    private static final class WatchAnswer implements DialogProbe.Answer {
        private final ScreenSleeper sleeper;

        WatchAnswer(ScreenSleeper sleeper) {
            this.sleeper = sleeper;
        }

        public void onDialogOpen(boolean open) {
            sleeper.onWatchAnswer(open);
        }
    }
}
