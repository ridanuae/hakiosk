package io.github.ridanuae.hakiosk;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Puts the panel back on screen after something else has taken it.
 *
 * On a door call the Hikvision's own intercom app comes to the front, and when
 * the call ends it returns to the vendor launcher rather than to whatever it
 * covered -- so the panel would sit there until someone tapped the app again.
 *
 * MainActivity arms a one-shot alarm every time it stops and cancels it every
 * time it starts. The alarm is held by the system, not by us, so it still
 * fires if the device killed our process to make room for the call.
 *
 * Do not add a "is the screen on?" check here. v1.7's lifecycle notes had one:
 * while the screen was dark it re-armed on a non-waking alarm and started
 * nothing. It was written for the era when ScreenSleeper switched the screen
 * off itself, and v1.7 ended that era -- the clock keeps FLAG_KEEP_SCREEN_ON on
 * throughout and never goes dark. What was left was a branch that only fired in
 * the one case it got wrong: FLAG_KEEP_SCREEN_ON belongs to *our* window, so
 * once the intercom or the vendor launcher is in front the device's own display
 * timeout blanks the screen, and the check then parked here for good -- it was
 * the only path not bounded by giveUpAt, on an alarm Doze defers all night. The
 * panel was still on the vendor launcher every morning. Starting the activity
 * against a dark screen is the point, not a waste: it does not light anything
 * up, it just means the dashboard is already there when somebody touches it.
 *
 * Named nested classes only, no anonymous ones -- see MainActivity.
 */
public class PanelWatchdog extends BroadcastReceiver {

    /** Our own alarm, and the only thing this receiver answers to. */
    private static final String ACTION_RETURN = "io.github.ridanuae.hakiosk.RETURN_TO_PANEL";

    /**
     * MemoryGuard's way back after it exits the process. Deliberately its own
     * action rather than a short ACTION_RETURN: that path checks the watchdog
     * setting and the door station, and neither has anything to do with coming
     * back from a restart we chose. A panel that stayed dark because the user
     * had turned the door-call watchdog off would be a bad way to learn this.
     */
    private static final String ACTION_RELAUNCH = "io.github.ridanuae.hakiosk.RELAUNCH";

    /**
     * How long the panel stays off screen before we take it back.
     *
     * A plain timer, and deliberately the only rule left. v1.2 to v1.16 all
     * tried to detect the end of the call instead and come back the moment it
     * finished; every one of them was wrong, because this intercom is invisible
     * to Android. Measured on a live panel, with a call over and nothing
     * playing: `players=1 rec=0 musicActive=true mode=0`. The mode never leaves
     * MODE_NORMAL, no playback configuration is registered, and isMusicActive()
     * is stuck true for good -- so audioBusy() answered "a call is still up"
     * forever and the panel sat backgrounded until the ten-minute giveup. The
     * user's own memory of the version that worked is the answer: it had no
     * detection at all, just a timer that happened to outlast a call.
     *
     * Four minutes: the bell rings for about 32s, and a long call runs about two
     * minutes of talking after answering, so ~2.5 min is the realistic worst
     * case and this leaves margin. Tune it to your own door. Too short and we
     * would cover
     * a call that is still running, which is the bug that started all of this;
     * too long and the panel sits on the vendor launcher after a quick call.
     *
     * Not private since v1.27: MemoryGuard derives its own background grace
     * period from this and POLL_GIVE_UP_MS rather than guessing a second
     * "how long can a call last?" constant that could drift away from these.
     */
    static final long RETURN_AFTER_MS = 4 * 60 * 1000L;

    /** Deadline for the fallback timer, carried on the alarm. */
    private static final String EXTRA_DEADLINE = "deadline";

    /** How soon to take the first look when the door station can be asked. */
    private static final long POLL_FIRST_MS = 4000L;

    /** How often to ask again while the door station says a call is up. */
    private static final long POLL_MS = 3000L;

    /**
     * Absolute cap on asking. If the station somehow sits in "onCall" for good
     * -- wedged, or a handset left off the hook -- we stop believing it and come
     * back anyway, rather than repeat the v1.16 bug of waiting for a condition
     * that never clears.
     *
     * Not private since v1.27 -- see RETURN_AFTER_MS.
     */
    static final long POLL_GIVE_UP_MS = 15 * 60 * 1000L;

    /**
     * Set when the app itself is about to open another screen -- Settings,
     * Android's device-admin Activate page, the package installer. Android
     * starts the new screen before it stops the old one, so without this the
     * watchdog would arm and then pull that screen away a few seconds later.
     */
    private static boolean suppressed;

    static void suppressNext() {
        suppressed = true;
    }

    /** Called when the panel leaves the screen. */
    static void arm(Context context) {
        boolean skip = suppressed;
        suppressed = false;
        if (skip || !Prefs.watchdogEnabled(context)) {
            return;
        }
        // The deadline is the v1.17 behaviour and the floor under everything
        // below: whatever the door station does or does not say, the panel is
        // back by then. Polling can only make the return *earlier*.
        long deadline = SystemClock.elapsedRealtime() + RETURN_AFTER_MS;
        schedule(context,
                DoorStation.configured(context) ? POLL_FIRST_MS : RETURN_AFTER_MS,
                deadline);
    }

    /** Called when the panel is back on screen, by itself or by us. */
    static void cancel(Context context) {
        suppressed = false;
        PendingIntent pending = PendingIntent.getBroadcast(context, 0,
                returnIntent(context, 0L),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) {
            alarms(context).cancel(pending);
            pending.cancel();
        }
    }

    /**
     * Two alarms, not one: the first is the restart, the second is insurance.
     * If the 3-second alarm is missed or refused, the panel would sit dark until
     * somebody touched it -- worse than the death this is preventing. The backup
     * lands on an app that is normally already running, where REORDER_TO_FRONT
     * costs nothing.
     */
    static void relaunchIn(Context context, long delayMs) {
        // Three alarms by two different mechanisms, because this one fires into
        // a process that no longer exists and there is no way to test the
        // failure from here -- the panel has no ADB and no logcat.
        //
        // The broadcast route is the one already proven on this hardware: it is
        // how the panel comes back after a door call. But that fires while our
        // process is still alive, and Android 10 restricts starting an activity
        // from the background, so a cold start may be refused where a warm one
        // was not. The middle alarm is therefore a PendingIntent.getActivity()
        // -- AlarmManager launching the activity itself, a different path
        // through the same restriction.
        //
        // If all three are refused the panel sits dark until somebody touches
        // it, which is exactly where it is today when Android kills us. The
        // downside is bounded by the status quo; the upside is it stops dying.
        scheduleRelaunch(context, delayMs, 1);
        scheduleActivityRelaunch(context, delayMs + 2000L);
        scheduleRelaunch(context, delayMs + 60000L, 2);
    }

    private static void scheduleActivityRelaunch(Context context, long delayMs) {
        Intent front = new Intent(context, MainActivity.class);
        front.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pending = PendingIntent.getActivity(context, 3, front,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms(context).setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pending);
    }

    private static void scheduleRelaunch(Context context, long delayMs, int code) {
        Intent intent = new Intent(context, PanelWatchdog.class);
        intent.setAction(ACTION_RELAUNCH);
        PendingIntent pending = PendingIntent.getBroadcast(context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms(context).setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pending);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_RELAUNCH.equals(intent.getAction())) {
            toFront(context);
            return;
        }
        if (!ACTION_RETURN.equals(intent.getAction())) {
            return;
        }

        if (!Prefs.watchdogEnabled(context)) {
            return;
        }

        long deadline = intent.getLongExtra(EXTRA_DEADLINE, 0L);
        long now = SystemClock.elapsedRealtime();

        // Past the fallback deadline, or nothing to ask: behave exactly as
        // v1.17 did and come back. Nothing below can delay this.
        if (now >= deadline || !DoorStation.configured(context)) {
            toFront(context);
            return;
        }

        // Ask the door station off the main thread -- onReceive runs on it, and
        // a network call there throws NetworkOnMainThreadException.
        final PendingResult async = goAsync();
        final Context app = context.getApplicationContext();
        final long fallbackAt = deadline;
        new Thread(new Poll(app, async, fallbackAt)).start();
    }

    /** Runs the one HTTP question and decides what happens next. */
    private static final class Poll implements Runnable {
        private final Context context;
        private final PendingResult async;
        private final long deadline;

        Poll(Context context, PendingResult async, long deadline) {
            this.context = context;
            this.async = async;
            this.deadline = deadline;
        }

        public void run() {
            try {
                String status = DoorStation.status(context);
                long now = SystemClock.elapsedRealtime();

                if (status == null) {
                    // Could not ask. Do not guess -- wait out the timer, which
                    // is the behaviour that is already proved to work.
                    schedule(context, Math.max(0L, deadline - now), deadline);
                    return;
                }
                if (DoorStation.IDLE.equals(status)) {
                    // Authoritative: the call is over. This is the whole point.
                    toFront(context);
                    return;
                }
                // ring or onCall: a call really is up, so stay away -- even past
                // the fallback deadline, because now we actually know. The cap
                // stops that being unbounded.
                if (now >= deadline + POLL_GIVE_UP_MS) {
                    toFront(context);
                    return;
                }
                schedule(context, POLL_MS, deadline);
            } finally {
                async.finish();
            }
        }
    }

    private static void toFront(Context context) {
        Intent front = new Intent(context, MainActivity.class);
        front.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(front);
    }

    private static void schedule(Context context, long delayMs, long deadline) {
        PendingIntent pending = PendingIntent.getBroadcast(context, 0,
                returnIntent(context, deadline),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // setExact, not set(): since API 19 a plain set() is batched with other
        // alarms and can drift by minutes, which is the whole delay we're
        // trying to avoid. Exact alarms need no permission below targetSdk 31.
        alarms(context).setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pending);
    }

    /** Extras are ignored when matching a PendingIntent, so cancel() still finds this. */
    private static Intent returnIntent(Context context, long deadline) {
        Intent intent = new Intent(context, PanelWatchdog.class);
        intent.setAction(ACTION_RETURN);
        intent.putExtra(EXTRA_DEADLINE, deadline);
        return intent;
    }

    private static AlarmManager alarms(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }
}
