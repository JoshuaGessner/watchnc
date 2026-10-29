package com.watchnc.app;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import com.google.android.gms.wearable.*;
import com.watchnc.core.Focas;
import com.watchnc.core.PatrolState;
import com.watchnc.core.UpdateGate;
import com.watchnc.core.SignalMapping;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.util.LinkedHashSet;

public class MonitorService extends Service {
    static final class DisplayState {
        final boolean active, live;
        final String title, detail;
        final String spindle, program, sequence, cycle, feed, mode, status;
        final long sampleTime;
        DisplayState(boolean active, boolean live, String title, String detail) {
            this(active, live, title, detail, new Readouts(), 0);
        }
        DisplayState(boolean active, boolean live, String title, String detail, Readouts values, long sampleTime) {
            this.active = active; this.live = live; this.title = title; this.detail = detail; this.sampleTime = sampleTime;
            spindle = values.spindle; program = values.program; sequence = values.sequence; cycle = values.cycle;
            feed = values.feed; mode = values.mode; status = values.machineStatus;
        }
        boolean isCurrent() { return live && PatrolState.fresh(sampleTime, System.currentTimeMillis()); }
    }
    private static final class Readouts {
        String spindle = "", program = "", sequence = "", cycle = "", feed = "", mode = "", machineStatus = "", tool = "";
    }
    @FunctionalInterface private interface Read<T> { T get() throws IOException; }
    private static final class OptionalRead<T> {
        private boolean unsupported;
        private boolean reported;
        private final String label;
        OptionalRead() { this(""); }
        OptionalRead(String label) { this.label = label; }
        private void report(String reason) {
            if (!reported && !label.isEmpty()) android.util.Log.i("WatchNC", label + " unavailable: " + reason);
            reported = true;
        }
        T get(Read<T> read) throws IOException {
            if (unsupported) return null;
            try { T value = read.get(); reported = false; return value; }
            catch (Focas.UnsupportedReadException e) { unsupported = true; report("unsupported by controller"); return null; }
            catch (Focas.ControllerException e) { report("controller error " + e.code); return null; }
            catch (Focas.InvalidTelemetryException e) { report(e.getMessage()); return null; }
        }
    }
    private static final class Telemetry {
        final OptionalRead<Double> spindle = new OptionalRead<>(), feed = new OptionalRead<>();
        final OptionalRead<int[]> program = new OptionalRead<>(), status = new OptionalRead<>();
        final OptionalRead<Long> sequence = new OptionalRead<>("Sequence");
        final OptionalRead<Long> cycle = new OptionalRead<>("Cycle timer");
        final OptionalRead<Long> tool = new OptionalRead<>("Spindle tool");
        final OptionalRead<Long> alarm = new OptionalRead<>();
        Readouts read(Focas cnc, boolean toolEnabled) throws IOException {
            Readouts v = new Readouts();
            Double s = spindle.get(cnc::readSpindle), f = feed.get(cnc::readFeed);
            if (s != null) v.spindle = number(s);
            if (f != null) v.feed = number(f);
            int[] p = program.get(cnc::readProgram);
            if (p != null && p[0] > 0) {
                v.program = "O" + p[0];
                if (p[1] > 0 && p[1] != p[0]) v.program += " · Main O" + p[1];
            }
            int[] state = status.get(cnc::readStatus);
            if (state != null) { v.mode = "Code " + state[0]; v.machineStatus = "Run code " + state[1]; }
            Long a = alarm.get(cnc::readAlarm);
            if (a != null && a != 0) v.machineStatus = (v.machineStatus.isEmpty() ? "" : v.machineStatus + " · ") + "Alarm reported";
            Long sequenceNumber = sequence.get(cnc::readSequence);
            if (sequenceNumber != null) v.sequence = "N" + sequenceNumber;
            Long elapsed = cycle.get(cnc::readCycleTimeMillis);
            if (elapsed != null) {
                long seconds = elapsed / 1000;
                v.cycle = String.format(java.util.Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
            }
            Long toolNumber = toolEnabled ? tool.get(cnc::readSpindleTool) : null;
            if (toolNumber != null) v.tool = Long.toString(toolNumber);
            return v;
        }
        private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    }
    static volatile DisplayState display = new DisplayState(false, false, "Monitoring off", "Start monitoring to connect to the machine.");
    private volatile boolean running;
    private final UpdateGate watchUpdates = new UpdateGate(5000);
    private volatile Network ethernet;
    private final LinkedHashSet<Network> ethernetNetworks = new LinkedHashSet<>();
    private volatile Socket socket;
    private Thread worker;
    private ConnectivityManager manager;
    private ConnectivityManager.NetworkCallback callback;
    private String networkName = "Ethernet";
    private boolean registered;
    private boolean startFailed;
    private static final String CHANNEL = "monitor";
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Machine monitoring", NotificationManager.IMPORTANCE_LOW));
        manager = getSystemService(ConnectivityManager.class);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                synchronized (ethernetNetworks) {
                    ethernetNetworks.add(n);
                    if (ethernet == null) ethernet = n;
                }
            }
            @Override public void onLost(Network n) {
                synchronized (ethernetNetworks) {
                    ethernetNetworks.remove(n);
                    if (n.equals(ethernet)) {
                        ethernet = ethernetNetworks.isEmpty() ? null : ethernetNetworks.iterator().next();
                        closeSocket();
                    }
                }
            }
        };
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        String host = intent == null ? null : intent.getStringExtra("host");
        if (host == null) { stopSelf(); return START_NOT_STICKY; }
        if (running) { notifyStatus("Stop monitoring before changing the address"); return START_NOT_STICKY; }
        running = true;
        display = new DisplayState(true, false, "Starting", "Preparing the Ethernet connection.");
        try {
            startForeground(1, notification("Starting monitoring"));
            NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            manager.registerNetworkCallback(request, callback); registered = true;
            worker = new Thread(() -> runMonitor(host), "cnc-monitor"); worker.start();
        } catch (RuntimeException e) {
            startFailed = true;
            display = new DisplayState(false, false, "Could not start", e.getMessage() == null ? "Unable to start monitoring." : e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    private Notification notification(String status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setContentTitle("WatchNC monitoring").setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi).setLocalOnly(true).setOngoing(true).build();
    }
    private void notifyStatus(String message) { getSystemService(NotificationManager.class).notify(1, notification(message)); }
    private synchronized void publish(int relay, String status) {
        publish(relay, status, new Readouts(), System.currentTimeMillis());
    }
    private synchronized void publish(int relay, String status, Readouts values, long sampleTime) {
        if (!running) return;
        if (relay >= 0) {
            StringBuilder lights = new StringBuilder();
            if ((relay & 0x80) != 0) lights.append("Red  ");
            if ((relay & 0x10) != 0) lights.append("Yellow  ");
            if ((relay & 0x40) != 0) lights.append("Green  ");
            display = new DisplayState(true, true, "Connected", lights.length() == 0 ? "All patrol lights are off." : "Patrol lights: " + lights.toString().trim(), values, sampleTime);
        } else {
            String title = status.startsWith("Waiting") ? "Waiting for Ethernet" : status.startsWith("Connecting") ? "Connecting" : "Connection problem";
            display = new DisplayState(true, false, title, status.startsWith("Waiting") ? "Connect the USB Ethernet adapter and check its network configuration." : status);
        }
        sendToWatch(relay, status, values, sampleTime);
        notifyStatus(status);
    }
    private void sendToWatch(int relay, String status) {
        sendToWatch(relay, status, new Readouts(), System.currentTimeMillis());
    }
    private void sendToWatch(int relay, String status, Readouts values, long sampleTime) {
        long attempt = watchUpdates.begin(java.util.Arrays.asList(relay, status, values.spindle, values.program,
            values.sequence, values.feed, values.cycle, values.mode, values.machineStatus, values.tool), SystemClock.elapsedRealtime());
        if (attempt == 0) return;
        PutDataMapRequest map = PutDataMapRequest.create("/machine-state");
        map.getDataMap().putInt("relay", relay); map.getDataMap().putString("status", status);
        map.getDataMap().putString("spindle", values.spindle); map.getDataMap().putString("program", values.program);
        map.getDataMap().putString("sequence", values.sequence); map.getDataMap().putString("feed", values.feed);
        map.getDataMap().putString("cycle", values.cycle);
        map.getDataMap().putString("tool", values.tool);
        map.getDataMap().putString("mode", values.mode); map.getDataMap().putString("machineStatus", values.machineStatus);
        map.getDataMap().putLong("time", sampleTime);
        Wearable.getDataClient(this).putDataItem(map.asPutDataRequest().setUrgent())
            .addOnFailureListener(error -> watchUpdates.failed(attempt));
    }
    private void runMonitor(String host) {
        while (running) {
            Network network = ethernet;
            if (network == null) { publish(-1, "Waiting for " + networkName); pause(3000); continue; }
            try {
                publish(-1, "Connecting to " + host + ":8193");
                Socket s = network.getSocketFactory().createSocket(); socket = s;
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(InetAddress.getByName(host), 8193), 3000); s.setSoTimeout(3000);
                try (Focas cnc = new Focas(s)) {
                    cnc.verifyController();
                    SharedPreferences config = getSharedPreferences("machine_signals", 0);
                    boolean configured = host.equals(config.getString("host", ""));
                    if (!configured) throw new IOException("Set up machine signals for this IP in the phone app before monitoring.");
                    SignalMapping mapping = new SignalMapping(config.getInt("address", 32), config.getInt("red", 7),
                            config.getInt("yellow", 4), config.getInt("green", 6), config.getBoolean("active_low", false));
                    boolean toolEnabled = config.getBoolean("tool_d450", false);
                    Telemetry telemetry = new Telemetry();
                    while (running && network.equals(ethernet)) {
                        long started = SystemClock.elapsedRealtime();
                        int value = mapping.normalize(cnc.readRelay(mapping.address));
                        long sampled = System.currentTimeMillis();
                        Readouts values = telemetry.read(cnc, toolEnabled);
                        if (running) publish(value, "Live • R" + mapping.address, values, sampled);
                        if (running) pause(Math.max(0, 1000 - (SystemClock.elapsedRealtime() - started)));
                    }
                }
            } catch (Exception e) {
                if (running) publish(-1, connectionFailure(e));
            } finally { closeSocket(); }
            if (running) pause(3000);
        }
    }
    private String connectionFailure(Exception error) {
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (reason.contains("EPERM")) {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                return "Ethernet access denied. Your VPN may be blocking it; check VPN settings or exclude WatchNC.";
            return "Ethernet access denied. Check phone network permissions and VPN settings.";
        }
        return "Disconnected: " + reason;
    }
    private void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
    private void closeSocket() { Socket s = socket; socket = null; if (s != null) try { s.close(); } catch (Exception ignored) {} }
    @Override public synchronized void onDestroy() {
        running = false; closeSocket(); if (worker != null) worker.interrupt();
        if (registered) manager.unregisterNetworkCallback(callback);
        if (!startFailed) display = new DisplayState(false, false, "Monitoring off", "Monitoring stopped. Start again when you're ready.");
        sendToWatch(-1, "Monitoring stopped");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
