package org.dublift.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class AndroidCompat {
    private AndroidCompat() {}

    static void startServer(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static String readUtf8(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static boolean isAlive(Process process) {
        if (Build.VERSION.SDK_INT >= 26) return process.isAlive();
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    static boolean waitFor(Process process, long timeout, TimeUnit unit) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= 26) return process.waitFor(timeout, unit);
        long duration = unit.toNanos(timeout);
        long started = System.nanoTime();
        while (true) {
            if (Thread.interrupted()) throw new InterruptedException();
            if (!isAlive(process)) return true;
            long remaining = duration - (System.nanoTime() - started);
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
        }
    }
}
