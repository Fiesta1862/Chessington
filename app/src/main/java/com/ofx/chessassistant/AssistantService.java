package com.ofx.chessassistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.IOException;

public class AssistantService extends Service {

    private static final String CHANNEL_ID = "assistant";
    private static final String STOCKFISH_ASSET = "stockfish";
    private static final int ANALYSIS_PERIOD_MS = 1200;

    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    private ImageReader reader;
    private HandlerThread workerThread;
    private Handler worker;

    private WindowManager windowManager;
    private ImageView hintView;
    private boolean overlayVisible;

    private StockfishEngine engine;
    private BoardDetector detector = new BoardDetector();

    // board calibration in normalized screen coords, set by BoardDetector on first stable read
    private int lastMoveUci = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        startForeground(1, n);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        workerThread = new HandlerThread("assistant-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getIntExtra("resultCode", -1) != -1) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            startProjection(resultCode, data);
            startEngine();
            showOverlay();
            worker.postDelayed(this::captureLoop, 800);
        }
        return START_STICKY;
    }

    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        int density = getResources().getDisplayMetrics().densityDpi;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        vdisplay = projection.createVirtualDisplay("chessington",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, worker);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, worker);
    }

    private void startEngine() {
        try {
            engine = new StockfishEngine(this);
        } catch (IOException e) {
            android.util.Log.e("Chessington", "stockfish spawn failed", e);
            engine = null;
        }
    }

    private void showOverlay() {
        if (overlayVisible) return;
        hintView = new ImageView(this);
        hintView.setAlpha(0f); // fully hidden until a hint is drawn
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        windowManager.addView(hintView, lp);
        overlayVisible = true;
    }

    private void captureLoop() {
        if (projection == null || reader == null) return;
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane plane = image.getPlanes()[0];
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            Bitmap bmp = BoardDetector.imageToBitmap(image, rowStride, pixelStride);
            if (bmp == null) return;

            BoardDetector.Frame frame = detector.analyze(bmp);
            if (frame.stable && frame.fen != null && engine != null
                    && frame.sideToMove == frame.engineColor) {
                String best = engine.bestMove(frame.fen, 300);
                if (best != null && !best.matches("(none|null).*")) {
                    drawHint(frame, best);
                    if (MoveAccessibilityService.hasInstance()) {
                        MoveAccessibilityService.performMove(frame.squareRect(best.substring(0, 2)),
                                frame.squareRect(best.substring(2, 4)));
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("Chessington", "capture pass failed", e);
        } finally {
            if (image != null) image.close();
            worker.postDelayed(this::captureLoop, ANALYSIS_PERIOD_MS);
        }
    }

    private void drawHint(BoardDetector.Frame frame, String uci) {
        HintDrawable hd = new HintDrawable(frame, uci);
        hintView.setImageDrawable(hd);
        hintView.setAlpha(1f);
        hintView.invalidate();
        worker.postDelayed(() -> {
            if (overlayVisible && hintView != null) {
                hintView.setAlpha(0f);
                hintView.invalidate();
            }
        }, 2500);
    }

    @Override
    public void onDestroy() {
        worker.post(() -> {
            if (projection != null) projection.stop();
        });
        if (overlayVisible && hintView != null) {
            windowManager.removeView(hintView);
            overlayVisible = false;
        }
        if (engine != null) engine.close();
        if (vdisplay != null) vdisplay.release();
        if (reader != null) reader.close();
        workerThread.quitSafely();
        super.onDestroy();
    }
}
