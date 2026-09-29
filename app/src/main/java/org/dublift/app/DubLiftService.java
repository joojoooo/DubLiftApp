package org.dublift.app;

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

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DubLiftService extends Service {
    static final String ACTION_START = "org.dublift.app.START";
    static final String ACTION_RESTART = "org.dublift.app.RESTART";
    static final String ACTION_STOP = "org.dublift.app.STOP";
    private static final String CHANNEL = "dublift_server";
    private static final int NOTIFICATION_ID = 7000;

    private final AtomicBoolean workerStarted = new AtomicBoolean(false);
    private volatile boolean enabled;
    private volatile Process server;
    private Thread worker;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("enabled", false);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "DubLift server", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the DubLift media addon available in the background");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null && !isEnabled(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            enabled = false;
            getSharedPreferences("runtime", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        enabled = true;
        getSharedPreferences("runtime", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        showForeground("Starting local server…");
        acquireLocks();
        if (ACTION_RESTART.equals(action) && server != null) server.destroy();
        if (workerStarted.compareAndSet(false, true)) {
            worker = new Thread(this::runServerLoop, "dublift-server-watchdog");
            worker.start();
        }
        return START_STICKY;
    }

    private void showForeground(String status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, DubLiftService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("DubLift")
                .setContentText(status)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopIntent).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireLocks() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (cpuLock == null && power != null) {
            cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DubLift:server");
            cpuLock.setReferenceCounted(false);
            cpuLock.acquire();
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiLock == null && wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DubLift:network");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void runServerLoop() {
        while (enabled && !Thread.currentThread().isInterrupted()) {
            try {
                if (healthy()) {
                    showForeground("Available at http://127.0.0.1:7000");
                    while (enabled && healthy()) Thread.sleep(5000);
                    continue;
                }
                File binaryDir = new File(getApplicationInfo().nativeLibraryDir);
                File go = new File(binaryDir, "libdublift.so");
                File ffmpeg = new File(binaryDir, "libffmpeg.so");
                File ffprobe = new File(binaryDir, "libffprobe.so");
                if (!go.canExecute() || !ffmpeg.canExecute() || !ffprobe.canExecute()) {
                    throw new IllegalStateException("One or more bundled executables are missing");
                }
                File dataDir = new File(getFilesDir(), "dublift");
                if (!dataDir.exists() && !dataDir.mkdirs()) {
                    throw new IllegalStateException("Could not create private data directory");
                }
                File config = new File(dataDir, "config.json");
                prepareConfig(config, ffmpeg, ffprobe);
                ProcessBuilder builder = new ProcessBuilder(go.getAbsolutePath(),
                        "-config", config.getAbsolutePath(), "-listen", "0.0.0.0:7000");
                builder.directory(dataDir);
                builder.environment().put("LD_LIBRARY_PATH", binaryDir.getAbsolutePath());
                builder.redirectErrorStream(true);
                builder.redirectOutput(new File(dataDir, "server.log"));
                showForeground("Starting local server…");
                server = builder.start();
                long lastHealthy = System.currentTimeMillis();
                while (enabled && server.isAlive()) {
                    if (healthy()) {
                        lastHealthy = System.currentTimeMillis();
                        showForeground("Available at http://127.0.0.1:7000");
                    } else if (System.currentTimeMillis() - lastHealthy > 30000) {
                        server.destroy();
                        break;
                    }
                    server.waitFor(5, TimeUnit.SECONDS);
                }
                if (server.isAlive()) server.destroy();
                server = null;
                if (enabled) showForeground("Server stopped; restarting…");
            } catch (Exception e) {
                getSharedPreferences("runtime", MODE_PRIVATE).edit()
                        .putString("lastError", e.toString()).apply();
                if (enabled) showForeground("Server error; retrying…");
            }
            try { Thread.sleep(3000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void prepareConfig(File config, File ffmpeg, File ffprobe) throws Exception {
        JSONObject settings = config.exists()
                ? new JSONObject(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8))
                : new JSONObject();
        settings.put("ffmpeg", ffmpeg.getAbsolutePath());
        settings.put("ffprobe", ffprobe.getAbsolutePath());
        settings.put("listen", "0.0.0.0:7000");
        if (!config.exists()) settings.put("cacheMB", 128);
        File temporary = new File(config.getParentFile(), "config.json.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(settings.toString(2).getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        if (!temporary.renameTo(config)) throw new IllegalStateException("Could not save config");
    }

    static boolean healthy() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:7000/healthz").openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override public void onDestroy() {
        enabled = false;
        if (server != null) server.destroy();
        if (worker != null) worker.interrupt();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
