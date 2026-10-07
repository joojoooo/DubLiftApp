package org.dublift.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReleaseUpdateChecker {
    private static final String API_URL =
            "https://api.github.com/repos/joojoooo/DubLiftApp/releases/latest";
    private static final String RELEASES_URL = "https://github.com/joojoooo/DubLiftApp/releases";
    private static final Pattern VERSION = Pattern.compile("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)$");
    private static final Pattern CHANGELOG = Pattern.compile(
            "^##[ \\t]+(?:Changelog|What's Changed)[ \\t]*\\r?$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_SECTION = Pattern.compile("^#{1,2}[ \\t]+", Pattern.MULTILINE);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private ReleaseUpdateChecker() {}

    static final class Release {
        final String tag;
        final String changelog;

        Release(String tag, String changelog) {
            this.tag = tag;
            this.changelog = changelog;
        }

        String version() {
            return tag.startsWith("v") ? tag.substring(1) : tag;
        }

        String pageUrl() {
            return RELEASES_URL + "/tag/" + tag;
        }
    }

    // Called on a dedicated worker so the dashboard's health checks can continue.
    static Release check(String installedVersion) {
        if (installedVersion == null || !VERSION.matcher(installedVersion).matches()) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
            connection.setRequestProperty("User-Agent", "DubLiftApp/" + installedVersion);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) return null;
                    if (output.size() + count > MAX_RESPONSE_BYTES) return null;
                    output.write(buffer, 0, count);
                }
                String body = new String(output.toByteArray(), StandardCharsets.UTF_8);
                return findUpdate(new JSONObject(body), installedVersion);
            }
        } catch (IOException | JSONException ignored) {
            // Offline, rate limited, or malformed responses must not interrupt startup.
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static Release findUpdate(JSONObject response, String installedVersion) {
        String tag = response.optString("tag_name", "");
        if (response.optBoolean("draft") || response.optBoolean("prerelease")
                || !isNewer(tag, installedVersion)) return null;
        JSONArray assets = response.optJSONArray("assets");
        if (assets == null) return null;
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !"uploaded".equals(asset.optString("state"))) continue;
            String name = asset.optString("name");
            if (("DubLift-" + version + "-arm64-v8a-release.apk").equals(name)
                    || ("DubLift-" + version + "-armeabi-v7a-release.apk").equals(name)) {
                String body = response.isNull("body") ? "" : response.optString("body", "");
                return new Release(tag, changelog(body));
            }
        }
        return null;
    }

    static boolean isNewer(String releaseVersion, String installedVersion) {
        if (releaseVersion == null || installedVersion == null) return false;
        Matcher released = VERSION.matcher(releaseVersion);
        Matcher installed = VERSION.matcher(installedVersion);
        if (!released.matches() || !installed.matches()) return false;
        for (int i = 1; i <= 3; i++) {
            int comparison = new BigInteger(released.group(i)).compareTo(new BigInteger(installed.group(i)));
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }

    static String changelog(String body) {
        Matcher heading = CHANGELOG.matcher(body);
        if (heading.find()) {
            body = body.substring(heading.end());
            Matcher next = NEXT_SECTION.matcher(body);
            if (next.find()) body = body.substring(0, next.start());
        }
        // Use native text, including for releases created before the Changelog section existed.
        body = body.replaceAll("(?m)^#{1,6}[ \\t]+", "")
                .replaceAll("(?m)^[ \\t]*[-*][ \\t]+", "• ")
                .replaceAll("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)", "$1 ($2)")
                .replace("**", "").replace("`", "").trim();
        return body.isEmpty() ? "No changelog provided. Open the release page for details." : body;
    }
}
