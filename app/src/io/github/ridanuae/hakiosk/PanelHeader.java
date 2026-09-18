package io.github.ridanuae.hakiosk;

import android.content.Context;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * The bar across the bottom: close on the left, settings on the right.
 *
 * It overlays the WebView rather than sitting above it in the layout. Showing
 * and hiding it must not re-lay-out and re-flow the page, which is slow on
 * this device and would make the dashboard jump every eight seconds.
 *
 * With auto-hide on it starts *collapsed*: nothing shows but a small grabber
 * pill in the bottom-right corner, and the bar appears only when that pill is
 * pressed. The panel spends all day showing the dashboard, so the resting
 * state is the one with no chrome in it. Bottom-right because the Home
 * Assistant dashboard puts its view tabs centre-left, and kiosk-mode already
 * hides the sidebar button on the far left.
 *
 * Named nested classes rather than anonymous ones throughout -- see the note
 * at the top of MainActivity.
 */
class PanelHeader extends FrameLayout {

    /** Tiny on purpose: this is a wall panel, the page is what matters. */
    static final int HEIGHT_DP = 34;

    private static final int BUTTON_WIDTH_DP = 44;
    private static final int GRABBER_WIDTH_DP = 44;
    private static final long HIDE_DELAY_MS = 8000L;
    private static final long FADE_MS = 180L;

    /** What the two buttons do. MainActivity implements this. */
    interface Listener {
        void onClose();

        void onSettings();
    }

    private final Listener listener;
    private final LinearLayout bar;
    private final GlyphButton grabber;
    private final Handler handler = new Handler();
    private final HideTask hideTask = new HideTask(this);
    private final FadeOutListener fadeOutListener = new FadeOutListener(this);

    private boolean autoHide = true;

    PanelHeader(Context context, Listener listener) {
        super(context);
        this.listener = listener;

        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Ui.BAR_BG);
        // Clickable so a tap on the bar itself does not fall through to the page.
        bar.setClickable(true);
        bar.setOnClickListener(new PokeClick(this));
        addView(bar, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Above the row, not below it: the bar sits at the bottom now, so the
        // hairline belongs on the edge that faces the page.
        View hairline = new View(context);
        hairline.setBackgroundColor(Ui.HAIRLINE);
        bar.addView(hairline, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 1));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(row, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        GlyphButton close = new GlyphButton(context, GlyphButton.KIND_CLOSE);
        close.setOnClickListener(new CloseClick(this));
        row.addView(close, new LinearLayout.LayoutParams(
                Ui.dp(context, BUTTON_WIDTH_DP), LayoutParams.MATCH_PARENT));

        View spacer = new View(context);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        GlyphButton gear = new GlyphButton(context, GlyphButton.KIND_GEAR);
        gear.setOnClickListener(new GearClick(this));
        row.addView(gear, new LinearLayout.LayoutParams(
                Ui.dp(context, BUTTON_WIDTH_DP), LayoutParams.MATCH_PARENT));

        // Start collapsed. onStart() calls setAutoHide() and would settle this
        // anyway, but doing it here too avoids one frame of full bar on the
        // very first layout.
        bar.setVisibility(View.GONE);

        grabber = new GlyphButton(context, GlyphButton.KIND_GRABBER);
        grabber.setOnClickListener(new GrabberClick(this));
        addView(grabber, new FrameLayout.LayoutParams(
                Ui.dp(context, GRABBER_WIDTH_DP), LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM | Gravity.RIGHT));
    }

    /** Called on every start, so a change in Settings takes effect at once. */
    void setAutoHide(boolean enabled) {
        autoHide = enabled;
        handler.removeCallbacks(hideTask);
        if (enabled) {
            // Collapsed from the start, so there is no countdown to run yet:
            // the pill is the only way in, and pressing it starts the timer.
            barHidden();
        } else {
            showBar();
        }
    }

    /** Restart the countdown; the bar should not vanish under a finger. */
    void poke() {
        if (!autoHide) {
            return;
        }
        handler.removeCallbacks(hideTask);
        showBar();
        handler.postDelayed(hideTask, HIDE_DELAY_MS);
    }

    void stopTimer() {
        handler.removeCallbacks(hideTask);
    }

    private void showBar() {
        if (bar.getVisibility() != View.VISIBLE) {
            AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
            fadeIn.setDuration(FADE_MS);
            bar.startAnimation(fadeIn);
        }
        bar.setVisibility(View.VISIBLE);
        grabber.setVisibility(View.GONE);
    }

    void fadeOutBar() {
        if (bar.getVisibility() != View.VISIBLE) {
            return;
        }
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(FADE_MS);
        fadeOut.setAnimationListener(fadeOutListener);
        bar.startAnimation(fadeOut);
    }

    void barHidden() {
        bar.setVisibility(View.GONE);
        grabber.setVisibility(View.VISIBLE);
    }

    private static final class HideTask implements Runnable {
        private final PanelHeader header;

        HideTask(PanelHeader header) {
            this.header = header;
        }

        public void run() {
            header.fadeOutBar();
        }
    }

    private static final class FadeOutListener implements Animation.AnimationListener {
        private final PanelHeader header;

        FadeOutListener(PanelHeader header) {
            this.header = header;
        }

        public void onAnimationStart(Animation animation) {
        }

        public void onAnimationRepeat(Animation animation) {
        }

        public void onAnimationEnd(Animation animation) {
            header.barHidden();
        }
    }

    private static final class CloseClick implements View.OnClickListener {
        private final PanelHeader header;

        CloseClick(PanelHeader header) {
            this.header = header;
        }

        public void onClick(View view) {
            header.listener.onClose();
        }
    }

    private static final class GearClick implements View.OnClickListener {
        private final PanelHeader header;

        GearClick(PanelHeader header) {
            this.header = header;
        }

        public void onClick(View view) {
            header.poke();
            header.listener.onSettings();
        }
    }

    private static final class GrabberClick implements View.OnClickListener {
        private final PanelHeader header;

        GrabberClick(PanelHeader header) {
            this.header = header;
        }

        public void onClick(View view) {
            header.poke();
        }
    }

    private static final class PokeClick implements View.OnClickListener {
        private final PanelHeader header;

        PokeClick(PanelHeader header) {
            this.header = header;
        }

        public void onClick(View view) {
            header.poke();
        }
    }
}
