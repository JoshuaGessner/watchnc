package com.watchnc.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
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
import com.watchnc.core.SignalMapping;
import java.util.UUID;

public class MainActivity extends Activity implements DataClient.OnDataChangedListener {
    private static final int BG = 0xff0b1218, SURFACE = 0xff14212a, BORDER = 0xff2b3d45;
    private static final int TEXT = 0xffedf6f3, MUTED = 0xffa4b7b9, ACCENT = 0xff8de4c0;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private TextView statusTitle, statusDetail;
    private Button startButton, stopButton;
    private EditText hostInput;
    private TextView machineStatus, spindleValue, programValue, sequenceValue, cycleValue, feedValue, modeValue;
    private LinearLayout connectionPage, machinePage, settingsPage;
    private Button connectionTab, machineTab, settingsTab;
    private TextView settingsStatus;
    private int selectedTab;
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() { renderStatus(); statusHandler.postDelayed(this, 500); }
    };

    private void renderStatus() {
        MonitorService.DisplayState state = MonitorService.display;
        boolean current = state.isCurrent();
        statusTitle.setText(state.live && !current ? "No current data" : state.title);
        statusTitle.setTextColor(current ? ACCENT : state.active ? 0xffffc451 : TEXT);
        statusDetail.setText(state.live && !current ? "Waiting for a fresh machine reading." : state.detail);
        startButton.setEnabled(!state.active); startButton.setAlpha(state.active ? .45f : 1f);
        stopButton.setEnabled(state.active); stopButton.setAlpha(state.active ? 1f : .45f);
        hostInput.setEnabled(!state.active); hostInput.setAlpha(state.active ? .65f : 1f);
        machineStatus.setText(current ? state.detail : state.live ? "No current data" : state.title);
        machineStatus.setTextColor(current ? ACCENT : state.active ? 0xffffc451 : MUTED);
        spindleValue.setText(current ? value(state.spindle) : "—");
        programValue.setText(current ? value(state.program) : "—");
        sequenceValue.setText(current ? value(state.sequence) : "—");
        cycleValue.setText(current ? value(state.cycle) : "—");
        feedValue.setText(current ? value(state.feed) : "—");
        String codes = state.mode.isEmpty() ? state.status : state.status.isEmpty() ? state.mode : state.mode + " · " + state.status;
        modeValue.setText(current ? value(codes) : "—");
    }
    private String value(String text) { return text == null || text.trim().isEmpty() ? "—" : text; }
    @Override protected void onResume() {
        super.onResume(); statusHandler.post(refreshStatus);
        DataClient client = Wearable.getDataClient(this);
        client.addListener(this);
        client.getDataItems().addOnSuccessListener(items -> {
            try { for (DataItem item : items) if ("/settings-ack".equals(item.getUri().getPath())) receiveSettingsAck(item); }
            finally { items.release(); }
        });
        updateSettingsStatus();
    }
    @Override protected void onPause() { statusHandler.removeCallbacks(refreshStatus); Wearable.getDataClient(this).removeListener(this); super.onPause(); }
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent event : events)
            if (event.getType() == DataEvent.TYPE_CHANGED && "/settings-ack".equals(event.getDataItem().getUri().getPath()))
                receiveSettingsAck(event.getDataItem());
    }
    private void receiveSettingsAck(DataItem item) {
        DataMap map = DataMapItem.fromDataItem(item).getDataMap();
        String request = map.getString("request", ""), error = map.getString("error", "");
        SharedPreferences prefs = getSharedPreferences("watch_settings", 0);
        if (request.isEmpty() || !request.equals(prefs.getString("request", ""))) return;
        prefs.edit().putString("sync_status", error.isEmpty() ? "Updated on watch" : "Couldn't update watch. Try again.").apply();
        runOnUiThread(this::updateSettingsStatus);
    }
    private void updateSettingsStatus() {
        if (settingsStatus != null) settingsStatus.setText(getSharedPreferences("watch_settings", 0)
            .getString("sync_status", "Default layout · Changes sync to your watch"));
    }
    private void sendWatchSettings() {
        SharedPreferences prefs = getSharedPreferences("watch_settings", 0);
        String request = UUID.randomUUID().toString();
        prefs.edit().putString("request", request).putString("sync_status", "Waiting for watch…").apply();
        updateSettingsStatus();
        PutDataMapRequest map = PutDataMapRequest.create("/watch-settings");
        map.getDataMap().putString("request", request);
        for (String key : new String[]{"showProgram", "showSequence", "showCycle", "showSpindle", "showTool"})
            map.getDataMap().putBoolean(key, prefs.getBoolean(key, !key.equals("showTool")));
        Wearable.getDataClient(getApplicationContext()).putDataItem(map.asPutDataRequest().setUrgent())
            .addOnFailureListener(error -> {
                if (!request.equals(prefs.getString("request", ""))) return;
                prefs.edit().putString("sync_status", "Couldn't sync. Tap a setting to retry.").apply();
                runOnUiThread(this::updateSettingsStatus);
            });
    }

    private EditText signalNumber(LinearLayout parent, String title, int initial) {
        TextView label = text(title, 12, MUTED, true); add(parent, label, 8);
        EditText input = new EditText(this); input.setSingleLine(); input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(TEXT); input.setTextSize(16); input.setText(Integer.toString(initial));
        input.setPadding(dp(12), 0, dp(12), 0); input.setMinHeight(dp(42)); input.setBackground(shape(BG, BORDER, 12));
        add(parent, input, 3); return input;
    }
    private void showMachineSignals() {
        if (MonitorService.display.active) { Toast.makeText(this, "Stop monitoring before changing machine signals.", Toast.LENGTH_LONG).show(); return; }
        String host = hostInput.getText().toString().trim();
        if (!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) { hostInput.setError("Enter a valid IPv4 address first"); return; }
        for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255) { hostInput.setError("Enter a valid IPv4 address first"); return; }
        SharedPreferences config = getSharedPreferences("machine_signals", 0);
        boolean same = host.equals(config.getString("host", ""));
        LinearLayout content = column(); content.setPadding(dp(20), dp(10), dp(20), dp(12)); content.setBackgroundColor(SURFACE);
        TextView info = text("For " + host + ": enter your machine's verified PMC R-byte and bit numbers. The defaults are from one Doosan machine; confirm them against your own machine.", 13, TEXT, false); add(content, info, 0);
        EditText address = signalNumber(content, "R BYTE ADDRESS (decimal)", same ? config.getInt("address", 32) : 32);
        LinearLayout bits = new LinearLayout(this); add(content, bits, 4);
        LinearLayout r = column(), y = column(), g = column();
        bits.addView(r, new LinearLayout.LayoutParams(0, -2, 1)); bits.addView(y, new LinearLayout.LayoutParams(0, -2, 1)); bits.addView(g, new LinearLayout.LayoutParams(0, -2, 1));
        EditText red = signalNumber(r, "RED BIT", same ? config.getInt("red", 7) : 7);
        EditText yellow = signalNumber(y, "YELLOW BIT", same ? config.getInt("yellow", 4) : 4);
        EditText green = signalNumber(g, "GREEN BIT", same ? config.getInt("green", 6) : 6);
        CheckBox activeLow = new CheckBox(this); activeLow.setText("Lights use active-low bits"); activeLow.setTextColor(TEXT);
        activeLow.setChecked(same && config.getBoolean("active_low", false)); add(content, activeLow, 8);
        CheckBox tool = new CheckBox(this); tool.setText("Use D450 for spindle tool (verified machines only)"); tool.setTextColor(TEXT);
        tool.setChecked(same && config.getBoolean("tool_d450", false)); add(content, tool, 4);
        ScrollView scroller = new ScrollView(this); scroller.addView(content);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle("Machine signals").setView(scroller)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                SignalMapping mapping = new SignalMapping(Integer.parseInt(address.getText().toString()), Integer.parseInt(red.getText().toString()),
                        Integer.parseInt(yellow.getText().toString()), Integer.parseInt(green.getText().toString()), activeLow.isChecked());
                config.edit().putString("host", host).putInt("address", mapping.address).putInt("red", mapping.red)
                        .putInt("yellow", mapping.yellow).putInt("green", mapping.green).putBoolean("active_low", mapping.activeLow)
                        .putBoolean("tool_d450", tool.isChecked()).apply();
                dialog.dismiss(); Toast.makeText(this, "Signals saved for " + host, Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException error) {
                Toast.makeText(this, "Use an R address from 0–65535 and three different bits from 0–7.", Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
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
    private void selectTab(int tab) {
        selectedTab = tab;
        connectionPage.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        machinePage.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        Button[] buttons = {connectionTab, machineTab, settingsTab};
        for (int i = 0; i < buttons.length; i++) {
            boolean selected = i == tab;
            buttons[i].setBackground(shape(selected ? ACCENT : 0xff1c2b33, selected ? 0 : BORDER, 12));
            buttons[i].setTextColor(selected ? BG : TEXT);
            buttons[i].setSelected(selected);
        }
    }
    private TextView readout(LinearLayout parent, String label, int top) {
        LinearLayout panel = column(); panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.setBackground(shape(BG, BORDER, 12)); add(parent, panel, top);
        TextView heading = text(label, 10, MUTED, true); heading.setLetterSpacing(.06f); add(panel, heading, 0);
        TextView result = text("—", 17, TEXT, true); result.setMaxLines(1);
        result.setEllipsize(android.text.TextUtils.TruncateAt.END); add(panel, result, 3);
        return result;
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("selected_tab", selectedTab); super.onSaveInstanceState(out);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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

        LinearLayout tabs = new LinearLayout(this); add(page, tabs, 14);
        connectionTab = button("Connect", false); machineTab = button("Machine", false); settingsTab = button("Settings", false);
        connectionTab.setMinHeight(dp(42)); machineTab.setMinHeight(dp(42)); settingsTab.setMinHeight(dp(42));
        LinearLayout.LayoutParams leftTab = new LinearLayout.LayoutParams(0, dp(42), 1); leftTab.rightMargin = dp(4);
        LinearLayout.LayoutParams rightTab = new LinearLayout.LayoutParams(0, dp(42), 1); rightTab.leftMargin = dp(4);
        tabs.addView(connectionTab, leftTab); tabs.addView(machineTab, rightTab);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(0, dp(42), 1); settingsParams.leftMargin = dp(4);
        tabs.addView(settingsTab, settingsParams);

        connectionPage = column(); add(page, connectionPage, 12);
        machinePage = column(); add(page, machinePage, 12);
        settingsPage = column(); add(page, settingsPage, 12);
        LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackground(shape(SURFACE, BORDER, 22)); add(connectionPage, card, 0);
        TextView cardTitle = text("Connection", 18, TEXT, true); add(card, cardTitle, 0);
        LinearLayout statusCard = column(); statusCard.setPadding(dp(14), dp(10), dp(14), dp(10)); statusCard.setBackground(shape(BG, BORDER, 12)); add(card, statusCard, 12);
        statusTitle = text("Monitoring off", 16, TEXT, true); add(statusCard, statusTitle, 0);
        statusDetail = text("Start monitoring to connect to the machine.", 13, MUTED, false); statusDetail.setMaxLines(3); statusDetail.setEllipsize(android.text.TextUtils.TruncateAt.END); add(statusCard, statusDetail, 4);
        TextView hostLabel = text("MACHINE IP ADDRESS", 11, MUTED, true); hostLabel.setLetterSpacing(.08f); add(card, hostLabel, 14);
        EditText host = new EditText(this); host.setSingleLine(); host.setTextSize(17); host.setTextColor(TEXT); host.setHintTextColor(0xff7d9296);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); host.setImeOptions(EditorInfo.IME_ACTION_DONE);
        host.setText(getPreferences(0).getString("host", "192.168.1.1")); host.setHint("e.g. 192.168.1.1");
        host.setPadding(dp(16), 0, dp(16), 0); host.setMinHeight(dp(48)); host.setBackground(shape(BG, BORDER, 12)); host.setContentDescription("Machine IP address"); add(card, host, 6);
        TextView hint = text("Configure machine signals  →", 13, ACCENT, true); hint.setPadding(0, dp(6), 0, dp(6));
        hint.setClickable(true); hint.setOnClickListener(v -> showMachineSignals()); add(card, hint, 4);
        Button start = button("Start monitoring  →", true); add(card, start, 14);
        Button stop = button("Stop monitoring", false); add(card, stop, 8);
        startButton = start; stopButton = stop; hostInput = host;

        LinearLayout machineCard = column(); machineCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        machineCard.setBackground(shape(SURFACE, BORDER, 22)); add(machinePage, machineCard, 0);
        TextView machineTitle = text("Machine status", 18, TEXT, true); add(machineCard, machineTitle, 0);
        machineStatus = text("Monitoring off", 13, MUTED, true); machineStatus.setMaxLines(1);
        machineStatus.setEllipsize(android.text.TextUtils.TruncateAt.END); add(machineCard, machineStatus, 4);
        LinearLayout figures = new LinearLayout(this); add(machineCard, figures, 10);
        LinearLayout spindlePanel = column(); LinearLayout feedPanel = column();
        LinearLayout.LayoutParams spindleParams = new LinearLayout.LayoutParams(0, -2, 1); spindleParams.rightMargin = dp(4);
        LinearLayout.LayoutParams feedParams = new LinearLayout.LayoutParams(0, -2, 1); feedParams.leftMargin = dp(4);
        figures.addView(spindlePanel, spindleParams); figures.addView(feedPanel, feedParams);
        spindleValue = readout(spindlePanel, "SPINDLE SPEED", 0);
        feedValue = readout(feedPanel, "FEED RATE", 0);
        programValue = readout(machineCard, "CURRENT PROGRAM", 7);
        LinearLayout progress = new LinearLayout(this); add(machineCard, progress, 7);
        LinearLayout sequencePanel = column(), cyclePanel = column();
        LinearLayout.LayoutParams sequenceParams = new LinearLayout.LayoutParams(0, -2, 1); sequenceParams.rightMargin = dp(4);
        LinearLayout.LayoutParams cycleParams = new LinearLayout.LayoutParams(0, -2, 1); cycleParams.leftMargin = dp(4);
        progress.addView(sequencePanel, sequenceParams); progress.addView(cyclePanel, cycleParams);
        sequenceValue = readout(sequencePanel, "OP", 0);
        cycleValue = readout(cyclePanel, "CYCLE TIME", 0);
        sequenceValue.setTextSize(17);
        modeValue = readout(machineCard, "CONTROLLER CODES", 7);
        TextView limitations = text("Spindle and feed units unverified", 11, MUTED, false);
        add(machinePage, limitations, 8);
        LinearLayout settingsCard = column(); settingsCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        settingsCard.setBackground(shape(SURFACE, BORDER, 22)); add(settingsPage, settingsCard, 0);
        add(settingsCard, text("Watch face", 18, TEXT, true), 0);
        add(settingsCard, text("Choose the readouts below the clock.", 13, MUTED, false), 4);
        SharedPreferences watchPrefs = getSharedPreferences("watch_settings", 0);
        String[] keys = {"showProgram", "showSequence", "showCycle", "showSpindle", "showTool"};
        String[] titles = {"Program", "OP sequence", "Cycle time", "Spindle speed", "Current tool"};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            Switch row = new Switch(this); row.setText(titles[i]); row.setTextColor(TEXT); row.setTextSize(15);
            int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
            row.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, 0xffa4b7b9}));
            row.setTrackTintList(new ColorStateList(states, new int[]{0xff477d6b, 0xff364a50}));
            row.setPadding(dp(12), dp(6), dp(12), dp(6)); row.setMinHeight(dp(44));
            row.setBackground(shape(BG, BORDER, 12)); row.setChecked(watchPrefs.getBoolean(key, !key.equals("showTool")));
            row.setOnCheckedChangeListener((button, checked) -> { watchPrefs.edit().putBoolean(key, checked).apply(); sendWatchSettings(); });
            add(settingsCard, row, 8);
        }
        settingsStatus = text("Default layout · Changes sync to your watch", 12, MUTED, false);
        settingsStatus.setMaxLines(2); add(settingsCard, settingsStatus, 10); updateSettingsStatus();
        connectionTab.setOnClickListener(v -> selectTab(0)); machineTab.setOnClickListener(v -> selectTab(1)); settingsTab.setOnClickListener(v -> selectTab(2));
        selectTab(state != null ? Math.max(0, Math.min(2, state.getInt("selected_tab", 0))) : 0);
        renderStatus();

        TextView safety = text("Informational monitoring only · Not a safety indicator", 11, 0xffa4b7b9, false); add(page, safety, 10);

        start.setOnClickListener(v -> {
            String address = host.getText().toString().trim();
            if (!address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) { host.setError("Enter a valid IPv4 address"); return; }
            for (String part : address.split("\\.")) if (Integer.parseInt(part) > 255) { host.setError("Enter a valid IPv4 address"); return; }
            if (!address.equals(getSharedPreferences("machine_signals", 0).getString("host", ""))) {
                Toast.makeText(this, "Configure machine signals for this address first.", Toast.LENGTH_LONG).show();
                showMachineSignals(); return;
            }
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
