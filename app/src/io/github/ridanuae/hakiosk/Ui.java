package io.github.ridanuae.hakiosk;

import android.content.Context;

/**
 * The few colours and the one unit conversion shared by the views built in
 * code. Everything the settings screen uses lives in res/values instead.
 */
final class Ui {

    /** iOS "secondary system background", near-opaque so the page shows through. */
    static final int BAR_BG = 0xE61C1C1E;

    /** iOS separator, used for the hairline under the bar. */
    static final int HAIRLINE = 0xFF38383A;

    static final int GLYPH = 0xFFFFFFFF;

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
