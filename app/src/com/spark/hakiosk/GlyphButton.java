package com.spark.hakiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The three header icons, drawn with Canvas instead of shipped as PNGs.
 *
 * Vector drawables are API 21+ and this app still has to run on 19, and a set
 * of PNGs would be five densities of two icons for what a dozen lines of Path
 * do -- on a device the README describes as short on both space and speed.
 * Drawn strokes are also sharp at whatever density this panel turns out to be.
 *
 * Thin round-capped strokes on purpose: that is the SF Symbols weight, and the
 * settings screen next door is styled to match.
 */
class GlyphButton extends View {

    static final int KIND_CLOSE = 0;
    static final int KIND_GEAR = 1;
    static final int KIND_GRABBER = 2;

    /** Teeth on the gear, evenly spaced. Eight reads as a gear; more turns to mush. */
    private static final int GEAR_TEETH = 8;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density;

    GlyphButton(Context context, int kind) {
        super(context);
        this.kind = kind;
        this.density = context.getResources().getDisplayMetrics().density;

        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
    }

    /** Press feedback is a dip in alpha -- iOS, not a Material ripple. */
    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        paint.setColor(Ui.GLYPH);

        if (kind == KIND_GRABBER) {
            // Faint by design: it sits over the dashboard all day and is only
            // there to be found when someone goes looking for it.
            paint.setAlpha(isPressed() ? 160 : 89);
            paint.setStyle(Paint.Style.FILL);
            float halfW = 18f * density;
            float halfH = 2.5f * density;
            rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
            canvas.drawRoundRect(rect, halfH, halfH, paint);
            return;
        }

        paint.setAlpha(isPressed() ? 110 : 255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * density);

        path.reset();
        if (kind == KIND_CLOSE) {
            float r = 5.6f * density;
            path.moveTo(cx - r, cy - r);
            path.lineTo(cx + r, cy + r);
            path.moveTo(cx + r, cy - r);
            path.lineTo(cx - r, cy + r);
        } else {
            float ring = 5.5f * density;
            float tooth = 2.3f * density;
            for (int i = 0; i < GEAR_TEETH; i++) {
                double angle = Math.PI * 2 * i / GEAR_TEETH;
                float sin = (float) Math.sin(angle);
                float cos = (float) Math.cos(angle);
                // Starts just inside the ring so the tooth joins it rather
                // than floating away from it.
                path.moveTo(cx + cos * (ring - 0.4f * density),
                        cy + sin * (ring - 0.4f * density));
                path.lineTo(cx + cos * (ring + tooth), cy + sin * (ring + tooth));
            }
            path.addCircle(cx, cy, ring, Path.Direction.CW);
            path.addCircle(cx, cy, 2.2f * density, Path.Direction.CW);
        }
        canvas.drawPath(path, paint);
    }
}
