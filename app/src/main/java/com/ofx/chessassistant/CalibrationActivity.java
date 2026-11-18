package com.ofx.chessassistant;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.TextView;

/** Two taps: top-left of board, then bottom-right. Stores the rect for gestures. */
public class CalibrationActivity extends Activity {

    private float firstX = -1, firstY = -1;
    private TextView prompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prompt = new TextView(this);
        prompt.setText("Tap TOP-LEFT corner of the chessboard");
        prompt.setTextSize(18f);
        prompt.setTextColor(0xFF39FF6B);
        prompt.setPadding(24, 80, 24, 24);
        setContentView(prompt);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) return true;
        if (firstX < 0) {
            firstX = event.getX();
            firstY = event.getY();
            prompt.setText("Now tap BOTTOM-RIGHT corner");
            return true;
        }
        float right = event.getX();
        float bottom = event.getY();
        int left = (int) Math.min(firstX, right);
        int top = (int) Math.min(firstY, bottom);
        int size = (int) Math.max(Math.abs(right - firstX), Math.abs(bottom - firstY));

        SharedPreferences prefs = getSharedPreferences(ManualBoardActivity.PREFS, MODE_PRIVATE);
        prefs.edit()
                .putInt(ManualBoardActivity.KEY_LEFT, left)
                .putInt(ManualBoardActivity.KEY_TOP, top)
                .putInt(ManualBoardActivity.KEY_SIZE, size)
                .apply();
        finish();
        return true;
    }
}
