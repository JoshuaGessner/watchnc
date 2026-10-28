package com.watchnc.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import com.google.android.gms.wearable.*;
import java.util.ArrayList;
import java.util.UUID;

public class MainActivity extends Activity implements DataClient.OnDataChangedListener {
    private static final int BG = 0xff0b1218, SURFACE = 0xff14212a, BORDER = 0xff2b3d45;
    private static final int TEXT = 0xffedf6f3, MUTED = 0xffa4b7b9, ACCENT = 0xff8de4c0;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private TextView statusTitle, statusDetail;
    private Button startButton, stopButton;
    private EditText hostInput;
    private TextView soundTitle, soundStatus;
    private String toneRequest;
    private android.app.AlertDialog loadingTones;
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() { renderStatus(); statusHandler.postDelayed(this, 500); }
    };

    private void renderStatus() {
        MonitorService.DisplayState state = MonitorService.display;
        statusTitle.setText(state.title);
        statusTitle.setTextColor(state.live ? ACCENT : state.active ? 0xffffc451 : TEXT);
        statusDetail.setText(state.detail);
        startButton.setEnabled(!state.active); startButton.setAlpha(state.active ? .45f : 1f);
        stopButton.setEnabled(state.active); stopButton.setAlpha(state.active ? 1f : .45f);
        hostInput.setEnabled(!state.active); hostInput.setAlpha(state.active ? .65f : 1f);
    }
    @Override protected void onResume() {
        super.onResume(); statusHandler.post(refreshStatus); updateSoundLabel();
        DataClient client = Wearable.getDataClient(this);
        client.addListener(this);
        client.getDataItems().addOnSuccessListener(items -> {
            try { for (DataItem item : items) if ("/sound-ack".equals(item.getUri().getPath())) receiveAck(item); }
            finally { items.release(); }
        });
    }
    @Override protected void onPause() { statusHandler.removeCallbacks(refreshStatus); Wearable.getDataClient(this).removeListener(this); super.onPause(); }
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent event : events) {
            if (event.getType() != DataEvent.TYPE_CHANGED) continue;
            String path = event.getDataItem().getUri().getPath();
            if ("/sound-ack".equals(path)) receiveAck(event.getDataItem());
            else if ("/tone-list".equals(path)) receiveToneList(event.getDataItem());
        }
    }
    private void receiveAck(DataItem item) {
        DataMap map = DataMapItem.fromDataItem(item).getDataMap();
        SharedPreferences p = getSharedPreferences("sound", 0);
        if (!map.getString("request", "").equals(p.getString("request", ""))) return;
        String error = map.getString("error", "");
        p.edit().putString("status", error.isEmpty() ? "Saved on watch" : "Watch couldn't save sound: " + error).apply();
        runOnUiThread(this::updateSoundLabel);
    }
    private void updateSoundLabel() {
        if (soundTitle == null) return;
        SharedPreferences p = getSharedPreferences("sound", 0);
        soundTitle.setText("Watch alert: " + p.getString("name", "Watch default") + "  ›");
        soundStatus.setText(p.getString("status", "Uses the watch's default notification sound"));
    }
    private void chooseSound() {
        toneRequest = UUID.randomUUID().toString();
        String request = toneRequest;
        loadingTones = new android.app.AlertDialog.Builder(this).setTitle("Watch alert sound")
            .setMessage("Loading sounds from your watch…")
            .setNegativeButton("Cancel", (dialog, which) -> toneRequest = null).create();
        loadingTones.setOnCancelListener(dialog -> toneRequest = null);
        loadingTones.show();
        statusHandler.postDelayed(() -> {
            if (request.equals(toneRequest) && loadingTones != null && loadingTones.isShowing())
                loadingTones.setMessage("Still waiting for your watch. Make sure it is connected and WatchNC is installed.");
        }, 15000);
        PutDataMapRequest map = PutDataMapRequest.create("/tone-request");
        map.getDataMap().putString("request", request);
        Wearable.getDataClient(this).putDataItem(map.asPutDataRequest().setUrgent())
            .addOnFailureListener(e -> {
                if (!request.equals(toneRequest)) return;
                toneRequest = null;
                if (loadingTones != null) loadingTones.dismiss();
                Toast.makeText(this, "Couldn't request watch sounds; try again", Toast.LENGTH_LONG).show();
            });
    }
    private void receiveToneList(DataItem item) {
        DataMap map = DataMapItem.fromDataItem(item).getDataMap();
        String request = map.getString("request", "");
        String error = map.getString("error", "");
        ArrayList<String> names = map.getStringArrayList("names"), uris = map.getStringArrayList("uris");
        runOnUiThread(() -> {
            if (!request.equals(toneRequest) || isFinishing() || isDestroyed()) return;
            toneRequest = null;
            if (loadingTones != null) loadingTones.dismiss();
            if (!error.isEmpty() || names == null || uris == null || names.isEmpty() || names.size() != uris.size()) {
                Toast.makeText(this, "Couldn't load watch sounds; try again", Toast.LENGTH_LONG).show(); return;
            }
            new android.app.AlertDialog.Builder(this).setTitle("Watch alert sound")
                .setItems(names.toArray(new String[0]), (dialog, which) -> sendSound(uris.get(which), names.get(which)))
                .setNegativeButton("Cancel", null).show();
        });
    }
    private void sendSound(String id, String name) {
        String request = UUID.randomUUID().toString();
        getSharedPreferences("sound", 0).edit().putString("name", name).putString("request", request).putString("status", "Waiting for watch…").apply();
        updateSoundLabel();
        PutDataMapRequest map = PutDataMapRequest.create("/alert-sound");
        map.getDataMap().putString("id", id); map.getDataMap().putString("request", request);
        Wearable.getDataClient(getApplicationContext()).putDataItem(map.asPutDataRequest().setUrgent())
            .addOnFailureListener(e -> {
                getSharedPreferences("sound", 0).edit().putString("status", "Couldn't sync sound; try again").apply();
                runOnUiThread(this::updateSoundLabel);
            });
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p);
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private Button button(String title, boolean primary) {
        Button b = new Button(this); b.setText(title); b.setTextSize(15); b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? BG : TEXT); b.setBackground(shape(primary ? ACCENT : 0xff1c2b33, primary ? 0 : BORDER, 16));
        b.setMinHeight(dp(48)); return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences sounds = getSharedPreferences("sound", 0);
        if (!sounds.getBoolean("watch_tones_version", false)) {
            sounds.edit().clear().putBoolean("watch_tones_version", true).apply();
        }
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        LinearLayout page = column(); page.setBackgroundColor(BG);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30 ? insets.getInsets(android.view.WindowInsets.Type.statusBars()).top : insets.getSystemWindowInsetTop();
            view.setPadding(dp(20), dp(16) + top, dp(20), dp(16));
            return insets;
        });

        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("W", 20, BG, true); mark.setGravity(Gravity.CENTER); mark.setBackground(shape(ACCENT, 0, 14));
        header.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout brand = column(); LinearLayout.LayoutParams brandLayout = new LinearLayout.LayoutParams(-2, -2); brandLayout.leftMargin = dp(12);
        brand.addView(text("WATCHNC", 17, TEXT, true)); brand.addView(text("MACHINE MONITOR", 10, MUTED, true)); header.addView(brand, brandLayout);
        add(page, header, 0);

        TextView title = text("Machine monitoring", 25, TEXT, true); add(page, title, 18);
        TextView intro = text("Your machine status, on your watch.", 14, MUTED, false); add(page, intro, 3);

        LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackground(shape(SURFACE, BORDER, 22)); add(page, card, 16);
        TextView cardTitle = text("Connection", 18, TEXT, true); add(card, cardTitle, 0);
        LinearLayout statusCard = column(); statusCard.setPadding(dp(14), dp(10), dp(14), dp(10)); statusCard.setBackground(shape(BG, BORDER, 12)); add(card, statusCard, 12);
        statusTitle = text("Monitoring off", 16, TEXT, true); add(statusCard, statusTitle, 0);
        statusDetail = text("Start monitoring to connect to the machine.", 13, MUTED, false); statusDetail.setMaxLines(3); statusDetail.setEllipsize(android.text.TextUtils.TruncateAt.END); add(statusCard, statusDetail, 4);
        TextView hostLabel = text("MACHINE IP ADDRESS", 11, MUTED, true); hostLabel.setLetterSpacing(.08f); add(card, hostLabel, 14);
        EditText host = new EditText(this); host.setSingleLine(); host.setTextSize(17); host.setTextColor(TEXT); host.setHintTextColor(0xff7d9296);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); host.setImeOptions(EditorInfo.IME_ACTION_DONE);
        host.setText(getPreferences(0).getString("host", "192.168.1.1")); host.setHint("e.g. 192.168.1.1");
        host.setPadding(dp(16), 0, dp(16), 0); host.setMinHeight(dp(48)); host.setBackground(shape(BG, BORDER, 12)); host.setContentDescription("Machine IP address"); add(card, host, 6);
        TextView hint = text("Connect Ethernet to the machine network.", 12, MUTED, false); add(card, hint, 8);
        Button start = button("Start monitoring  →", true); add(card, start, 14);
        Button stop = button("Stop monitoring", false); add(card, stop, 8);
        startButton = start; stopButton = stop; hostInput = host; renderStatus();

        LinearLayout soundCard = column(); soundCard.setPadding(dp(16), dp(10), dp(16), dp(10)); soundCard.setBackground(shape(SURFACE, BORDER, 16));
        soundCard.setClickable(true); soundCard.setFocusable(true); soundCard.setOnClickListener(v -> chooseSound()); add(page, soundCard, 12);
        soundTitle = text("Watch alert: Watch default  ›", 15, TEXT, true); soundTitle.setMaxLines(1); soundTitle.setEllipsize(android.text.TextUtils.TruncateAt.END); add(soundCard, soundTitle, 0);
        soundStatus = text("Uses the watch's default notification sound", 12, MUTED, false); soundStatus.setMaxLines(2); add(soundCard, soundStatus, 3);
        updateSoundLabel();
        TextView safety = text("Informational monitoring only · Not a safety indicator", 11, 0xffa4b7b9, false); add(page, safety, 10);

        start.setOnClickListener(v -> {
            String address = host.getText().toString().trim();
            if (!address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) { host.setError("Enter a valid IPv4 address"); return; }
            for (String part : address.split("\\.")) if (Integer.parseInt(part) > 255) { host.setError("Enter a valid IPv4 address"); return; }
            getPreferences(0).edit().putString("host", address).apply();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            try {
                startForegroundService(new Intent(this, MonitorService.class).putExtra("host", address));
                statusTitle.setText("Starting"); statusDetail.setText("Preparing the Ethernet connection.");
            } catch (RuntimeException e) {
                MonitorService.display = new MonitorService.DisplayState(false, false, "Could not start", e.getMessage() == null ? "Unable to start monitoring." : e.getMessage());
                renderStatus();
            }
        });
        stop.setOnClickListener(v -> {
            statusTitle.setText("Stopping"); statusDetail.setText("Ending the machine connection.");
            startService(new Intent(this, MonitorService.class).setAction("stop"));
        });
        setContentView(page);
    }
}
