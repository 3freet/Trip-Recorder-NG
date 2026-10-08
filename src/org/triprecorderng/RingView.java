package org.triprecorderng;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Circular progress ring with a centered number, like the stock app's score ring. */
final class RingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private double value = Double.NaN;
    private double max = 100;
    private String centerText = "--";
    private String subText = "";
    private int color = Ui.GREEN;
    private final float stroke;

    RingView(Context c, float strokeDp, float numSp, float subSp) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        stroke = strokeDp * d;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(Ui.RING_TRACK);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        num.setTextAlign(Paint.Align.CENTER);
        num.setColor(Ui.TEXT_SELECTED);
        num.setTextSize(numSp * sd);
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setColor(Ui.TEXT_LABEL);
        sub.setTextSize(subSp * sd);
    }

    void set(double v, double maxValue, String center, String subtitle, int ringColor) {
        value = v;
        max = maxValue <= 0 ? 1 : maxValue;
        centerText = center;
        subText = subtitle == null ? "" : subtitle;
        color = ringColor;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h) - stroke;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        rect.set(left, top, left + size, top + size);
        c.drawArc(rect, 0, 360, false, track);
        if (!Double.isNaN(value) && value > 0) {
            arc.setColor(color);
            float sweep = (float) (Math.min(1.0, value / max) * 360.0);
            c.drawArc(rect, -90, sweep, false, arc);
        }
        float cy = h / 2f;
        if (subText.length() > 0) {
            c.drawText(centerText, w / 2f, cy + num.getTextSize() * 0.15f, num);
            c.drawText(subText, w / 2f, cy + num.getTextSize() * 0.15f + sub.getTextSize() * 1.5f, sub);
        } else {
            c.drawText(centerText, w / 2f, cy + num.getTextSize() * 0.35f, num);
        }
    }
}
