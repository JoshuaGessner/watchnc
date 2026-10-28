package com.watchnc.app;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import com.google.android.gms.wearable.*;
import com.watchnc.core.Focas;
import java.net.*;
import java.util.LinkedHashSet;

public class MonitorService extends Service {
    static final class DisplayState {
        final boolean active, live;
        final String title, detail;
        DisplayState(boolean active, boolean live, String title, String detail) {
            this.active = active; this.live = live; this.title = title; this.detail = detail;
        }
    }
    static volatile DisplayState display = new DisplayState(false, false, "Monitoring off", "Start monitoring to connect to the machine.");
    private volatile boolean running;
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
        if (!running) return;
        if (relay >= 0) {
            StringBuilder lights = new StringBuilder();
            if ((relay & 0x80) != 0) lights.append("Red  ");
            if ((relay & 0x10) != 0) lights.append("Yellow  ");
            if ((relay & 0x40) != 0) lights.append("Green  ");
            display = new DisplayState(true, true, "Connected", lights.length() == 0 ? "All patrol lights are off." : "Patrol lights: " + lights.toString().trim());
        } else {
            String title = status.startsWith("Waiting") ? "Waiting for Ethernet" : status.startsWith("Connecting") ? "Connecting" : "Connection problem";
            display = new DisplayState(true, false, title, status.startsWith("Waiting") ? "Connect the USB Ethernet adapter and check its network configuration." : status);
        }
        sendToWatch(relay, status);
        notifyStatus(status);
    }
    private void sendToWatch(int relay, String status) {
        PutDataMapRequest map = PutDataMapRequest.create("/machine-state");
        map.getDataMap().putInt("relay", relay); map.getDataMap().putString("status", status);
        map.getDataMap().putLong("time", System.currentTimeMillis());
        Wearable.getDataClient(this).putDataItem(map.asPutDataRequest().setUrgent());
    }
    private void runMonitor(String host) {
        while (running) {
            Network network = ethernet;
            if (network == null) { publish(-1, "Waiting for " + networkName); pause(3000); continue; }
            try {
                publish(-1, "Connecting to " + host + ":8193");
                Socket s = network.getSocketFactory().createSocket(); socket = s;
                s.connect(new InetSocketAddress(InetAddress.getByName(host), 8193), 3000); s.setSoTimeout(3000);
                try (Focas cnc = new Focas(s)) {
                    cnc.verifyController();
                    while (running && network.equals(ethernet)) {
                        int value = cnc.readRelay();
                        if (running) publish(value, "Live • R0032");
                        pause(3000);
                    }
                }
            } catch (Exception e) {
                if (running) publish(-1, "Disconnected: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally { closeSocket(); }
            if (running) pause(3000);
        }
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
