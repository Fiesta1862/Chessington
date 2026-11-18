package com.ofx.chessassistant;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Draws a glowing arrow from -> to over the whole screen for the current best move. */
public class HintDrawable extends Drawable {

    private final BoardDetector.Frame frame;
    private final String uci;

    HintDrawable(BoardDetector.Frame frame, String uci) {
        this.frame = frame;
        this.uci = uci;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!frame.stable || uci == null || uci.length() < 4) return;
        RectF from = frame.squareRect(uci.substring(0, 2));
        RectF to = frame.squareRect(uci.substring(2, 4));

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x6639FF6B);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(14f);
        p.setShadowLayer(18f, 0, 0, 0xB039FF6B);

        canvas.drawLine(from.centerX(), from.centerY(), to.centerX(), to.centerY(), p);

        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(to.centerX(), to.centerY(), 10f, p);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
