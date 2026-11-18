package com.ofx.chessassistant;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_PROJECTION = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button start = findViewById(R.id.btn_start);
        Button acc = findViewById(R.id.btn_accessibility);
        Button manual = findViewById(R.id.btn_manual);

        manual.setOnClickListener(v ->
                startActivity(new Intent(this, ManualBoardActivity.class)));

        acc.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "Enable \"Chess Assistant\" in Accessibility", Toast.LENGTH_LONG).show();
        });

        start.setOnClickListener(v -> {
            if (!MoveAccessibilityService.hasInstance()) {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show();
                return;
            }
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION || resultCode != RESULT_OK) return;

        Intent svc = new Intent(this, AssistantService.class);
        svc.putExtra("resultCode", resultCode);
        svc.putExtra("data", data);
        startForegroundService(svc);
        Toast.makeText(this, "Assistant online — undefeated mode", Toast.LENGTH_SHORT).show();
        finish();
    }
}
