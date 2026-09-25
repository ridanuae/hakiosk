package io.github.ridanuae.hakiosk;

import android.content.Context;
import android.os.Handler;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tells Home Assistant when the big camera window is open on this panel.
 *
 * The problem it solves is somebody standing at a panel with a camera open by
 * hand, and an automation taking the screen away from them mid-look. Home
 * Assistant cannot see that window: browser_mod reports the page path, the
 * visibility, the size and touch activity, and its path is
 * `window.location.pathname`, which does not move when a dialog opens. Checked
 * against browser_mod v3.2.3.
 *
 * The panel already knows, though, and has since v1.11. DialogProbe asks the
 * page whether `ha-more-info-dialog` is open -- the same element browser_mod
 * itself reaches for to close one -- and the night clock has been riding on
 * that answer ever since. All that was missing was telling anyone.
 *
 * So this is a second pair of eyes on the same probe, on a slower beat, posting
 * the answer to a webhook whenever it changes. See docs/camera-window.yaml
 * for what receives it on the Home Assistant side; your own popup automations
 * then check that flag and leave an occupied panel alone.
 *
 * **Which panel this is.** browser_mod knows each panel by a browser id, and
 * that name comes from the `?BrowserID=` on the address each app is pointed at
 * -- so it is read back out of the configured URL rather than stored twice.
 * A panel whose URL carries no BrowserID sends its IP instead, which the HA
 * side will not match to a flag: it goes in the log and nothing else, which is
 * the right failure for a panel nobody has finished setting up.
 */
final class WindowWatch {

    /** Must match docs/camera-window.yaml. */
    private static final String WEBHOOK_ID = "hakiosk-panel-window";

    /**
     * How often the page is asked, while we are the screen.
     *
     * Five seconds, against the clock's two. The clock is deciding whether to
     * cover a live camera and has to be quick; this is deciding whether a
     * motion popup may take the screen, and five seconds of lag there costs at
     * worst one interrupted look. The probe is one `evaluateJavascript` call
     * returning a short string, but these panels have a memory problem (see
     * MemoryGuard), so the rate is the lowest one that still feels immediate.
     */
    private static final long PROBE_MS = 5000L;

    /**
     * Say it again even when nothing has changed, this often.
     *
     * The flag lives in Home Assistant and this app is not the only thing that
     * can lose its place: MemoryGuard restarts the process on purpose, a door
     * call backgrounds us, HA itself restarts. Without a heartbeat, a flag
     * stuck `on` would silently block every popup on that panel forever, and
     * the failure would look exactly like the automations being broken. The HA
     * side clears anything it has not heard about in twelve minutes, which is
     * two of these plus room for a slow morning.
     */
    private static final long REASSERT_MS = 5 * 60 * 1000L;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;

    private final Context context;
    private final Handler handler;
    private final Tick tick = new Tick(this);
    private final Seen seen = new Seen(this);

    /** Not final: a renderer crash replaces the WebView, and so the probe. */
    private DialogProbe probe;

    private boolean running;
    private volatile boolean sending;

    /** What HA was last told, and when. Null until the first answer. */
    private Boolean told;
    private long toldAt;

    WindowWatch(Context context, WebView web, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.probe = new DialogProbe(web, handler);
    }

    /** After a renderer crash, exactly as ScreenSleeper.rebind() does it. */
    void rebind(WebView web) {
        probe.cancel();
        probe = new DialogProbe(web, handler);
    }

    void start() {
        running = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /**
     * Off screen, so there is no window of ours for anybody to interrupt --
     * and HA is told so rather than left holding the last answer. A door call
     * that lasts an hour would otherwise keep every popup off this panel for
     * an hour, for a dialog nobody can see.
     */
    void stop() {
        running = false;
        handler.removeCallbacks(tick);
        probe.cancel();
        if (!Boolean.FALSE.equals(told)) {
            send(false);
        }
    }

    private void ask() {
        if (!running) {
            return;
        }
        probe.ask(seen);
        handler.postDelayed(tick, PROBE_MS);
    }

    /**
     * Only on a change, or once every REASSERT_MS. A post per five seconds
     * would be 17,000 requests a day per panel to say nothing at all.
     */
    private void onAnswer(boolean open) {
        long now = System.currentTimeMillis();
        boolean changed = told == null || told.booleanValue() != open;
        // now < toldAt guards a clock that has gone backwards -- see MemoryGuard.
        boolean stale = toldAt > 0 && now - toldAt > REASSERT_MS && now >= toldAt;
        if (changed || stale || toldAt == 0) {
            send(open);
        }
    }

    private void send(boolean open) {
        if (sending) {
            return;
        }
        sending = true;
        told = Boolean.valueOf(open);
        toldAt = System.currentTimeMillis();
        new Thread(new Post(this, open)).start();
    }

    /**
     * A failed post forgets what it claimed to have said, so the next answer
     * counts as a change and tries again. Nothing is retried on a timer: the
     * probe comes back in five seconds anyway, which is a better retry than
     * any this class could schedule.
     */
    private void onPostFailed() {
        told = null;
        toldAt = 0L;
    }

    /**
     * The browser id -- say `panel-1` -- off the address the panel is pointed at.
     * Matched case-insensitively, and `browser_id` is accepted beside
     * `BrowserID` because browser_mod has answered to both spellings.
     */
    static String browserId(Context context) {
        String url = Prefs.url(context);
        int mark = url.indexOf('?');
        if (mark >= 0) {
            String[] parts = url.substring(mark + 1).split("&");
            for (int i = 0; i < parts.length; i++) {
                int equals = parts[i].indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = parts[i].substring(0, equals).toLowerCase();
                if (key.equals("browserid") || key.equals("browser_id")) {
                    return parts[i].substring(equals + 1);
                }
            }
        }
        return SettingsActivity.ipAddress();
    }

    /**
     * Whatever host the dashboard is on is the host that gets told, so a panel
     * moved to another Home Assistant keeps telling the one it is showing.
     */
    static String webhookUrl(Context context) {
        String path = "/api/webhook/" + WEBHOOK_ID;
        try {
            URL panel = new URL(Prefs.url(context));
            return new URL(panel.getProtocol(), panel.getHost(), panel.getPort(),
                    path).toString();
        } catch (Exception e) {
            return Prefs.DEFAULT_URL.replaceAll("/+$", "") + path;
        }
    }

    /** One JSON POST. Returns the HTTP status. */
    static int post(String url, String body) throws Exception {
        HttpURLConnection connection = null;
        OutputStream out = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            out = connection.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.flush();
            return connection.getResponseCode();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                    // Nothing useful to do about a failed close.
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static final class Tick implements Runnable {
        private final WindowWatch watch;

        Tick(WindowWatch watch) {
            this.watch = watch;
        }

        public void run() {
            watch.ask();
        }
    }

    private static final class Seen implements DialogProbe.Answer {
        private final WindowWatch watch;

        Seen(WindowWatch watch) {
            this.watch = watch;
        }

        public void onDialogOpen(boolean open) {
            watch.onAnswer(open);
        }
    }

    /** Never on the main thread -- that is a NetworkOnMainThreadException. */
    private static final class Post implements Runnable {
        private final WindowWatch watch;
        private final boolean open;

        Post(WindowWatch watch, boolean open) {
            this.watch = watch;
            this.open = open;
        }

        public void run() {
            try {
                JSONObject json = new JSONObject();
                json.put("browser", browserId(watch.context));
                json.put("ip", SettingsActivity.ipAddress());
                json.put("open", open);
                int status = post(webhookUrl(watch.context), json.toString());
                // 200 says HA heard us and nothing more: Home Assistant answers
                // 200 even to a webhook id it has never heard of, so this can
                // never prove the flag was set. Anything else is a real refusal.
                if (status < 200 || status >= 300) {
                    watch.onPostFailed();
                }
            } catch (Exception e) {
                watch.onPostFailed();
            } finally {
                watch.sending = false;
            }
        }
    }
}
