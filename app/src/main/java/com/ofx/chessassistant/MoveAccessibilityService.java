package com.ofx.chessassistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.accessibility.AccessibilityEvent;

public class MoveAccessibilityService extends AccessibilityService {

    private static volatile MoveAccessibilityService instance;

    public static boolean hasInstance() {
        return instance != null;
    }

    /** source: square centers in real screen px, destination same. Drag then release. */
    public static void performMove(RectF from, RectF to) {
        MoveAccessibilityService svc = instance;
        if (svc == null || from == null || to == null) return;

        Path path = new Path();
        path.moveTo(from.centerX(), from.centerY());
        path.lineTo(to.centerX(), to.centerY());

        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, 180));
        svc.dispatchGesture(b.build(), null, null);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // gestures only; no event reading, nothing shows in the accessibility tree
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }
}
