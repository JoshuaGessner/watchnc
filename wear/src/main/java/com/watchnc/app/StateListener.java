package com.watchnc.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import com.google.android.gms.wearable.*;
import com.watchnc.core.PatrolState;

/** Receives updates while the watch activity is closed. */
public class StateListener extends WearableListenerService {
    static final String[] TELEMETRY_KEYS = {"spindle", "program", "sequence", "cycle", "feed", "mode", "machineStatus", "tool"};
    private static final String[] FACE_KEYS = {"program", "sequence", "cycle", "spindle", "tool"};
    private static final String[] FACE_SETTINGS = {"showProgram", "showSequence", "showCycle", "showSpindle", "showTool"};
    // Keep a fresh complication before its 15-second validity expires even if values do not change.
    private static final long COMPLICATION_HEARTBEAT_MS = 5000;
    static String cleanTelemetry(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (cleaned.length() > 160) cleaned = cleaned.substring(0, 160);
        return cleaned;
    }
    static String cleanTool(String value) {
        String tool = cleanTelemetry(value);
        return tool.matches("T[0-9]+") ? tool.substring(1) : tool;
    }
    static String label(int value) {
        if (value < 0) return "UNKNOWN";
        StringBuilder s = new StringBuilder();
        if ((value & 0x80) != 0) s.append("RED ");
        if ((value & 0x10) != 0) s.append("YELLOW ");
        if ((value & 0x40) != 0) s.append("GREEN ");
        return s.length() == 0 ? "OFF" : s.toString().trim();
    }
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent e : events) {
            if (e.getType() != DataEvent.TYPE_CHANGED) continue;
            if ("/watch-settings".equals(e.getDataItem().getUri().getPath())) {
                DataMap settings = DataMapItem.fromDataItem(e.getDataItem()).getDataMap();
                android.content.SharedPreferences.Editor editor = getSharedPreferences("watch_settings", 0).edit();
                for (String key : FACE_SETTINGS)
                    editor.putBoolean(key, settings.getBoolean(key, !key.equals("showTool")));
                boolean saved = editor.commit();
                if (saved) {
                    getSharedPreferences("state", 0).edit().putLong("complicationRequestedAt", System.currentTimeMillis()).apply();
                    ComplicationDataSourceUpdateRequester.create(this, new ComponentName(this, MachineComplication.class)).requestUpdateAll();
                }
                PutDataMapRequest ack = PutDataMapRequest.create("/settings-ack");
                ack.getDataMap().putString("request", settings.getString("request", ""));
                ack.getDataMap().putString("error", saved ? "" : "Unable to save settings");
                ack.getDataMap().putLong("time", System.currentTimeMillis());
                Wearable.getDataClient(this).putDataItem(ack.asPutDataRequest().setUrgent());
                continue;
            }
            if (!"/machine-state".equals(e.getDataItem().getUri().getPath())) continue;
            DataMap map = DataMapItem.fromDataItem(e.getDataItem()).getDataMap();
            long time = map.getLong("time", 0), now = System.currentTimeMillis();
            android.content.SharedPreferences p = getSharedPreferences("state", 0);
            long previousTime = p.getLong("time", 0);
            if (!PatrolState.acceptSample(time, previousTime, now)) continue;
            int relay = map.getInt("relay", -1);
            int normalized = PatrolState.normalize(relay);
            int previous = PatrolState.fresh(previousTime, now) ? p.getInt("relay", -1) : -1;
            boolean updateComplication = previous != normalized;
            if (!updateComplication && normalized >= 0) {
                android.content.SharedPreferences settings = getSharedPreferences("watch_settings", 0);
                for (int i = 0; i < FACE_KEYS.length; i++) {
                    String key = FACE_KEYS[i];
                    if (settings.getBoolean(FACE_SETTINGS[i], !FACE_SETTINGS[i].equals("showTool")) &&
                            !p.getString(key, "").equals(key.equals("tool") ? cleanTool(map.getString(key, "")) : cleanTelemetry(map.getString(key, "")))) {
                        updateComplication = true;
                        break;
                    }
                }
                long sinceRequest = now - p.getLong("complicationRequestedAt", 0);
                if (sinceRequest < 0 || sinceRequest >= COMPLICATION_HEARTBEAT_MS) updateComplication = true;
            }
            android.content.SharedPreferences.Editor edit = p.edit().putLong("time", time).putInt("relay", normalized);
            edit.remove("operation"); // Discard program text stored by earlier development builds.
            for (String key : TELEMETRY_KEYS) edit.putString(key, normalized >= 0 ? (key.equals("tool") ? cleanTool(map.getString(key, "")) : cleanTelemetry(map.getString(key, ""))) : "");
            if (updateComplication) edit.putLong("complicationRequestedAt", now);
            edit.apply();
            if (updateComplication) ComplicationDataSourceUpdateRequester.create(this, new ComponentName(this, MachineComplication.class)).requestUpdateAll();
            if (PatrolState.changedKnown(previous, normalized)) alertChange(this, normalized);
        }
    }
    static void alertChange(Context context, int relay) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        // Reuse the existing default channel to preserve the user's channel settings.
        String channelId = "machine_changes_v2";
        NotificationChannel channel = new NotificationChannel(channelId, "Machine state changes", NotificationManager.IMPORTANCE_DEFAULT);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 250, 120, 250});
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, WatchActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Machine state changed")
            .setContentText(label(relay)).setLocalOnly(true).setAutoCancel(true)
            .setContentIntent(open).setTimeoutAfter(4000).build();
        manager.notify(42, notification);
    }
}
