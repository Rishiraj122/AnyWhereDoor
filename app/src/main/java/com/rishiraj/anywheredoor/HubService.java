package com.rishiraj.anywheredoor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Keeps the door open while the phone is locked or the app is in the background. */
public class HubService extends Service implements Http.Events {

    public static final String ACTION_STOP = "com.rishiraj.anywheredoor.STOP";
    private static final String CHANNEL = "door";
    private static final int NOTIF_ID = 1;

    private static volatile HubService instance;

    public static HubService get() { return instance; }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, HubService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, HubService.class));
    }

    public Shelf shelf;
    public Http http;
    public String phoneName;
    public volatile String error;
    public final List<String> log = Collections.synchronizedList(new ArrayList<>());

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private long lastNotify;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!http.active.isEmpty()) updateNotification(false);
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        phoneName = phoneName(this);
        shelf = new Shelf(this);
        http = new Http(this, shelf, phoneName, this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification("Starting…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (!http.running()) {
            try {
                http.start();
                error = null;
                instance = this;
                acquireLocks();
                ticker.postDelayed(tick, 1000);
                addLog("Door opened");
            } catch (Exception e) {
                error = "Couldn't open the door: " + e.getMessage();
                instance = this;
            }
        }
        updateNotification(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ticker.removeCallbacks(tick);
        http.stop();
        releaseLocks();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- events from the server

    @Override
    public void onTransferStart(Http.Active a) {
        updateNotification(true);
    }

    @Override
    public void onTransferEnd(Http.Active a, boolean ok, String err) {
        String what = a.upload ? "Received " + a.name : "Sent " + a.name;
        if (ok) addLog(what + " · " + MainActivity.size(a.size));
        else addLog((a.upload ? "Receiving " : "Sending ") + a.name + " failed — " + err);
        updateNotification(true);
    }

    private void addLog(String s) {
        String t = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
        log.add(0, t + "  " + s);
        while (log.size() > 50) log.remove(log.size() - 1);
    }

    // ---------------------------------------------------------------- notification

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Door open", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while other devices can reach this phone");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    /** Called often while transferring; only redraws every second. */
    public void updateNotification(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 1000) return;
        lastNotify = now;
        String text;
        if (error != null) {
            text = error;
        } else if (!http.active.isEmpty()) {
            Http.Active a = http.active.get(0);
            int pct = a.size > 0 ? (int) (a.done * 100 / a.size) : 0;
            text = (a.upload ? "Receiving " : "Sending ") + a.name + " · " + pct + "%";
            if (http.active.size() > 1) text += " (+" + (http.active.size() - 1) + " more)";
        } else {
            List<String> urls = addresses(http.port());
            text = urls.isEmpty() ? "Turn on your hotspot to let devices in" : "Open " + urls.get(0) + " on the other device";
        }
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification(text));
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, HubService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_door)
                .setContentTitle("AnyWhereDoor is open")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Close door", stop).build())
                .build();
    }

    // ---------------------------------------------------------------- locks

    private void acquireLocks() {
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnyWhereDoor:door");
        wake.setReferenceCounted(false);
        wake.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "AnyWhereDoor:door");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- addresses

    /**
     * The addresses other devices can use. The hotspot interface comes first
     * (ap0 / swlan0 / softap0 / wlan1 …); mobile-data interfaces are skipped.
     */
    public static List<String> addresses(int port) {
        List<String> hotspot = new ArrayList<>(), other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun")
                        || n.startsWith("dummy") || n.startsWith("v4-") || n.startsWith("ims")
                        || n.startsWith("p2p")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String url = "http://" + a.getHostAddress() + (port == 80 ? "" : ":" + port);
                    boolean ap = n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")
                            || n.contains("hotspot") || n.startsWith("wigig") || n.equals("wlan1") || n.equals("wlan2");
                    (ap ? hotspot : other).add(url);
                }
            }
        } catch (Exception ignored) { }
        hotspot.addAll(other);
        return hotspot;
    }

    static String phoneName(Context c) {
        String n = null;
        try { n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (Exception ignored) { }
        if (n == null || n.trim().isEmpty()) n = Build.MANUFACTURER + " " + Build.MODEL;
        return n.trim();
    }
}
