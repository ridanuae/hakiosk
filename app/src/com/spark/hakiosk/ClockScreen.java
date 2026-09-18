package com.spark.hakiosk;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The night clock: a dim clock on black, laid over the page while the panel is
 * idle inside the sleep window.
 *
 * This replaced turning the screen off outright (v1.7). Turning it off was one
 * DevicePolicyManager call, but only a touch could undo it -- nothing in the
 * app can switch a dark screen back on, so the panel sat dead until somebody
 * walked up to it, including all morning after the window had ended. Keeping
 * the backlight on and covering the page instead costs a dim glow and gives
 * back both ends: the clock leaves by itself when the window ends, and no
 * device-administrator rights are needed for any of it.
 *
 * Drawn as ordinary views rather than a Canvas because it is two lines of text
 * that change once a minute; the layout pass is free at that rate.
 */
final class ClockScreen extends FrameLayout {

    private static final float TIME_SP = 88f;
    private static final float DATE_SP = 20f;

    /** Grey, not white: this is looked at in a dark room at night. */
    private static final int TIME_COLOR = 0xFF6E6E73;
    private static final int DATE_COLOR = 0xFF48484A;

    /**
     * The clock steps around a little every minute. These are LCD panels, so
     * burn-in proper is unlikely, but this runs eight hours a night on the same
     * pixels and the nudge is free.
     */
    private static final int DRIFT_DP = 14;
    private static final int DRIFT_X_STEPS = 7;
    private static final int DRIFT_Y_STEPS = 5;

    private final LinearLayout column;
    private final TextView time;
    private final TextView date;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEEE d MMMM", Locale.getDefault());

    private int drift;

    ClockScreen(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setVisibility(GONE);
        // Opaque and clickable so nothing behind it can be pressed by accident;
        // MainActivity swallows the gesture that dismisses it as well.
        setClickable(true);

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        time = new TextView(context);
        time.setTextColor(TIME_COLOR);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, TIME_SP);
        column.addView(time);

        date = new TextView(context);
        date.setTextColor(DATE_COLOR);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, DATE_SP);
        column.addView(date);

        addView(column, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** Repaints for the current minute and takes one step of the drift. */
    void tick() {
        Calendar now = Calendar.getInstance();
        // The same formatter the settings screen uses, so the panel says
        // "10:47 PM" in both places.
        time.setText(ScreenSleeper.formatTime(
                now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)));
        date.setText(dateFormat.format(new Date(now.getTimeInMillis())));

        drift++;
        column.setTranslationX(Ui.dp(getContext(),
                (drift % DRIFT_X_STEPS - DRIFT_X_STEPS / 2) * DRIFT_DP));
        column.setTranslationY(Ui.dp(getContext(),
                (drift % DRIFT_Y_STEPS - DRIFT_Y_STEPS / 2) * DRIFT_DP));
    }

    void show() {
        tick();
        setVisibility(VISIBLE);
        bringToFront();
    }

    void hide() {
        setVisibility(GONE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }
}
