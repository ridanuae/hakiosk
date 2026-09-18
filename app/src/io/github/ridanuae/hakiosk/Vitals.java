package io.github.ridanuae.hakiosk;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Debug;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The app's record of its own death.
 *
 * Written after a night when the panel's process died at 04:02 and every source
 * outside the app was exhausted without naming a cause. Home Assistant's history
 * had the minute the panel's entities went unavailable; the network gear showed
 * the wired link unbroken straight *through* the death -- no re-association, no
 * re-DHCP, no reboot. Both agreed on what it was not. Neither could say what it
 * was, because the only thing that knew was the process that died.
 *
 * If you are debugging the same thing, that is the lesson: outside evidence can
 * only ever rule things out. Something inside the process has to survive it.
 *
 * `Renderer:` already covers one way to die and cleared itself that night --
 * `OK (no crashes)`, from a counter that lives in SharedPreferences and so
 * survives the death it records. This is the same idea widened to the rest:
 *
 *   1. **Did the last run end, or stop?** A heartbeat once a minute, and a flag
 *      saying a run is open. A run that ends deliberately closes the flag; a
 *      process that is killed cannot, so finding the flag still open at startup
 *      *is* the death certificate, and the last heartbeat is its time of death.
 *   2. **Was it memory?** The heartbeat carries the free-memory reading with it,
 *      so the note left behind says what memory looked like in the last minute
 *      the app was alive -- which is the reading that matters and the one no
 *      later inspection can recover. onTrimMemory is recorded on top: Android
 *      warns before it kills, and a TRIM_MEMORY_COMPLETE at 04:01 would settle
 *      it outright.
 *   2b. **Was it *us*?** -- v1.22, and the question the first caught death left
 *      open. That note said `free 270M` against a normal 1200M, so ~900M
 *      went somewhere, and `heap 2M/128M` could not say where: the Java heap is
 *      not where a WebView keeps its weight. The beat now carries this process's
 *      total PSS beside the free reading, and the two together separate the only
 *      two answers there are -- our own size rising into the kill, or holding
 *      flat while the room empties around us. It also keeps the last few beats
 *      rather than one, because a cliff and a slide have different culprits.
 *   3. **Was it a crash?** An uncaught exception on any thread currently leaves
 *      nothing at all behind. Caught here, named, and chained on to the handler
 *      that was there before so the app still dies the way Android expects.
 *   4. **Or did the process never die?** MainActivity has no `uiMode` in its
 *      configChanges, so a vendor day/night switch would *recreate the activity*
 *      inside a living process -- a white flash that looks exactly like a death
 *      from across the room but leaves the run flag untouched. Counted
 *      separately, which is what tells the two apart.
 *
 * All of it reads back on Settings -> INFO. These panels have no ADB and no
 * logcat, so a line on that screen is the only place a finding can go.
 *
 * Named nested classes only, no anonymous ones -- see MainActivity.
 */
final class Vitals {

    /**
     * How often the heartbeat writes. This is the resolution of the time of
     * death and nothing more: the app died somewhere in the minute *after* the
     * note it left. Faster would narrow that, at a full SharedPreferences
     * rewrite each time -- see the commit() note below.
     */
    static final long BEAT_MS = 60000L;

    /**
     * How many heartbeats to keep. Eight minutes of history is enough to see the
     * shape of a fall without turning the settings screen into a logfile, and
     * every one of them is re-written on each beat -- see the commit() note.
     */
    private static final int BEATS_KEPT = 8;

    /** Separator for the beat list. Never appears inside a beat. */
    private static final String BEAT_SEP = " | ";

    /**
     * True once this *process* has started a run. Static, so it dies with the
     * process and survives an activity that is merely rebuilt -- which is the
     * whole distinction in point 4 above. Without it, a recreation would read
     * its own still-open run flag and report a death that never happened.
     */
    private static boolean runStarted;

    /** Guards against stacking a second handler on top of our own. */
    private static boolean catcherInstalled;

    private Vitals() {
    }

    /**
     * Called first thing in onCreate, before the WebView exists, so that a
     * failure while building the page is still caught.
     */
    static void startRun(Context context) {
        if (runStarted) {
            // Same process, second onCreate: the activity was rebuilt under us.
            recordRecreate(context);
            return;
        }
        runStarted = true;

        SharedPreferences prefs = Prefs.of(context);
        SharedPreferences.Editor edit = prefs.edit();

        if (prefs.getBoolean(Prefs.KEY_RUN_OPEN, false)) {
            // The previous run never closed its flag. Nothing reaches this
            // point except a process that was killed or crashed.
            String note = prefs.getString(Prefs.KEY_ALIVE_NOTE, null);
            edit.putString(Prefs.KEY_LAST_DEATH,
                    (note == null ? "died before its first heartbeat" : note)
                            + trimSuffix(prefs));
            edit.putInt(Prefs.KEY_DEATH_COUNT,
                    prefs.getInt(Prefs.KEY_DEATH_COUNT, 0) + 1);
            // Take the dead run's beats before this run starts overwriting them.
            edit.putString(Prefs.KEY_DEATH_BEATS,
                    prefs.getString(Prefs.KEY_BEATS, ""));
        }

        edit.putBoolean(Prefs.KEY_RUN_OPEN, true);
        // Per-run, not lifetime: the question is always what this run has seen.
        edit.putInt(Prefs.KEY_TRIM_WORST, 0);
        edit.putString(Prefs.KEY_BEATS, "");
        edit.putString(Prefs.KEY_ALIVE_NOTE, beatNote(context));
        edit.commit();

        installCatcher(context);
    }

    /**
     * The deliberate way out -- the header's close button, which calls finish().
     * Closing the flag here is what makes leaving on purpose distinguishable
     * from being killed.
     */
    static void endRunCleanly(Context context) {
        Prefs.putBoolean(context, Prefs.KEY_RUN_OPEN, false);
    }

    /**
     * One heartbeat. commit() rather than apply() on purpose: apply() hands the
     * write to a background thread, and the write this class exists to keep is
     * precisely the one taken by a kill that gives no warning. A synchronous
     * write of a small file once a minute is the cheaper side of that trade.
     */
    static void beat(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        String note = beatNote(context);
        // One commit for both, not two: this runs every minute and the whole
        // file is re-written each time.
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString(Prefs.KEY_ALIVE_NOTE, note);
        edit.putString(Prefs.KEY_BEATS,
                pushBeat(prefs.getString(Prefs.KEY_BEATS, ""), shortBeat(context)));
        edit.commit();
    }

    /**
     * Newest first, oldest dropped past BEATS_KEPT. Reading order matters on the
     * settings screen: the beat that interests anybody is the last one before
     * the death, and it should not be at the end of a wrapped line.
     */
    private static String pushBeat(String existing, String beat) {
        if (existing == null || existing.length() == 0) {
            return beat;
        }
        String[] kept = existing.split("\\|");
        StringBuilder out = new StringBuilder(beat);
        for (int i = 0; i < kept.length && i < BEATS_KEPT - 1; i++) {
            out.append(BEAT_SEP).append(kept[i].trim());
        }
        return out.toString();
    }

    /**
     * Android's warning shot. It calls this as memory tightens and before it
     * starts killing, so a high level recorded here a minute before a death
     * names the cause on its own. Only the worst level of the run is kept --
     * the low ones arrive constantly and are not worth a write each.
     */
    static void onTrim(Context context, int level) {
        SharedPreferences prefs = Prefs.of(context);
        if (level <= prefs.getInt(Prefs.KEY_TRIM_WORST, 0)) {
            return;
        }
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(Prefs.KEY_TRIM_WORST, level);
        // The stamp alone. The level is already in KEY_TRIM_WORST, and keeping
        // it in one place is what lets the death record below name a trim that
        // arrived after the last heartbeat.
        edit.putString(Prefs.KEY_TRIM_NOTE, stamp());
        edit.commit();
    }

    /**
     * The trim to hang on the end of a death record, read from the trim keys
     * rather than from the note the heartbeat left.
     *
     * This is the fix for the hole v1.21 had. The heartbeat embedded the trim it
     * knew about at the time, but onTrim() writes the instant the warning lands
     * and the note is only re-written on the next beat -- so a warning in the
     * final seconds of a run, which is exactly the interesting one, was recorded
     * in KEY_TRIM_WORST and then reset to 0 by the next startRun without ever
     * being read. Now the death record takes it straight from the key.
     */
    private static String trimSuffix(SharedPreferences prefs) {
        int worst = prefs.getInt(Prefs.KEY_TRIM_WORST, 0);
        if (worst == 0) {
            return "";
        }
        return "  trim " + trimName(worst)
                + " at " + prefs.getString(Prefs.KEY_TRIM_NOTE, "?");
    }

    /** An activity rebuilt inside a process that never died. See point 4. */
    private static void recordRecreate(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(Prefs.KEY_RECREATE_COUNT,
                prefs.getInt(Prefs.KEY_RECREATE_COUNT, 0) + 1);
        edit.putString(Prefs.KEY_RECREATE_NOTE, stamp());
        edit.commit();
    }

    private static void installCatcher(Context context) {
        if (catcherInstalled) {
            return;
        }
        catcherInstalled = true;
        Thread.setDefaultUncaughtExceptionHandler(new Catcher(
                context.getApplicationContext(),
                Thread.getDefaultUncaughtExceptionHandler()));
    }

    /** "Sep 18 04:04  free 270M/1954M lowram  app 214M  heap 2M/128M". */
    private static String beatNote(Context context) {
        return stamp() + "  " + memoryText(context);
    }

    /**
     * The same beat squeezed to "04:04 f270 a214", because eight of these have
     * to sit on a 1024px screen next to everything else. Only the two numbers
     * that move are kept: free memory, and how much of it is us.
     */
    private static String shortBeat(Context context) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date())
                + " f" + freeMb(context) + " a" + appMb();
    }

    /**
     * Free system memory first, because that is the number that decides whether
     * the low-memory killer comes for us; the Java heap is second because a
     * WebView's real weight is native and never shows up in it. Both are worth
     * having -- they fail in different directions.
     */
    static String memoryText(Context context) {
        StringBuilder out = new StringBuilder();
        try {
            ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            out.append("free ").append(mb(info.availMem))
                    .append('/').append(mb(info.totalMem));
            if (info.lowMemory) {
                out.append(" LOW");
            }
            if (manager.isLowRamDevice()) {
                out.append(" lowram");
            }
        } catch (Exception e) {
            out.append("free ?");
        }
        // Since v1.22, and the number the whole rebuild is for. Held next to the
        // free reading on purpose: neither one means much alone.
        out.append("  app ").append(appMb()).append('M');
        Runtime runtime = Runtime.getRuntime();
        out.append("  heap ")
                .append(mb(runtime.totalMemory() - runtime.freeMemory()))
                .append('/').append(mb(runtime.maxMemory()));
        return out.toString();
    }

    /**
     * This process's total PSS, in MB -- every page it is holding, native and
     * Java together, with shared pages charged proportionally. This is the
     * reading the Java heap cannot give: a WebView keeps almost everything it
     * has outside the heap, so `heap 2M/128M` sits unchanged while the process
     * grows by hundreds of megabytes.
     *
     * Debug.getMemoryInfo() walks /proc/self/smaps, which is not free -- hence
     * once a minute on the heartbeat, and not on any path that repeats faster.
     */
    private static long appMb() {
        try {
            Debug.MemoryInfo info = new Debug.MemoryInfo();
            Debug.getMemoryInfo(info);
            return info.getTotalPss() / 1024L;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Free system memory in MB, for the short beat. -1 if it cannot be read. */
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

    /**
     * The line the whole class is for. "Ended cleanly" is the answer we want;
     * anything else is a time of death with the memory reading from the minute
     * before it, which is the one measurement that cannot be taken afterwards.
     */
    static String lastRunText(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        int deaths = prefs.getInt(Prefs.KEY_DEATH_COUNT, 0);
        if (deaths == 0) {
            return "ended cleanly";
        }
        return deaths + "x died, last " + prefs.getString(
                Prefs.KEY_LAST_DEATH, "?");
    }

    static String trimText(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        int worst = prefs.getInt(Prefs.KEY_TRIM_WORST, 0);
        if (worst == 0) {
            return "none this run";
        }
        return trimName(worst) + ", first at "
                + prefs.getString(Prefs.KEY_TRIM_NOTE, "?");
    }

    /**
     * The minutes leading into the last death, newest first. Empty until there
     * has been one, and worth nothing on a panel that has never died -- which is
     * why the settings screen leaves the line out entirely in that case.
     *
     * What to look for: `a` climbing beat after beat while `f` falls by the same
     * amount is this app eating the panel. `f` falling while `a` holds still is
     * something else eating it, and no change we make here will help.
     */
    static String deathBeatsText(Context context) {
        String beats = Prefs.of(context).getString(Prefs.KEY_DEATH_BEATS, "");
        return beats == null ? "" : beats;
    }

    static String crashText(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        int count = prefs.getInt(Prefs.KEY_CRASH_COUNT, 0);
        if (count == 0) {
            return "none";
        }
        return count + "x, last " + prefs.getString(Prefs.KEY_CRASH, "?");
    }

    /**
     * Non-zero here with "ended cleanly" above is suspect 4 caught in the act:
     * the screen went white and the app never died at all.
     */
    static String recreateText(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        int count = prefs.getInt(Prefs.KEY_RECREATE_COUNT, 0);
        if (count == 0) {
            return "never";
        }
        return count + "x, last " + prefs.getString(Prefs.KEY_RECREATE_NOTE, "?");
    }

    private static String mb(long bytes) {
        return (bytes / (1024L * 1024L)) + "M";
    }

    private static String stamp() {
        return new SimpleDateFormat("MMM d HH:mm", Locale.US)
                .format(new Date());
    }

    /**
     * Numbers, not just names: the constants are the thing actually compared,
     * and COMPLETE is 80 whatever a future Android decides to call it.
     */
    private static String trimName(int level) {
        String name;
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            name = "COMPLETE";
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            name = "MODERATE";
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            name = "BACKGROUND";
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            name = "UI_HIDDEN";
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            name = "RUN_CRITICAL";
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            name = "RUN_LOW";
        } else {
            name = "RUN_MODERATE";
        }
        return name + "(" + level + ")";
    }

    /**
     * Records the throwable, then hands it on. Chaining matters: the handler
     * that was already there is the one that shows Android's own dialog and
     * ends the process, and swallowing it would leave the app wedged and alive
     * instead of dead -- a worse failure than the one being measured.
     *
     * Runs on whichever thread is dying, which is why the write is a plain
     * commit() and nothing here touches the UI.
     */
    private static final class Catcher implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler next;

        Catcher(Context context, Thread.UncaughtExceptionHandler next) {
            this.context = context;
            this.next = next;
        }

        public void uncaughtException(Thread thread, Throwable error) {
            try {
                SharedPreferences prefs = Prefs.of(context);
                SharedPreferences.Editor edit = prefs.edit();
                edit.putInt(Prefs.KEY_CRASH_COUNT,
                        prefs.getInt(Prefs.KEY_CRASH_COUNT, 0) + 1);
                edit.putString(Prefs.KEY_CRASH,
                        stamp() + "  " + describe(error)
                                + " on " + thread.getName());
                edit.commit();
            } catch (Throwable ignored) {
                // A handler that throws loses the original exception as well.
            }
            if (next != null) {
                next.uncaughtException(thread, error);
            }
        }

        /**
         * Our own frame, not the top one: the top of a stack is usually deep
         * inside the framework and says nothing about which line of ours put it
         * there. Falls back to the top frame when we are not on the stack.
         */
        private static String describe(Throwable error) {
            String where = "?";
            StackTraceElement[] frames = error.getStackTrace();
            if (frames != null && frames.length > 0) {
                where = frames[0].toString();
                for (int i = 0; i < frames.length; i++) {
                    if (frames[i].getClassName().startsWith("io.github.ridanuae.hakiosk")) {
                        where = frames[i].getFileName()
                                + ":" + frames[i].getLineNumber();
                        break;
                    }
                }
            }
            String message = error.getMessage();
            return error.getClass().getSimpleName()
                    + (message == null ? "" : ": " + message)
                    + " @ " + where;
        }
    }
}
