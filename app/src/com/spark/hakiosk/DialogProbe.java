package com.spark.hakiosk;

import android.os.Handler;
import android.webkit.ValueCallback;
import android.webkit.WebView;

/**
 * Asks the page whether Home Assistant's more-info dialog is open.
 *
 * Why not the audio state, which is what v1.8 to v1.10 all used: on this
 * firmware it never goes false. With the camera closed and nothing playing the
 * panel still reports `players=1 musicActive=true` -- read off a live panel,
 * from the line Audio.describe() puts on the settings screen.
 * Anything built on that reading fails one of two ways: believe it and the
 * clock never comes back, cap it and the clock flaps on the cap instead.
 *
 * The dialog is an exact signal, and the WebView will answer for it. The
 * selector is the one browser_mod uses on the same element -- see showMoreInfo()
 * in browser_mod.js, which reaches
 * `<home-assistant>.shadowRoot.querySelector("ha-more-info-dialog")` to close
 * the dialog it opened.
 *
 * Answers come back asynchronously, and a WebView under load can fail to call
 * back at all, so every question carries a timeout that answers "not open".
 * A lost reply must not be able to strand the clock -- that is the failure this
 * class exists to end, and it would be careless to reintroduce it here.
 */
final class DialogProbe {

    /** Long enough for a busy WebView, short enough not to hold the clock up. */
    private static final long TIMEOUT_MS = 2500L;

    /**
     * Note what this does *not* ask: whether the element exists. v1.11's first
     * attempt did exactly that and failed identically to the audio versions,
     * because Home Assistant's dialog manager creates
     * `<ha-more-info-dialog>` once and leaves it in the shadow root for good --
     * which is the whole reason browser_mod can find it later and call
     * `closeDialog()` on it.
     *
     * Read out of the served frontend bundle rather than guessed
     * (frontend_latest/5703.*.js -- check yours, HA renames these):
     *
     *     closeDialog(){ ... this._open=!1 }
     *     _dialogClosed(){ ... this._entityId=void 0 ... }
     *     render(){ if(!this._entityId) return <nothing> ... }
     *
     * So `_open` is the direct answer and `_entityId` is the same answer one
     * animation later. `_open` is preferred; `_entityId` is only consulted if
     * a future frontend drops the field, so a rename degrades instead of
     * stranding the clock.
     *
     * The trailing detail is for the Dialog line on the settings screen. These
     * panels have no ADB and no logcat, so a reading on screen is the only way
     * to see what the probe actually got -- which is how the audio dead end was
     * finally proved.
     */
    private static final String JS =
            "(function(){try{"
            + "var h=document.querySelector('home-assistant');"
            + "if(!h||!h.shadowRoot)return '0 no-root';"
            + "var d=h.shadowRoot.querySelector('ha-more-info-dialog');"
            + "if(!d)return '0 no-el';"
            + "var o=d._open,e=d._entityId;"
            + "var open=(o===true)||(o===undefined&&!!e);"
            + "return (open?'1':'0')+' _open='+o+' ent='+(e||'-');"
            + "}catch(err){return '0 err';}})()";

    interface Answer {
        void onDialogOpen(boolean open);
    }

    private final WebView web;
    private final Handler handler;
    private final Timeout timeout = new Timeout(this);
    private final Reply reply = new Reply(this);

    private Answer waiting;

    /** Last raw answer, so the settings screen can be told what changed. */
    private String lastSeen;

    DialogProbe(WebView web, Handler handler) {
        this.web = web;
        this.handler = handler;
    }

    /** One question. The answer arrives exactly once, on the UI thread. */
    void ask(Answer answer) {
        waiting = answer;
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, TIMEOUT_MS);
        web.evaluateJavascript(JS, reply);
    }

    /**
     * Drop the question in flight, if there is one, without answering it.
     *
     * For the renderer-crash path only: the WebView this probe holds is about
     * to be destroyed, and a reply or a timeout arriving afterwards would call
     * back into a ScreenSleeper that has already moved on -- raising the clock
     * over the fresh page that just replaced the dead one. Note this is the one
     * case where *not* answering is right; everywhere else a lost reply must
     * still settle, which is what Timeout is for.
     */
    void cancel() {
        waiting = null;
        handler.removeCallbacks(timeout);
    }

    /**
     * evaluateJavascript hands the value back as JSON, so a returned string
     * arrives wrapped in quotes. The payload here is plain ASCII with no
     * quotes or backslashes in it, so stripping the outer pair is enough --
     * there is nothing to unescape.
     */
    private void received(String value) {
        String body = value;
        if (body != null && body.length() >= 2
                && body.charAt(0) == '"' && body.charAt(body.length() - 1) == '"') {
            body = body.substring(1, body.length() - 1);
        }
        if (body == null) {
            body = "0 null";
        }
        // Only on a change: this runs every two seconds while the clock is up,
        // and a SharedPreferences commit per tick would be careless.
        if (!body.equals(lastSeen)) {
            lastSeen = body;
            Prefs.putString(web.getContext(), Prefs.KEY_PROBE, body);
        }
        settle(body.startsWith("1"));
    }

    /** Whichever of the reply and the timeout arrives first wins. */
    private void settle(boolean open) {
        Answer answer = waiting;
        if (answer == null) {
            return;
        }
        waiting = null;
        handler.removeCallbacks(timeout);
        answer.onDialogOpen(open);
    }

    /**
     * Named, not anonymous, for the reason given in SettingsActivity: d8 8.2.2
     * cannot read anonymous classes emitted by javac 21.
     *
     * And raw ValueCallback, not ValueCallback<String>, for a neighbouring
     * reason: the parameterised version makes javac emit an
     * `onReceiveValue(Object)` bridge, and d8 8.2.2 dies on that bridge with an
     * internal NullPointerException -- "Error in
     * build/classes/com/spark/hakiosk/DialogProbe$Reply.class". The raw type
     * has no bridge to trip over. Hence the cast below.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Reply implements ValueCallback {
        private final DialogProbe probe;

        Reply(DialogProbe probe) {
            this.probe = probe;
        }

        public void onReceiveValue(Object value) {
            probe.received(value instanceof String ? (String) value : null);
        }
    }

    private static final class Timeout implements Runnable {
        private final DialogProbe probe;

        Timeout(DialogProbe probe) {
            this.probe = probe;
        }

        public void run() {
            probe.settle(false);
        }
    }
}
