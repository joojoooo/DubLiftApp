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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DubLiftService extends Service {
    static final String ACTION_START = "org.dublift.app.START";
    static final String ACTION_RESTART = "org.dublift.app.RESTART";
    static final String ACTION_STOP = "org.dublift.app.STOP";
    private static final String CHANNEL = "dublift_server";
    private static final int NOTIFICATION_ID = 7000;
    private static final int DEFAULT_CACHE_MB = 128;
    private static final int PORT = 7000;
    private static final String LISTEN_ADDRESS = "0.0.0.0:" + PORT;
    private static final String DASHBOARD_URL = "http://127.0.0.1:" + PORT;
    private static final String HEALTH_CHECK_URL = DASHBOARD_URL + "/healthz";

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
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "DubLift server", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the DubLift media addon available in the background");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
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
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_LOW)
                    .setOnlyAlertOnce(true);
        }
        Notification notification = builder
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
            Process process = null;
            try {
                if (healthy()) {
                    showForeground("Available at " + DASHBOARD_URL);
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
                // Shell redirection works on API 25; exec preserves process control for Stop/Restart.
                ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c",
                        "exec \"$@\" > server.log 2>&1", "dublift", go.getAbsolutePath(),
                        "-config", config.getAbsolutePath(), "-app-listen", LISTEN_ADDRESS,
                        "-app-ffmpeg", ffmpeg.getAbsolutePath(),
                        "-app-ffprobe", ffprobe.getAbsolutePath(),
                        "-default-cache-mb", String.valueOf(DEFAULT_CACHE_MB));
                builder.directory(dataDir);
                builder.environment().put("LD_LIBRARY_PATH", binaryDir.getAbsolutePath());
                builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                builder.redirectErrorStream(true);
                showForeground("Starting local server…");
                process = builder.start();
                server = process;
                long lastHealthy = System.currentTimeMillis();
                while (enabled && AndroidCompat.isAlive(process)) {
                    if (healthy()) {
                        lastHealthy = System.currentTimeMillis();
                        showForeground("Available at " + DASHBOARD_URL);
                    } else if (System.currentTimeMillis() - lastHealthy > 30000) {
                        process.destroy();
                        break;
                    }
                    AndroidCompat.waitFor(process, 5, TimeUnit.SECONDS);
                }
                if (enabled) showForeground("Server stopped; restarting…");
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                getSharedPreferences("runtime", MODE_PRIVATE).edit()
                        .putString("lastError", e.toString()).apply();
                if (enabled) showForeground("Server error; retrying…");
            } finally {
                if (process != null) {
                    if (AndroidCompat.isAlive(process)) process.destroy();
                    closeQuietly(process.getInputStream());
                    closeQuietly(process.getErrorStream());
                    closeQuietly(process.getOutputStream());
                    server = null;
                }
            }
            try { Thread.sleep(3000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) {}
    }

    static boolean healthy() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(HEALTH_CHECK_URL).openConnection();
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
