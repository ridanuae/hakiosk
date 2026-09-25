package io.github.ridanuae.hakiosk;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Restarts the app before Android kills it.
 *
 * Three days of instrumentation (v1.21 to v1.24) found the shape of the problem
 * without finding its owner. Free memory drains while this panel is in use --
 * ~200 MB/hour with a camera full screen, ~90 MB/hour without one -- and the
 * memory is invisible to every counter available: `getTotalPss()`,
 * `getMemoryStat("summary.graphics")` reads 0, `summary.total-swap` reads 9M,
 * and /proc/meminfo's categories move by a tenth of the fall. That signature is
 * DMA-BUF: buffers a kernel driver holds on a process's behalf and releases only
 * when that process dies.
 *
 * Which is the one thing we *can* do. Measured twice on a live panel: killing a
 * 19-hour-old process freed **873 MB**, and killing a 17-minute-old one freed
 * 31 MB. A page reload frees nothing (887M before, 865M after) because it is the
 * same WebView and the same renderer underneath.
 *
 * So this is not a fix for the leak. It is a fix for the *death*: come back on
 * our own terms, two seconds of black screen at a moment nobody is watching,
 * rather than being killed at 03:00 and leaving the panel on the vendor
 * launcher until somebody walks up to it.
 *
 * **It works.** First full day in use: 21 hours, five self-restarts, no
 * deaths, and the one that fired at 22:47 on `free 399M` had the panel back at
 * `free 972M` an hour later -- ~573 MB returned by the exit, on a panel in
 * daily use rather than on the bench.
 *
 * v1.27 closes the hole that day also exposed: v1.26 gave up at the first line
 * unless we were the screen, so for as long as anything else held the panel the
 * guard was simply off. See BG_GRACE_MS.
 *
 * Named nested classes only, no anonymous ones -- see MainActivity.
 */
final class MemoryGuard {

    /**
     * Restart below this, if the panel is idle. Chosen well clear of the danger
     * zone rather than close to it: the observed deaths were at 146M and ~190M
     * free, and Android's own `lowMemory` flag first appeared between 146M and
     * 270M. 400M leaves room to wait for an idle moment instead of acting the
     * instant things get tight.
     */
    private static final long LOW_WATER_MB = 400L;

    /**
     * Restart below this even if the panel is *not* idle -- a camera may be on
     * screen and it will be interrupted. Above the observed death points, so
     * there is still margin, but low enough that we are clearly losing. Taking a
     * camera away is worse than nothing; having the panel dead until morning is
     * worse than that.
     */
    private static final long CRITICAL_MB = 250L;

    /**
     * Never more often than this. If a restart does not actually recover the
     * memory -- the assumption this whole class rests on -- the failure mode
     * must be one restart every two hours, not an endless loop of them.
     */
    private static final long MIN_INTERVAL_MS = 2 * 60 * 60 * 1000L;

    /** Long enough for the process to be gone, short enough not to be noticed. */
    private static final long RELAUNCH_MS = 3000L;

    /**
     * How long the panel must have been off screen before a restart is allowed
     * while something else holds it -- v1.27, and the hole v1.26 left.
     *
     * v1.26 returned at the first line unless we were foreground, which is
     * right for a door call and wrong for everything else: `Pressure: hidden`
     * on a panel in use proves it does get backgrounded, and two test panels
     * were found one morning with the vendor app in front. Whenever that
     * happens the guard was simply off, and the drain went on underneath it
     * until Android killed us -- the death this class exists to prevent.
     *
     * Derived from PanelWatchdog rather than guessed, because "how long can a
     * call last?" is a question that class already answers: it waits
     * RETURN_AFTER_MS, then polls the door station for at most POLL_GIVE_UP_MS
     * before it stops believing any call is up. Past that sum the watchdog has
     * itself concluded there is no call *and* has already tried to bring us
     * back -- so if we are still hidden, its toFront() was refused and the only
     * move left is ours. The extra minute keeps us strictly behind it.
     */
    private static final long BG_GRACE_MS = PanelWatchdog.RETURN_AFTER_MS
            + PanelWatchdog.POLL_GIVE_UP_MS + 60000L;

    /**
     * What kind of restart it was. These are the tags that reach the settings
     * screen, and TAG_BG is also what decides the background counter -- so they
     * are constants rather than literals at three call sites: a typo in one
     * copy would have read as a foreground restart forever, which is the exact
     * mistake this version exists to stop making.
     */
    private static final String TAG_IDLE = "";
    private static final String TAG_BG = "  (bg)";
    private static final String TAG_HAND = "  (by hand)";

    /**
     * One door-station question at a time. Without this, every heartbeat under
     * the low-water mark would start another thread while the first was still
     * waiting on a 4-second timeout.
     */
    private static volatile boolean asking;

    private MemoryGuard() {
    }

    /**
     * Called from the once-a-minute heartbeat, which is deliberately the only
     * caller: the readings this decides on are taken there, and a check on any
     * faster path would be reading noise.
     *
     * Whether we are on screen, and for how long we have not been, are read off
     * the activity rather than passed in: the background path below finishes on
     * another thread some seconds later and has to ask the same question again
     * at the moment it acts, so a copy taken here would be the wrong one.
     *
     * @param idle the night clock is up, so nobody is looking at it. Only
     *             meaningful while we are on screen; see checkHidden().
     */
    static void check(MainActivity activity, boolean idle) {
        long free = freeMb(activity);
        if (free < 0) {
            return;
        }
        boolean urgent = free <= CRITICAL_MB;
        if (free > LOW_WATER_MB) {
            return;
        }
        SharedPreferences prefs = Prefs.of(activity);
        long last = prefs.getLong(Prefs.KEY_RESTART_AT, 0L);
        long now = System.currentTimeMillis();
        // now < last guards a clock that has gone backwards -- these panels get
        // their time from a router that has been wrong before.
        if (last > 0 && now - last < MIN_INTERVAL_MS && now >= last) {
            return;
        }
        if (!activity.onScreen()) {
            checkHidden(activity, free, urgent);
            return;
        }
        if (!idle && !urgent) {
            // Tight, but somebody is using the panel and we are not desperate
            // yet. Wait for the clock; at one minute of idle it is never long.
            return;
        }
        restart(activity, free, urgent, TAG_IDLE);
    }

    /**
     * The background path. Nobody is looking at our screen by definition, so
     * there is no "wait for idle" here -- the only question is whether the
     * thing that took the panel is a door call.
     *
     * Two answers are accepted, and the grace period is the one that does the
     * work: past BG_GRACE_MS a call is not merely unlikely, PanelWatchdog has
     * already given up on the idea. The door station is asked on top of that
     * where it is configured, because it is the only thing on this network that
     * actually knows -- see DoorStation for why the panel itself cannot tell.
     */
    private static void checkHidden(MainActivity activity, long free,
            boolean urgent) {
        if (activity.hiddenMs() < BG_GRACE_MS) {
            return;
        }
        if (!DoorStation.configured(activity)) {
            restart(activity, free, urgent, TAG_BG);
            return;
        }
        if (asking) {
            return;
        }
        asking = true;
        new Thread(new DoorCheck(activity, free, urgent)).start();
    }

    /**
     * Asks the door station, off the main thread, and restarts unless it says a
     * call is up.
     *
     * **Note this treats a null the opposite way to PanelWatchdog.Poll, and on
     * purpose.** There, "cannot ask" must not become "no call", because getting
     * it wrong pulls the dashboard over a live conversation. Here we are
     * already twenty minutes past the last possible call and the cost of being
     * wrong is two seconds of black screen on a panel nobody is watching --
     * while the cost of refusing to act is the panel found dead in the morning,
     * which is the whole thing being fixed. A door station that is unplugged
     * must not be able to disable the memory guard.
     */
    private static final class DoorCheck implements Runnable {
        private final MainActivity activity;
        private final long free;
        private final boolean urgent;

        DoorCheck(MainActivity activity, long free, boolean urgent) {
            this.activity = activity;
            this.free = free;
            this.urgent = urgent;
        }

        public void run() {
            try {
                String status = DoorStation.status(activity);
                if (status != null && !DoorStation.IDLE.equals(status)) {
                    // ring or onCall: it really is a call. Leave it alone.
                    return;
                }
                // finish() is a main-thread call, and restart() ends with one.
                activity.runOnUiThread(new Restart(activity, free, urgent));
            } finally {
                asking = false;
            }
        }
    }

    /**
     * Hops the restart back onto the main thread -- and asks once more whether
     * we are still off screen.
     *
     * The question has to be asked again because up to four seconds of door
     * station timeout have passed since it was last answered, and the likeliest
     * thing to have happened in them is the call ending and PanelWatchdog
     * putting the dashboard back. Restarting then would black out a panel
     * somebody has just been handed, for no gain: the memory is still low, we
     * are visible again, and the ordinary foreground path picks it up on the
     * next beat under its own idle rule.
     */
    private static final class Restart implements Runnable {
        private final MainActivity activity;
        private final long free;
        private final boolean urgent;

        Restart(MainActivity activity, long free, boolean urgent) {
            this.activity = activity;
            this.free = free;
            this.urgent = urgent;
        }

        public void run() {
            if (activity.onScreen()) {
                return;
            }
            restart(activity, free, urgent, TAG_BG);
        }
    }

    /**
     * The exit is deliberate, so the run flag is closed first. Leaving it open
     * would file this as a death in Vitals and corrupt the very record that is
     * being used to decide whether any of this works -- the restarts have their
     * own counter for that reason.
     *
     * @param tag what kind of restart this was, for the settings line. "(bg)"
     *            is the one worth looking for: it is the only evidence that the
     *            v1.27 background path ever fires, and a night that shows one
     *            is a night the v1.26 guard would have slept through.
     */
    private static void restart(Activity activity, long free, boolean urgent,
            String tag) {
        SharedPreferences prefs = Prefs.of(activity);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(Prefs.KEY_RESTART_COUNT, prefs.getInt(Prefs.KEY_RESTART_COUNT, 0) + 1);
        if (TAG_BG.equals(tag)) {
            // The note below is overwritten by the next restart; this is not.
            // See Prefs.KEY_RESTART_BG_COUNT for the reading that asked for it.
            edit.putInt(Prefs.KEY_RESTART_BG_COUNT,
                    prefs.getInt(Prefs.KEY_RESTART_BG_COUNT, 0) + 1);
        }
        edit.putString(Prefs.KEY_RESTART_NOTE,
                new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date())
                        + "  free " + free + "M" + (urgent ? "  (urgent)" : "")
                        + tag);
        edit.putLong(Prefs.KEY_RESTART_AT, System.currentTimeMillis());
        edit.commit();

        Vitals.endRunCleanly(activity);
        PanelWatchdog.relaunchIn(activity, RELAUNCH_MS);
        activity.finish();
        System.exit(0);
    }

    /**
     * The settings-screen button. Same exit and same comeback as the automatic
     * path, with none of the gates -- its whole purpose is to let somebody
     * standing at the panel find out in five seconds whether the relaunch
     * works, rather than discovering it hours later as a dark screen.
     *
     * Not rate-limited either: a person pressing a button has already decided.
     */
    static void restartNow(Activity activity) {
        restart(activity, freeMb(activity), false, TAG_HAND);
    }

    /**
     * The background count is printed even when it is nought, and especially
     * then: "15x, 0 bg" is the finding that the panel has never once needed the
     * v1.27 path, which is a different statement from a screen that says
     * nothing about it at all.
     */
    static String restartText(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        int count = prefs.getInt(Prefs.KEY_RESTART_COUNT, 0);
        if (count == 0) {
            return "never";
        }
        return count + "x, " + prefs.getInt(Prefs.KEY_RESTART_BG_COUNT, 0) + " bg, last "
                + prefs.getString(Prefs.KEY_RESTART_NOTE, "?");
    }

    private static long freeMb(Context context) {
        try {
            ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return info.availMem / (1024L * 1024L);
        } catch (Exception e) {
            return -1;
        }
    }
}
