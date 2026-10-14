package org.triprecorderng;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The 270 degree gauge of the This Trip screen: a track with ticks, a value arc that may fade from one color to
 * another, a knob at its end and the number and unit in the middle. It is drawn as large as the view allows (up to
 * the given limit) and everything inside scales with it.
 */
final class GaugeView extends View {
    private static final float START = 135f;
    private static final float SWEEP = 270f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint suf = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float maxDiameter;

    private float shown;
    private float target;
    private boolean first = true;
    private int colorA = Kit.ACCENT;
    private int colorB = Kit.ACCENT;
    private String number = "--";
    private String suffix = "";
    private String unit = "";
    private ValueAnimator anim;

    GaugeView(Context c, float maxDiameterPx) {
        super(c);
        maxDiameter = maxDiameterPx;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0x13FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        knob.setColor(0xF2FFFFFF);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeCap(Paint.Cap.ROUND);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setColor(Kit.TEXT);
        num.setTextAlign(Paint.Align.LEFT);
        suf.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        suf.setColor(Kit.SOFT);
        suf.setTextAlign(Paint.Align.LEFT);
        sub.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sub.setColor(Kit.MUTED);
        sub.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param fraction how far the arc goes, 0 to 1
     * @param from     color at the start of the arc (equal to {@code to} for a plain color)
     * @param number   the big text in the middle
     * @param suffix   small text next to it (such as %), or empty
     * @param unit     small text under it
     */
    void set(double fraction, int from, int to, String number, String suffix, String unit) {
        float f = (float) Math.max(0, Math.min(1, Double.isNaN(fraction) ? 0 : fraction));
        boolean same = colorA == from && colorB == to && number.equals(this.number)
                && suffix.equals(this.suffix) && unit.equals(this.unit) && Math.abs(f - target) < 0.0005f;
        if (same) return;
        colorA = from;
        colorB = to;
        this.number = number;
        this.suffix = suffix;
        this.unit = unit;
        if (anim != null) anim.cancel();
        if (first || Math.abs(f - shown) < 0.004f) {
            first = false;
            shown = target = f;
            invalidate();
            return;
        }
        target = f;
        anim = ValueAnimator.ofFloat(shown, f);
        anim.setDuration(450);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float d = Math.min(Math.min(w, h), maxDiameter);
        if (d <= 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float half = d / 2f;
        float stroke = d * 0.0822f;
        float r = half * 0.808f;

        for (int i = 0; i <= 18; i++) {
            boolean big = i % 3 == 0;
            double a = Math.toRadians(START + i * 15);
            float r1 = half * 0.959f;
            float r2 = half * (big ? 0.911f : 0.932f);
            tick.setColor(big ? 0x38FFFFFF : 0x1FFFFFFF);
            tick.setStrokeWidth(d * (big ? 0.011f : 0.007f));
            cv.drawLine(cx + (float) (r1 * Math.cos(a)), cy + (float) (r1 * Math.sin(a)),
                    cx + (float) (r2 * Math.cos(a)), cy + (float) (r2 * Math.sin(a)), tick);
        }

        rect.set(cx - r, cy - r, cx + r, cy + r);
        track.setStrokeWidth(stroke);
        cv.drawArc(rect, START, SWEEP, false, track);

        arc.setStrokeWidth(stroke);
        float sweep = shown * SWEEP;
        double end = Math.toRadians(START + sweep);
        float kx = cx + (float) (r * Math.cos(end));
        float ky = cy + (float) (r * Math.sin(end));
        if (shown > 0.004f) {
            if (colorA == colorB) {
                arc.setShader(null);
                arc.setColor(colorA);
            } else {
                arc.setShader(new LinearGradient(cx - r, 0, cx + r, 0, colorA, colorB, Shader.TileMode.CLAMP));
            }
            cv.drawArc(rect, START, sweep, false, arc);
            knob.setColor(0xF2FFFFFF);
        } else {
            knob.setColor(0x8CFFFFFF);
        }
        cv.drawCircle(kx, ky, stroke * 0.27f, knob);

        float numSize = d * 0.30f * (number.length() >= 3 ? 0.86f : 1f);
        float unitSize = d * 0.092f;
        num.setTextSize(numSize);
        suf.setTextSize(numSize * 0.45f);
        sub.setTextSize(unitSize);
        float numCap = numSize * 0.72f;
        float unitCap = unitSize * 0.72f;
        float gap = d * 0.05f;
        float block = numCap + (unit.isEmpty() ? 0 : gap + unitCap);
        float top = cy - block / 2f + d * 0.012f;
        float baseline = top + numCap;
        float wn = num.measureText(number);
        float ws = suffix.isEmpty() ? 0 : suf.measureText(suffix) + d * 0.012f;
        float x = cx - (wn + ws) / 2f;
        cv.drawText(number, x, baseline, num);
        if (!suffix.isEmpty()) cv.drawText(suffix, x + wn + d * 0.012f, baseline, suf);
        if (!unit.isEmpty()) cv.drawText(unit, cx, baseline + gap + unitCap, sub);
    }
}
