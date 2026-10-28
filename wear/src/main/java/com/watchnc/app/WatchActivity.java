package com.watchnc.app;
import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.android.gms.wearable.*;
import com.watchnc.core.PatrolState;
import java.util.Date;

public class WatchActivity extends Activity implements DataClient.OnDataChangedListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dashboard dashboard;
    private Ringtone previewTone;
    private int relay = -1;
    private long sampleTime;
    private boolean keepAwake = true;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { dashboard.invalidate(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); dashboard = new Dashboard(); setContentView(dashboard);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
    @Override protected void onResume() {
        super.onResume(); Wearable.getDataClient(this).addListener(this);
        Wearable.getDataClient(this).getDataItems().addOnSuccessListener(items -> {
            try { for (DataItem item : items) if ("/machine-state".equals(item.getUri().getPath())) accept(item); }
            finally { items.release(); }
        });
        handler.post(refresh);
    }
    @Override protected void onPause() { if (previewTone != null) previewTone.stop(); handler.removeCallbacks(refresh); Wearable.getDataClient(this).removeListener(this); super.onPause(); }
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent event : events) {
            String path = event.getDataItem().getUri().getPath();
            if ("/machine-state".equals(path)) {
                if (event.getType() == DataEvent.TYPE_CHANGED) accept(event.getDataItem());
                else { relay = -1; runOnUiThread(dashboard::invalidate); }
            }
        }
    }
    private void previewSound(String id) {
        if (!id.equals("default") && !id.startsWith("content://media/")) return;
        Uri uri = id.equals("default") ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : Uri.parse(id);
        try {
            if (previewTone != null) previewTone.stop();
            previewTone = RingtoneManager.getRingtone(this, uri);
            if (previewTone != null) {
                previewTone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                previewTone.play();
            }
        } catch (RuntimeException ignored) { }
    }
    private void accept(DataItem item) {
        DataMap map = DataMapItem.fromDataItem(item).getDataMap(); long time = map.getLong("time", 0);
        if (!PatrolState.acceptSample(time, sampleTime, System.currentTimeMillis())) return;
        sampleTime = time; relay = map.getInt("relay", -1); runOnUiThread(dashboard::invalidate);
    }
    private class Dashboard extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int previewAction = View.generateViewId();
        Dashboard() {
            super(WatchActivity.this);
            setClickable(true); setFocusable(true);
            setContentDescription("CNC patrol light status. Tap to toggle keep screen on.");
            setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(previewAction, "Preview tone"));
                }
                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == previewAction) {
                        previewSound(getSharedPreferences("sound", 0).getString("id", "default"));
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
        private void text(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
            p.setColor(color); p.setTextSize(size); p.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL)); p.setTextAlign(Paint.Align.CENTER); c.drawText(s, x, y, p);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float scale = Math.min(getWidth(), getHeight()) / 320f;
            canvas.save(); canvas.translate((getWidth() - 320 * scale) / 2, (getHeight() - 320 * scale) / 2); canvas.scale(scale, scale);
            canvas.drawColor(0xff080f14);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(0xff293c42); canvas.drawCircle(160, 160, 147, p);
            p.setStrokeWidth(3); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xff8de4c0); canvas.drawArc(14, 14, 306, 306, 229, 22, false, p);
            p.setStyle(Paint.Style.FILL);
            text(canvas, "WATCHNC", 160, 58, 11, 0xffa4b7b9, true);
            text(canvas, android.text.format.DateFormat.getTimeFormat(WatchActivity.this).format(new Date()), 160, 104, 39, 0xffedf6f3, false);
            boolean valid = relay >= 0 && PatrolState.fresh(sampleTime, System.currentTimeMillis());
            int[] masks = {0x80, 0x10, 0x40}; int[] colors = {0xffff5e62, 0xffffc451, 0xff55df9c};
            String[] names = {"RED", "YELLOW", "GREEN"};
            p.setColor(0xff14232a); canvas.drawRoundRect(49, 119, 271, 195, 22, 22, p);
            for (int i = 0; i < 3; i++) {
                float x = 94 + i * 66; boolean on = valid && (relay & masks[i]) != 0;
                p.setColor(on ? colors[i] : 0xff34474d); canvas.drawCircle(x, 150, 13, p);
                if (on) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(colors[i]); canvas.drawCircle(x, 150, 18, p); p.setStyle(Paint.Style.FILL); }
                text(canvas, names[i], x, 181, 10, on ? colors[i] : 0xffa4b7b9, true);
            }
            String status = valid ? ((relay & 0xd0) == 0 ? "LIGHTS OFF" : "LIVE SIGNAL") : "NO CURRENT DATA";
            text(canvas, status, 160, 222, 13, valid ? 0xffedf6f3 : 0xffffc451, true);
            text(canvas, keepAwake ? "SCREEN ON  ·  TAP TO CHANGE" : "SCREEN AUTO  ·  TAP TO CHANGE", 160, 244, 9, 0xffa4b7b9, false);
            p.setColor(0xff1c3036); canvas.drawRoundRect(99, 253, 221, 280, 13, 13, p);
            text(canvas, "▶  PREVIEW TONE", 160, 271, 10, 0xff8de4c0, true);
            setContentDescription("CNC patrol lights: " + (valid ? StateListener.label(PatrolState.normalize(relay)) : "no current data") + ". Tap to toggle keep screen on.");
            canvas.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float scale = Math.min(getWidth(), getHeight()) / 320f;
                float x = (e.getX() - (getWidth() - 320 * scale) / 2) / scale;
                float y = (e.getY() - (getHeight() - 320 * scale) / 2) / scale;
                if (x >= 85 && x <= 235 && y >= 250 && y <= 290) {
                    previewSound(getSharedPreferences("sound", 0).getString("id", "default"));
                } else performClick();
            }
            return true;
        }
        @Override public boolean performClick() {
            super.performClick(); keepAwake = !keepAwake;
            if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            invalidate(); return true;
        }
    }
}
