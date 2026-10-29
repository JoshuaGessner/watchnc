package com.watchnc.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.Icon;
import android.text.TextPaint;
import android.text.TextUtils;
import androidx.wear.watchface.complications.data.*;
import androidx.wear.watchface.complications.datasource.*;
import java.time.Instant;
import com.watchnc.core.PatrolState;

public class MachineComplication extends ComplicationDataSourceService {
    private static String show(String value) { return value == null || value.isEmpty() ? "—" : value; }
    private static void line(Canvas canvas, Paint paint, String text, int y, int maxWidth) {
        CharSequence fitted = TextUtils.ellipsize(text, new TextPaint(paint), maxWidth, TextUtils.TruncateAt.END);
        canvas.drawText(fitted.toString(), 160, y, paint);
    }
    private Icon backgroundIcon(int relay, String spindle, String program, String sequence, String cycle, String tool) {
        Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int color = relay < 0 ? 0xff111b22 : (relay & 0x80) != 0 ? 0xff922832 :
                (relay & 0x10) != 0 ? 0xff805b0c : (relay & 0x40) != 0 ? 0xff0b6040 : 0xff24343a;
        canvas.drawColor(color);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setShader(new RadialGradient(160, 120, 220, new int[]{0x16ffffff, 0x00000000, 0x55000000}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 320, 320, shade);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xfff2f7f4); paint.setTextSize(15); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (relay < 0) canvas.drawText("NO CURRENT DATA", 160, 250, paint);
        else {
            android.content.SharedPreferences settings = getSharedPreferences("watch_settings", 0);
            java.util.ArrayList<String> rows = new java.util.ArrayList<>();
            if (settings.getBoolean("showProgram", true)) rows.add("PROGRAM  " + show(program));
            if (settings.getBoolean("showSequence", true)) rows.add("OP  " + show(sequence));
            if (settings.getBoolean("showCycle", true)) rows.add("CYCLE  " + show(cycle));
            if (settings.getBoolean("showSpindle", true)) rows.add("SPINDLE  " + show(spindle));
            if (settings.getBoolean("showTool", false)) rows.add("TOOL  " + show(StateListener.cleanTool(tool)));
            if ((relay & 0xd0) == 0) rows.add("LIGHTS OFF");
            paint.setTextSize(rows.size() > 3 ? 11 : 13);
            int step = rows.size() > 4 ? 13 : rows.size() > 3 ? 15 : 20;
            for (int i = 0; i < rows.size(); i++) line(canvas, paint, rows.get(i), 218 + i * step, 180);
        }
        return Icon.createWithBitmap(bitmap);
    }
    private ComplicationData backgroundData(int relay, long time, boolean preview, String spindle, String program, String sequence, String cycle, String tool) {
        String details = relay < 0 ? "" : "; program " + show(program) + "; spindle " + show(spindle) + "; OP sequence " + show(sequence) + "; tool " + show(tool);
        PlainComplicationText description = new PlainComplicationText.Builder("CNC patrol light " + StateListener.label(relay) + details).build();
        PhotoImageComplicationData.Builder b = new PhotoImageComplicationData.Builder(backgroundIcon(relay, spindle, program, sequence, cycle, tool), description);
        b.setTapAction(PendingIntent.getActivity(this, 0, new Intent(this, WatchActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (!preview && relay >= 0) b.setValidTimeRange(TimeRange.between(Instant.ofEpochMilli(time - 5000), Instant.ofEpochMilli(time + 15000)));
        return b.build();
    }
    private Icon towerIcon(int relay) {
        Bitmap bitmap = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap); c.drawColor(0xff080f14);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff23363b); c.drawRoundRect(41, 3, 91, 97, 13, 13, p);
        p.setColor(0xff101b20); c.drawRoundRect(45, 7, 87, 93, 10, 10, p);
        int[] masks = {0x80, 0x10, 0x40}, colors = {0xffff5e62, 0xffffc451, 0xff55df9c};
        for (int i = 0; i < 3; i++) {
            boolean on = relay >= 0 && (relay & masks[i]) != 0;
            p.setColor(on ? colors[i] : 0xff37494c);
            c.drawCircle(66, 22 + i * 28, 10, p);
            if (on) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); c.drawCircle(66, 22 + i * 28, 13, p); p.setStyle(Paint.Style.FILL); }
        }
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); p.setTextSize(10);
        p.setColor(relay < 0 ? 0xffffc451 : 0xffa4b7b9);
        c.drawText(relay < 0 ? "NO" : "CNC", 105, 43, p);
        c.drawText(relay < 0 ? "DATA" : "LIVE", 105, 58, p);
        return Icon.createWithBitmap(bitmap);
    }
    private ComplicationData imageData(int relay, long time, boolean preview) {
        PlainComplicationText description = new PlainComplicationText.Builder("CNC patrol light " + StateListener.label(relay)).build();
        SmallImage image = new SmallImage.Builder(towerIcon(relay), SmallImageType.ICON).build();
        SmallImageComplicationData.Builder b = new SmallImageComplicationData.Builder(image, description);
        b.setTapAction(PendingIntent.getActivity(this, 0, new Intent(this, WatchActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (!preview && relay >= 0) b.setValidTimeRange(TimeRange.between(Instant.ofEpochMilli(time - 5000), Instant.ofEpochMilli(time + 15000)));
        return b.build();
    }
    private ComplicationData data(int relay, long time, boolean preview) {
        String text = PatrolState.shortLabel(relay);
        PlainComplicationText label = new PlainComplicationText.Builder(text).build();
        PlainComplicationText description = new PlainComplicationText.Builder("CNC patrol light " + StateListener.label(relay)).build();
        ShortTextComplicationData.Builder b = new ShortTextComplicationData.Builder(label, description);
        b.setTapAction(PendingIntent.getActivity(this, 0, new Intent(this, WatchActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        // Once the sample expires, the system must stop showing its color even if the app never runs again.
        if (!preview && relay >= 0) b.setValidTimeRange(TimeRange.between(Instant.ofEpochMilli(time - 5000), Instant.ofEpochMilli(time + 15000)));
        return b.build();
    }
    @Override public ComplicationData getPreviewData(ComplicationType type) {
        return type == ComplicationType.PHOTO_IMAGE ? backgroundData(0x40, 0, true, "", "", "", "", "") :
            type == ComplicationType.SMALL_IMAGE ? imageData(0x40, 0, true) : type == ComplicationType.SHORT_TEXT ? data(0x40, 0, true) : null;
    }
    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        android.content.SharedPreferences p = getSharedPreferences("state", 0);
        long time = p.getLong("time", 0);
        int relay = !PatrolState.fresh(time, System.currentTimeMillis()) ? -1 : p.getInt("relay", -1);
        ComplicationData result = request.getComplicationType() == ComplicationType.PHOTO_IMAGE ? backgroundData(relay, time, false,
                relay < 0 ? "" : p.getString("spindle", ""), relay < 0 ? "" : p.getString("program", ""), relay < 0 ? "" : p.getString("sequence", ""), relay < 0 ? "" : p.getString("cycle", ""), relay < 0 ? "" : p.getString("tool", "")) :
            request.getComplicationType() == ComplicationType.SMALL_IMAGE ? imageData(relay, time, false) :
            request.getComplicationType() == ComplicationType.SHORT_TEXT ? data(relay, time, false) : null;
        try { listener.onComplicationData(result); }
        catch (android.os.RemoteException ignored) { }
    }
}
