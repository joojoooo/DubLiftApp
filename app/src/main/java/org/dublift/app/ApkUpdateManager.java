package org.dublift.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

/** One app-owned download, persisted so rotation and process death cannot orphan an APK. */
final class ApkUpdateManager {
    static final String MIME_TYPE = "application/vnd.android.package-archive";
    private static final String DIRECTORY = "updates";
    private static final String FILE_NAME = "update.apk";

    private ApkUpdateManager() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("apkUpdate", Context.MODE_PRIVATE);
    }

    static boolean hasDownload(Context context) {
        return downloadId(context) != -1;
    }

    static long downloadId(Context context) {
        return preferences(context).getLong("id", -1);
    }

    static String tag(Context context) {
        return preferences(context).getString("tag", "");
    }

    static File apkFile(Context context) {
        File directory = context.getExternalFilesDir(DIRECTORY);
        return directory == null ? null : new File(directory, FILE_NAME);
    }

    static Uri apkUri(Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".updates/" + FILE_NAME);
    }

    static void download(Context context, ReleaseUpdateChecker.Release release) throws IOException {
        if (release.apkUrl == null) throw new IOException("No compatible APK is available");
        clear(context);
        File file = apkFile(context);
        if (file == null || (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
                || file.exists()) throw new IOException("Update storage is unavailable");
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        if (manager == null) throw new IOException("Android Download Manager is unavailable");
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle("DubLift " + release.version())
                .setDescription("Downloading app update (" + release.abi + ")")
                .setMimeType(MIME_TYPE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, DIRECTORY, FILE_NAME);
        long id = manager.enqueue(request);
        // Commit before returning: the app can be killed as soon as the installer replaces it.
        if (!preferences(context).edit().putLong("id", id).putString("tag", release.tag).commit()) {
            manager.remove(id);
            throw new IOException("Could not save the update download");
        }
    }

    static final class Progress {
        final int status;
        final int reason;
        final long downloaded;
        final long total;

        Progress(int status, int reason, long downloaded, long total) {
            this.status = status;
            this.reason = reason;
            this.downloaded = downloaded;
            this.total = total;
        }
    }

    static Progress progress(Context context) throws IOException {
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        long id = preferences(context).getLong("id", -1);
        if (manager == null || id == -1) throw new IOException("Download not found");
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Download not found");
            return new Progress(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
        }
    }

    @SuppressWarnings("deprecation") // GET_SIGNATURES returns the oldest proven signer after rotation, also on API 25.
    static void validateApk(Context context) throws IOException, PackageManager.NameNotFoundException {
        if (progress(context).status != DownloadManager.STATUS_SUCCESSFUL) {
            throw new IOException("The APK has not finished downloading");
        }
        File file = apkFile(context);
        if (file == null || !file.isFile()) throw new IOException("The downloaded APK is missing");
        PackageManager manager = context.getPackageManager();
        PackageInfo apk = manager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        String expected = tag(context);
        if (expected.startsWith("v")) expected = expected.substring(1);
        if (apk == null || !context.getPackageName().equals(apk.packageName)
                || !expected.equals(apk.versionName)
                || !ReleaseUpdateChecker.isNewer(apk.versionName, installed.versionName)
                || versionCode(apk) < versionCode(installed)) {
            throw new IOException("The downloaded APK is not a newer DubLift update. Delete it and try again.");
        }
        if (!sameSigners(apk.signatures, installed.signatures)) {
            throw new IOException("This APK uses a different signing key and cannot update this installation. "
                    + "A release APK cannot update a debug build.");
        }
        // Android's installer performs the final cryptographic and device compatibility checks.
    }

    private static boolean sameSigners(Signature[] first, Signature[] second) {
        return first != null && second != null && first.length > 0 && second.length > 0
                && new HashSet<>(Arrays.asList(first)).equals(new HashSet<>(Arrays.asList(second)));
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static void cleanupInstalled(Context context) {
        try {
            String installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            if (!hasDownload(context) || !ReleaseUpdateChecker.isNewer(tag(context), installed)) clear(context);
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    @SuppressLint("ApplySharedPref") // Persist cleanup before the replacement receiver's process can exit.
    static void clear(Context context) {
        long id = preferences(context).getLong("id", -1);
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        if (manager != null && id != -1) {
            try {
                manager.remove(id); // Cancels pending work and removes the system's download record and file.
            } catch (RuntimeException failure) {
                Log.w("DubLift", "Could not remove update from Download Manager", failure);
            }
        }
        File file = apkFile(context);
        if (file != null && file.exists() && !file.delete()) {
            Log.w("DubLift", "Could not delete update APK; cleanup will retry at next launch");
        }
        context.revokeUriPermission(apkUri(context), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        preferences(context).edit().clear().commit();
    }
}
