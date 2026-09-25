package io.github.ridanuae.hakiosk;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.FileReader;
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
 *   2c. **Then who *was* it?** -- v1.23. The next death answered
 *      2b: `app` held at 166M across all eight beats into the kill while ~870M
 *      went elsewhere and came back by morning. So the beat now also carries
 *      /proc/meminfo -- anon, cache, shmem, slab, zram -- because
 *      ActivityManager only offers one "free" number and cannot say what *kind*
 *      of memory left. See sysMemText() for how to read it.
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

        // Before anything is judged: was there an install between that run and
        // this one? An install kills the process with the run flag open and
        // looks exactly like a death from here. See Prefs.KEY_VERSION.
        int running = versionCode(context);
        int stored = prefs.getInt(Prefs.KEY_VERSION, -1);
        // A version we cannot read must not turn every launch into an install,
        // so an unreadable code falls back to the pre-v1.27 behaviour.
        boolean installed = running >= 0 && stored != running;

        if (prefs.getBoolean(Prefs.KEY_RUN_OPEN, false) && !installed) {
            // The previous run never closed its flag, and it was the same build
            // that opened it. Nothing reaches this point except a process that
            // was killed or crashed.
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

        if (installed) {
            // Recorded, and deliberately nowhere near the death keys: the last
            // real death and the beats that led into it are the evidence, and
            // an upgrade must never be able to scroll them away.
            edit.putString(Prefs.KEY_INSTALL_NOTE,
                    stamp() + "  v" + (stored < 0 ? "?" : String.valueOf(stored))
                            + "->v" + running);
        }
        if (running >= 0) {
            edit.putBoolean(Prefs.KEY_LAST_WAS_INSTALL, installed);
            edit.putInt(Prefs.KEY_VERSION, running);
        }

        edit.putBoolean(Prefs.KEY_RUN_OPEN, true);
        // Per-run, not lifetime: the question is always what this run has seen.
        edit.putInt(Prefs.KEY_TRIM_RUN, 0);
        edit.putInt(Prefs.KEY_TRIM_BG, 0);
        edit.remove(Prefs.KEY_HIDDEN_NOTE);
        edit.putString(Prefs.KEY_BEATS, "");
        edit.putString(Prefs.KEY_ALIVE_NOTE, beatNote(context, takeMemoryInfo()));
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
    static void beat(Context context, char state) {
        SharedPreferences prefs = Prefs.of(context);
        // One reading, used twice. Debug.getMemoryInfo() walks /proc/self/smaps
        // and is the most expensive thing on this path, so it is taken once and
        // handed to both writers rather than called by each.
        Debug.MemoryInfo snapshot = takeMemoryInfo();
        String note = beatNote(context, snapshot);
        // One commit for both, not two: this runs every minute and the whole
        // file is re-written each time.
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString(Prefs.KEY_ALIVE_NOTE, note);
        edit.putString(Prefs.KEY_BEATS,
                pushBeat(prefs.getString(Prefs.KEY_BEATS, ""),
                        shortBeat(context, snapshot, state)));
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
     * names the cause on its own.
     *
     * **These levels are not one scale, and v1.22 wrongly treated them as one.**
     * Keeping only the highest number looked right until two test panels came
     * back one morning reading `UI_HIDDEN(20)`, which says nothing about
     * memory at all -- it means the vendor app came to the front. Because 20 is
     * numerically above `RUNNING_CRITICAL`(15), it had silently masked any
     * pressure warning those panels saw all night. Three separate things:
     *
     *   RUNNING_MODERATE/LOW/CRITICAL (5/10/15)  pressure, while we are visible
     *   UI_HIDDEN (20)                           a state change, not pressure
     *   BACKGROUND/MODERATE/COMPLETE (40/60/80)  pressure, while we are hidden
     *
     * The two pressure families are kept apart as well: 15 while foreground is a
     * far worse sign than 40 while backgrounded, so a single maximum across both
     * would mislead in the other direction.
     */
    static void onTrim(Context context, int level) {
        SharedPreferences prefs = Prefs.of(context);
        String key;
        String noteKey;
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Record it once, for its timestamp only -- it is not a severity.
            if (prefs.getString(Prefs.KEY_HIDDEN_NOTE, null) != null) {
                return;
            }
            SharedPreferences.Editor first = prefs.edit();
            first.putString(Prefs.KEY_HIDDEN_NOTE, stamp());
            first.commit();
            return;
        } else if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            key = Prefs.KEY_TRIM_RUN;
            noteKey = Prefs.KEY_TRIM_RUN_NOTE;
        } else {
            key = Prefs.KEY_TRIM_BG;
            noteKey = Prefs.KEY_TRIM_BG_NOTE;
        }
        if (level <= prefs.getInt(key, 0)) {
            return;
        }
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(key, level);
        // The stamp alone. The level is already in its own key, and keeping it
        // in one place is what lets the death record below name a trim that
        // arrived after the last heartbeat.
        edit.putString(noteKey, stamp());
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
     * in the trim key and then reset to 0 by the next startRun without ever
     * being read. Now the death record takes it straight from the key.
     */
    private static String trimSuffix(SharedPreferences prefs) {
        StringBuilder out = new StringBuilder();
        int run = prefs.getInt(Prefs.KEY_TRIM_RUN, 0);
        if (run > 0) {
            out.append("  trim ").append(trimName(run))
                    .append(" at ").append(prefs.getString(Prefs.KEY_TRIM_RUN_NOTE, "?"));
        }
        int bg = prefs.getInt(Prefs.KEY_TRIM_BG, 0);
        if (bg > 0) {
            out.append("  bg ").append(trimName(bg))
                    .append(" at ").append(prefs.getString(Prefs.KEY_TRIM_BG_NOTE, "?"));
        }
        String hidden = prefs.getString(Prefs.KEY_HIDDEN_NOTE, null);
        if (hidden != null) {
            out.append("  hidden ").append(hidden);
        }
        return out.toString();
    }

    /**
     * This build's versionCode, or -1 when it cannot be read.
     *
     * getPackageInfo().versionCode is deprecated at API 28 in favour of
     * getLongVersionCode(), and is used anyway: minSdk here is 19, the value is
     * a small counter that will not overflow an int this decade, and the
     * replacement would cost a version gate for nothing. No import: naming
     * PackageInfo is the only thing that would need one.
     */
    private static int versionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
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

    /** Stamp, the system totals, our own breakdown, and /proc/meminfo. */
    private static String beatNote(Context context, Debug.MemoryInfo snapshot) {
        return stamp() + "  " + memoryText(context)
                + "  {" + appMemText(snapshot) + "}"
                + "  [" + sysMemText() + "]";
    }

    /**
     * Our own process, as Android itself accounts for it -- v1.24, and the
     * correction to v1.22.
     *
     * getTotalPss() was the wrong number to have trusted alone. Reinstalling on
     * one panel sent free memory from 376M to 1249M the moment the old process
     * died, while that process had been reporting `app 108M`. Something held ~870M that PSS never counted, and it
     * went away with our process, so it was ours.
     *
     * getMemoryStat() is the framework's own breakdown and covers the two
     * categories PSS leaves out:
     *
     *   graphics     rendering surfaces and gralloc buffers -- a WebView's real
     *                weight on a device that composites in hardware, and
     *                invisible to getTotalPss().
     *   total-swap   pages pushed out to zram. These panels have 977M of it and
     *                sit ~375M used even when healthy, so anything swapped had
     *                simply vanished from our accounting.
     *
     * API 23+, and these panels are 29. Older builds lose the line, not the app.
     */
    static String appMemText(Debug.MemoryInfo snapshot) {
        if (snapshot == null) {
            return "unreadable";
        }
        if (Build.VERSION.SDK_INT < 23) {
            return "pss " + (snapshot.getTotalPss() / 1024L) + "M (no breakdown below API 23)";
        }
        return "pss " + stat(snapshot, "summary.total-pss")
                + " java " + stat(snapshot, "summary.java-heap")
                + " native " + stat(snapshot, "summary.native-heap")
                + " gfx " + stat(snapshot, "summary.graphics")
                + " code " + stat(snapshot, "summary.code")
                + " other " + stat(snapshot, "summary.private-other")
                + " swap " + stat(snapshot, "summary.total-swap");
    }

    /** Live reading for the settings screen, which takes its own snapshot. */
    static String appMemText() {
        return appMemText(takeMemoryInfo());
    }

    /** null rather than a throw: a missing reading must not cost the heartbeat. */
    private static Debug.MemoryInfo takeMemoryInfo() {
        try {
            Debug.MemoryInfo info = new Debug.MemoryInfo();
            Debug.getMemoryInfo(info);
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /** One summary field, in MB. "?" when this Android does not report it. */
    private static String stat(Debug.MemoryInfo info, String name) {
        try {
            String kb = info.getMemoryStat(name);
            return kb == null ? "?" : (Long.parseLong(kb) / 1024L) + "M";
        } catch (Exception e) {
            return "?";
        }
    }

    /** Graphics memory in MB, for the short beat. -1 when unavailable. */
    private static long gfxMb(Debug.MemoryInfo info) {
        if (info == null || Build.VERSION.SDK_INT < 23) {
            return -1;
        }
        try {
            String kb = info.getMemoryStat("summary.graphics");
            return kb == null ? -1 : Long.parseLong(kb) / 1024L;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The same beat squeezed to "04:04 f270 a214", because eight of these have
     * to sit on a 1024px screen next to everything else. Only the two numbers
     * that move are kept: free memory, and how much of it is us.
     */
    private static String shortBeat(Context context, Debug.MemoryInfo snapshot,
            char state) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date())
                + " f" + freeMb(context)
                + " a" + (snapshot == null ? -1 : snapshot.getTotalPss() / 1024L)
                + " g" + gfxMb(snapshot)
                + " n" + anonMb()
                + " " + state;
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

    /**
     * The system's own breakdown, from /proc/meminfo -- v1.23, and the question
     * the second caught death forced.
     *
     * That night proved the app is not the eater: `a` sat at 166M, unmoved, for
     * all eight beats into the kill while ~870M was held by something else and
     * released again by morning. ActivityManager only reports a single "free"
     * figure, which cannot say *what kind* of memory went. /proc/meminfo can,
     * and it is world-readable -- no permission, no root, which on a panel with
     * no ADB is the whole reason this is reachable at all.
     *
     * How to read the result:
     *
     *   anon high      a process is holding it. Ours is measured separately as
     *                  `app`, so anon-minus-app is somebody else's.
     *   cache high     file page cache. Reclaimable -- Android should drop this
     *                  rather than kill us, so a kill against high cache means
     *                  the pressure was somewhere else.
     *   shmem high     ashmem/gralloc: the graphics and media buffer pool.
     *   slab high      kernel structures. On a video intercom, read: driver.
     *   swap near full zram exhausted, which would explain 47 minutes of
     *                  pressure rather than a sudden death.
     *
     * And the fifth answer, which is the one to watch for: if these add up to
     * far less than the memory that went missing, it is in ION/DMA-BUF -- the
     * hardware video buffers, which deliberately do not appear here. On this
     * device that means the camera pipeline, and the absence is the finding.
     */
    static String sysMemText() {
        // One pass, one allocation per line, on the same once-a-minute beat as
        // the PSS read. Nothing here may throw: /proc is a kernel interface and
        // a missing field on some vendor kernel must not cost us the heartbeat.
        long anon = -1, cached = -1, shmem = -1, slab = -1;
        long swapTotal = -1, swapFree = -1;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("AnonPages:")) {
                    anon = kbOf(line);
                } else if (line.startsWith("Cached:")) {
                    cached = kbOf(line);
                } else if (line.startsWith("Shmem:")) {
                    shmem = kbOf(line);
                } else if (line.startsWith("Slab:")) {
                    slab = kbOf(line);
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = kbOf(line);
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = kbOf(line);
                }
            }
        } catch (Exception e) {
            return "(unreadable)";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // Nothing useful to do, and the reading is already taken.
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("anon ").append(mbKb(anon))
                .append("  cache ").append(mbKb(cached))
                .append("  shmem ").append(mbKb(shmem))
                .append("  slab ").append(mbKb(slab));
        if (swapTotal > 0) {
            // Used, not free: "190/512M" reads as a gauge filling up.
            out.append("  swap ").append(mbKb(swapTotal - swapFree))
                    .append('/').append(mbKb(swapTotal));
        } else {
            out.append("  swap none");
        }
        return out.toString();
    }

    /** "AnonPages:      123456 kB" -> 123456. -1 if the line will not parse. */
    private static long kbOf(String line) {
        try {
            String[] parts = line.split("\\s+");
            return Long.parseLong(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String mbKb(long kb) {
        return kb < 0 ? "?" : (kb / 1024L) + "M";
    }

    /** System-wide AnonPages in MB, for the short beat. -1 if unreadable. */
    private static long anonMb() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("AnonPages:")) {
                    return kbOf(line) / 1024L;
                }
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // See sysMemText().
                }
            }
        }
        return -1;
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
        // The install goes first, because it is the answer to the question
        // somebody reading this line straight after an upgrade is actually
        // asking. What follows it is the last *real* death, which an install no
        // longer disturbs -- so a stale date there is the good news, not a bug.
        String prefix = prefs.getBoolean(Prefs.KEY_LAST_WAS_INSTALL, false)
                ? "installed " + prefs.getString(Prefs.KEY_INSTALL_NOTE, "?")
                        + ", before that "
                : "";
        int deaths = prefs.getInt(Prefs.KEY_DEATH_COUNT, 0);
        if (deaths == 0) {
            return prefix + "ended cleanly";
        }
        return prefix + deaths + "x died, last " + prefs.getString(
                Prefs.KEY_LAST_DEATH, "?");
    }

    static String trimText(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        String suffix = trimSuffix(prefs).trim();
        if (suffix.length() == 0) {
            return "none this run";
        }
        // trimSuffix is written to sit on the end of a death record, where it
        // needs its "trim " label; here it is already under "Pressure:".
        return suffix.startsWith("trim ") ? suffix.substring(5) : suffix;
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
