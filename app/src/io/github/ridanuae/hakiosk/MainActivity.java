package io.github.ridanuae.hakiosk;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Full-screen WebView pointed at the panel page, with a small bar of its own
 * across the bottom (PanelHeader) for closing the app and opening Settings.
 *
 * Kept deliberately small: no support libraries, no native code, nothing newer
 * than API 19, so the same APK installs on whatever Android the Hikvision runs.
 *
 * Note the named nested classes below where anonymous ones would read more
 * naturally -- d8 8.2.2 (build-tools 34) hits an internal NPE on anonymous
 * classes emitted by javac 21, so this file deliberately has none.
 */
public class MainActivity extends Activity implements PanelHeader.Listener {

    /** How long to wait before retrying after a load failure. */
    private static final long RETRY_DELAY_MS = 5000L;

    private static boolean pendingReload;
    private static boolean pendingClearCache;

    private WebView web;
    private FrameLayout root;
    private PanelHeader header;
    private ClockScreen clock;
    private ScreenSleeper sleeper;
    private Handler handler;
    private boolean retryScheduled;
    private String loadedUrl;

    /** True from the touch that dismisses the clock until that gesture ends. */
    private boolean swallowingGesture;

    /** One instance, re-posted: see startHeartbeat(). */
    private final BeatTask beatTask = new BeatTask(this);

    /** Settings asks for these; the WebView they act on lives here. */
    static void requestReload(boolean clearCache) {
        pendingReload = true;
        pendingClearCache = pendingClearCache || clearCache;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // First, and before the WebView exists: this is what reads the previous
        // run's death certificate, and what arms the handler that would catch a
        // failure while the page is still being built. See Vitals.
        Vitals.startRun(this);

        handler = new Handler();

        // v1.7 stopped turning the screen off, so the rights that did it are
        // dead weight -- and Android refuses to uninstall the app while they
        // are held. Hand them back on the way past. No-op once they are gone.
        Admin.deactivate(this);

        web = newWebView();

        root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        header = new PanelHeader(this, this);
        root.addView(header, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(this, PanelHeader.HEIGHT_DP), Gravity.BOTTOM));

        // Last, so it covers the bar as well as the page.
        clock = new ClockScreen(this);
        root.addView(clock, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        sleeper = new ScreenSleeper(this, clock, web);

        setContentView(root);
        web.loadUrl(freshUrl());
        startHeartbeat();
    }

    /**
     * The once-a-minute note that dates the next death. Re-armed rather than
     * simply started, because onRenderGone() clears every callback on this
     * handler and would otherwise stop the heartbeat for good -- silently, and
     * exactly on the kind of night this is meant to measure.
     */
    private void startHeartbeat() {
        handler.removeCallbacks(beatTask);
        handler.postDelayed(beatTask, Vitals.BEAT_MS);
    }

    void onHeartbeat() {
        Vitals.beat(this);
        startHeartbeat();
    }

    /**
     * Builds the panel WebView. Called once at startup, and again by
     * onRenderGone() -- a WebView whose renderer has died cannot be revived,
     * only replaced, so this has to be repeatable.
     */
    private WebView newWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // A wall panel has no user to tap "play", and HA camera cards autoplay.
        settings.setMediaPlaybackRequiresUserGesture(false);
        // The crash-handling subclass only where the callback exists; see it.
        view.setWebViewClient(Build.VERSION.SDK_INT >= 26
                ? new PanelWebViewClient26(this)
                : new PanelWebViewClient(this));

        // The device's own web port is closed, so this is the only way to learn
        // what WebView it runs -- Settings shows it, no log scraping needed.
        Prefs.putString(this, Prefs.KEY_UA, settings.getUserAgentString());
        return view;
    }

    /**
     * The WebView's render process has died. Replace it and carry on.
     *
     * This is the single most damaging thing that can happen to the panel, and
     * until v1.20 it was not handled at all. Returning false from
     * onRenderProcessGone -- or not overriding it, which is the same thing --
     * makes Android kill the whole app process. That matters far more here than
     * the lost page, because **process death is not a lifecycle event**:
     * onStop() never runs, so PanelWatchdog.arm() never runs, so no alarm is
     * ever scheduled and nothing is coming to bring the panel back. Every other
     * way this app can lose the screen is bounded by RETURN_AFTER_MS; this one
     * was unbounded and ended only when somebody walked up and touched the
     * panel.
     *
     * Measured on a live panel: Home Assistant pushed a camera to it, the
     * renderer died 996ms later, and the panel was still dead 42 minutes
     * afterwards with the vendor intercom's screensaver showing through the hole
     * where this app had been. A camera stream is the usual trigger -- it is by
     * far the heaviest thing these panels are ever asked to render.
     *
     * The count is kept because the interesting question is not whether one
     * crash happened but whether they are routine -- and on a panel with no ADB
     * and no logcat, the settings screen is the only place to read it.
     */
    void onRenderGone(boolean didCrash) {
        int count = Prefs.of(this).getInt(Prefs.KEY_RENDER_GONE_COUNT, 0) + 1;
        Prefs.putInt(this, Prefs.KEY_RENDER_GONE_COUNT, count);
        Prefs.putString(this, Prefs.KEY_RENDER_GONE,
                new java.text.SimpleDateFormat("MMM d HH:mm:ss")
                        .format(new java.util.Date())
                        + (didCrash ? " (crashed)" : " (killed for memory)"));

        // Nothing may touch the dead view again. The retry timer would reload
        // it and the clock's probe would run JavaScript in it, both of which
        // throw once it is destroyed.
        handler.removeCallbacksAndMessages(null);
        retryScheduled = false;

        WebView dead = web;
        root.removeView(dead);
        dead.destroy();

        web = newWebView();
        // Index 0: the header bar and the clock must stay above the page.
        root.addView(web, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        sleeper.rebind(web);
        sleeper.applyPrefs();
        web.loadUrl(freshUrl());
        // removeCallbacksAndMessages above took the heartbeat with it.
        startHeartbeat();
    }

    /**
     * HA serves /local/ with Cache-Control: max-age=2678400 (31 days) and gives
     * no way to override it, so without a cache-buster an edited page would
     * never reach the panel. Appending a timestamp makes every load fresh.
     */
    private String freshUrl() {
        loadedUrl = Prefs.url(this);
        return loadedUrl + (loadedUrl.indexOf('?') < 0 ? "?t=" : "&t=")
                + System.currentTimeMillis();
    }

    @Override
    protected void onStart() {
        super.onStart();
        web.onResume();
        PanelWatchdog.cancel(this);
        header.setAutoHide(Prefs.autoHideHeader(this));
        sleeper.applyPrefs();
        // After the settings screen, and after a door call: the intercom
        // firmware is free to move the media stream while it has the panel.
        Volume.apply(this);

        if (pendingClearCache) {
            web.clearCache(true);
        }
        if (pendingReload || pendingClearCache || !Prefs.url(this).equals(loadedUrl)) {
            pendingReload = false;
            pendingClearCache = false;
            reload();
        }
    }

    /**
     * A door call pushes the panel off screen and, when it ends, the intercom
     * app goes back to the vendor launcher instead of to us -- so arm the
     * watchdog on the way out and stand it down on the way back in.
     */
    @Override
    protected void onStop() {
        super.onStop();
        // Pausing the WebView stops it decoding a camera stream nobody is
        // watching, and keeps its audio out of the watchdog's "is a call still
        // up?" check, which would otherwise never say no.
        web.onPause();
        header.stopTimer();
        sleeper.stop();

        // Back at the root is the deliberate way out; don't fight it.
        if (!isFinishing()) {
            PanelWatchdog.arm(this);
        }
    }

    /**
     * Android's warning that memory is tightening. It is sent before the killer
     * runs, so this is the app's one chance to leave a note saying it saw the
     * pressure that took it -- see Vitals.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Vitals.onTrim(this, level);
    }

    /**
     * Only a deliberate exit closes the run. A killed process never reaches
     * here, and that asymmetry is exactly what makes the open flag evidence;
     * closing it on a mere activity teardown would erase the death instead.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            Vitals.endRunCleanly(this);
        }
    }

    /**
     * Any touch anywhere counts as use, including taps that land in the page.
     *
     * The touch that dismisses the clock is the exception: it must not also
     * press whatever sits under it, or waking the panel at night would turn a
     * light on. Swallow that whole gesture, not just its first event -- an UP
     * with no DOWN reaches the WebView as a stray tap otherwise.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            swallowingGesture = clock.showing();
            sleeper.onUserActivity();
        }
        if (swallowingGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                swallowingGesture = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    public void onClose() {
        finish();
    }

    public void onSettings() {
        // Settings starts before this stops, so tell the watchdog to sit still.
        PanelWatchdog.suppressNext();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * The panel may well boot before Home Assistant is reachable, so a failed
     * load must not be terminal -- show why, then keep trying by itself.
     */
    void showErrorAndRetry(String description) {
        String html = "<html><head><meta name='viewport' content="
                + "'width=device-width,initial-scale=1'></head>"
                + "<body style='background:#111;color:#eee;font-family:sans-serif;"
                + "text-align:center;padding-top:20%'>"
                + "<h2>Waiting for Home Assistant</h2>"
                + "<p style='color:#888'>" + description + "</p>"
                + "<p style='color:#888'>Retrying every 5 seconds…</p>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

        if (!retryScheduled) {
            retryScheduled = true;
            handler.postDelayed(new RetryTask(this), RETRY_DELAY_MS);
        }
    }

    void reload() {
        retryScheduled = false;
        web.loadUrl(freshUrl());
    }

    void clearRetryFlag() {
        retryScheduled = false;
    }

    /** Back navigates within the page; at the root it falls through and exits. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goImmersive();
        }
    }

    /** Hide the status and navigation bars, and re-hide them after any swipe. */
    private void goImmersive() {
        root.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Keeps every navigation inside the WebView and drives the retry loop.
     *
     * Not final: PanelWebViewClient26 extends it. Everything that works on API
     * 19 belongs here, not there.
     */
    private static class PanelWebViewClient extends WebViewClient {
        private final MainActivity activity;

        PanelWebViewClient(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // Never hand off to a browser the device may not even have.
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode,
                                    String description, String failingUrl) {
            // The deprecated 4-arg form on purpose: the replacement is API 23+
            // and this has to fire on older WebViews too.
            activity.showErrorAndRetry(description);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            activity.clearRetryFlag();
        }
    }

    /**
     * The renderer-crash half of the client, split off for one reason: its
     * override mentions RenderProcessGoneDetail, an API 26 type, and this dex
     * is built with --min-api 19. Keeping it in a class of its own means the
     * name is only ever resolved on a device that has it, because ART resolves
     * a class at first use and nothing here is used below API 26. Audio.java
     * takes the same care with getActivePlaybackConfigurations(), by a
     * different trick -- there the API 26 type could be hidden behind a
     * wildcard, and an override's signature cannot be.
     *
     * These panels are Android 10, so this is the path that actually runs.
     */
    private static final class PanelWebViewClient26 extends PanelWebViewClient {
        private final MainActivity activity;

        PanelWebViewClient26(MainActivity activity) {
            super(activity);
            this.activity = activity;
        }

        @Override
        public boolean onRenderProcessGone(WebView view,
                                           RenderProcessGoneDetail detail) {
            // true means "handled, do not kill the app". Returning false is
            // what made a one-second camera glitch into a panel that was still
            // dead 42 minutes later; see MainActivity.onRenderGone().
            activity.onRenderGone(detail != null && detail.didCrash());
            return true;
        }
    }

    private static final class BeatTask implements Runnable {
        private final MainActivity activity;

        BeatTask(MainActivity activity) {
            this.activity = activity;
        }

        public void run() {
            activity.onHeartbeat();
        }
    }

    private static final class RetryTask implements Runnable {
        private final MainActivity activity;

        RetryTask(MainActivity activity) {
            this.activity = activity;
        }

        public void run() {
            activity.reload();
        }
    }
}
