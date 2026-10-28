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
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import com.google.android.gms.wearable.*;
import com.watchnc.core.PatrolState;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Receives updates while the watch activity is closed. */
public class StateListener extends WearableListenerService {
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
            if ("/tone-request".equals(e.getDataItem().getUri().getPath())) {
                sendTones(DataMapItem.fromDataItem(e.getDataItem()).getDataMap());
                continue;
            }
            if ("/alert-sound".equals(e.getDataItem().getUri().getPath())) {
                receiveSound(DataMapItem.fromDataItem(e.getDataItem()).getDataMap());
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
            p.edit().putLong("time", time).putInt("relay", normalized).apply();
            ComplicationDataSourceUpdateRequester.create(this, new ComponentName(this, MachineComplication.class)).requestUpdateAll();
            if (PatrolState.changedKnown(previous, normalized)) alertChange(this, normalized);
        }
    }
    private void sendTones(DataMap request) {
        ArrayList<String> names = new ArrayList<>(), uris = new ArrayList<>();
        names.add("Watch default"); uris.add("default");
        String error = "";
        try {
            RingtoneManager manager = new RingtoneManager(this);
            manager.setType(RingtoneManager.TYPE_NOTIFICATION);
            try (Cursor cursor = manager.getCursor()) {
                while (cursor.moveToNext()) {
                    Uri uri = manager.getRingtoneUri(cursor.getPosition());
                    if (uri != null) {
                        names.add(cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX));
                        uris.add(uri.toString());
                    }
                }
            }
        } catch (Exception ex) {
            error = "Couldn't load watch tones";
        }
        PutDataMapRequest response = PutDataMapRequest.create("/tone-list");
        response.getDataMap().putString("request", request.getString("request", ""));
        response.getDataMap().putStringArrayList("names", names);
        response.getDataMap().putStringArrayList("uris", uris);
        response.getDataMap().putString("error", error);
        response.getDataMap().putLong("time", System.currentTimeMillis());
        Wearable.getDataClient(this).putDataItem(response.asPutDataRequest().setUrgent());
    }
    private void receiveSound(DataMap map) {
        String request = map.getString("request", ""), id = map.getString("id", "");
        String error = "";
        try {
            if (!id.equals("default")) {
                RingtoneManager manager = new RingtoneManager(this);
                manager.setType(RingtoneManager.TYPE_NOTIFICATION);
                boolean found = false;
                try (Cursor cursor = manager.getCursor()) {
                    while (cursor.moveToNext()) {
                        Uri uri = manager.getRingtoneUri(cursor.getPosition());
                        if (uri != null && uri.toString().equals(id)) { found = true; break; }
                    }
                }
                if (!found) throw new IllegalArgumentException("Tone is no longer available on the watch");
            }
            if (!getSharedPreferences("sound", 0).edit().putString("id", id).commit()) throw new IllegalStateException("Unable to save tone");
        } catch (Exception ex) {
            error = ex.getMessage() == null ? "Unable to save sound" : ex.getMessage();
        }
        PutDataMapRequest ack = PutDataMapRequest.create("/sound-ack");
        ack.getDataMap().putString("request", request); ack.getDataMap().putString("error", error);
        ack.getDataMap().putLong("time", System.currentTimeMillis());
        Wearable.getDataClient(this).putDataItem(ack.asPutDataRequest().setUrgent());
    }
    static void alertChange(Context context, int relay) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String soundId = context.getSharedPreferences("sound", 0).getString("id", "default");
        if (!soundId.equals("default") && !soundId.startsWith("content://")) soundId = "default";
        String channelId = soundId.equals("default") ? "machine_changes_v2" : "watch_tone_" + hash(soundId);
        NotificationChannel channel = new NotificationChannel(channelId, "Machine state changes", NotificationManager.IMPORTANCE_DEFAULT);
        Uri sound = soundId.equals("default") ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : Uri.parse(soundId);
        if (!soundId.equals("default")) {
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            channel.setSound(sound, attributes);
        }
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 250, 120, 250});
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, WatchActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Machine state changed")
            .setContentText(label(relay)).setLocalOnly(true).setAutoCancel(true)
            .setContentIntent(open).setSound(sound).setTimeoutAfter(4000).build();
        manager.notify(42, notification);
    }
    private static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
