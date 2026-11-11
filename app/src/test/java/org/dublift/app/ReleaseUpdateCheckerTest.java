package org.dublift.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ReleaseUpdateCheckerTest {
    @Test public void comparesVersionComponentsNumerically() {
        assertTrue(ReleaseUpdateChecker.isNewer("v0.0.10", "0.0.9"));
        assertTrue(ReleaseUpdateChecker.isNewer("v0.10.0", "0.9.99"));
        assertTrue(ReleaseUpdateChecker.isNewer("v1.0.0", "0.999.999"));
        assertFalse(ReleaseUpdateChecker.isNewer("v0.0.5", "0.0.5"));
        assertFalse(ReleaseUpdateChecker.isNewer("v0.9.99", "1.0.0"));
        assertFalse(ReleaseUpdateChecker.isNewer("v01.02.003", "1.2.3"));
    }

    @Test public void ignoresInvalidAndPrereleaseVersions() {
        for (String value : new String[]{null, "", "v1.2", "v1.2.3-beta", "latest", "v1.2.3/path"}) {
            assertFalse(ReleaseUpdateChecker.isNewer(value, "0.0.1"));
            assertFalse(ReleaseUpdateChecker.isNewer("v1.2.3", value));
        }
    }

    @Test public void requiresANewerStableReleaseWithAnUploadedApk() throws JSONException {
        JSONObject release = release();
        assertNotNull(findUpdate(release, "0.0.9"));
        assertNull(findUpdate(release, "0.0.10"));
        assertNull(findUpdate(release, "0.1.0"));
        release.put("draft", true);
        assertNull(findUpdate(release, "0.0.9"));
        release.put("draft", false).put("prerelease", true);
        assertNull(findUpdate(release, "0.0.9"));
        release.put("prerelease", false);
        release.getJSONArray("assets").getJSONObject(0).put("state", "new");
        assertNull(findUpdate(release, "0.0.9"));
        release.getJSONArray("assets").getJSONObject(0).put("name", "source.zip");
        assertNull(findUpdate(release, "0.0.9"));
        release.put("assets", new JSONArray());
        assertNull(findUpdate(release, "0.0.9"));
        assertNull(findUpdate(new JSONObject(), "0.0.9"));
    }

    @Test public void usesOnlyTheExpectedReleaseAssetAndTrustedDownloadUrl() throws JSONException {
        JSONObject release = release();
        release.getJSONArray("assets").getJSONObject(0)
                .put("name", "custom-app-build.apk");
        release.put("html_url", "https://example.com/download.apk");
        ReleaseUpdateChecker.Release update = findUpdate(release, "0.0.9");
        assertNotNull(update);
        assertNull(update.apkUrl);
        assertEquals("0.0.10", update.version());
        assertEquals("https://github.com/joojoooo/DubLiftApp/releases/tag/v0.0.10", update.pageUrl());
        assertEquals("• Fix playback\n• Add updates", update.changelog);
    }

    @Test public void extractsChangelogWithoutInstallationInstructionsOrComparisonSection() {
        String notes = "## Download\nInstall an APK.\n\n## Changelog\r\n\r\n"
                + "- Fix playback\r\n- Add updates\r\n\r\n## Full changelog\nComparison link.";
        assertEquals("• Fix playback\r\n• Add updates", ReleaseUpdateChecker.changelog(notes));
    }

    @Test public void supportsExistingGitHubNotesAndMissingChangelogs() {
        assertEquals("• Fix playback", ReleaseUpdateChecker.changelog(
                "## What's Changed\n* Fix playback\n\n## New Contributors\nSomeone"));
        assertEquals("Full Changelog: Changes (https://github.com/joojoooo/DubLiftApp/compare/v1...v2)",
                ReleaseUpdateChecker.changelog("**Full Changelog**: [Changes](https://github.com/joojoooo/DubLiftApp/compare/v1...v2)"));
        assertEquals("No changelog provided. Open the release page for details.", ReleaseUpdateChecker.changelog("\n  "));
    }

    @Test public void acceptsMissingBodyAndMalformedAssets() throws JSONException {
        JSONObject release = release();
        release.put("body", JSONObject.NULL);
        JSONObject apk = release.getJSONArray("assets").getJSONObject(0);
        release.put("assets", new JSONArray().put("invalid").put(JSONObject.NULL).put(apk));
        ReleaseUpdateChecker.Release update = findUpdate(release, "0.0.9");
        assertNotNull(update);
        assertEquals("No changelog provided. Open the release page for details.", update.changelog);
        release.remove("body");
        assertEquals(update.changelog, findUpdate(release, "0.0.9").changelog);
    }

    @Test public void selectsApkInDeviceAbiPreferenceOrder() throws JSONException {
        JSONObject release = release();
        release.getJSONArray("assets").put(asset("armeabi-v7a"));
        ReleaseUpdateChecker.Release update = ReleaseUpdateChecker.findUpdate(release, "0.0.9",
                new String[]{"arm64-v8a", "armeabi-v7a"});
        assertEquals("arm64-v8a", update.abi);
        assertEquals(downloadUrl("arm64-v8a"), update.apkUrl);
        update = ReleaseUpdateChecker.findUpdate(release, "0.0.9", new String[]{"armeabi-v7a"});
        assertEquals("armeabi-v7a", update.abi);
        assertEquals(downloadUrl("armeabi-v7a"), update.apkUrl);
        update = ReleaseUpdateChecker.findUpdate(release, "0.0.9", new String[]{"x86_64", "x86"});
        assertNotNull(update);
        assertNull(update.apkUrl);
        assertNull(update.abi);
    }

    @Test public void fallsBackToArmv7OnlyWhenSupported() throws JSONException {
        JSONObject release = release().put("assets", new JSONArray().put(asset("armeabi-v7a")));
        assertEquals("armeabi-v7a", ReleaseUpdateChecker.findUpdate(release, "0.0.9",
                new String[]{"arm64-v8a", "armeabi-v7a"}).abi);
        assertNull(ReleaseUpdateChecker.findUpdate(release, "0.0.9",
                new String[]{"arm64-v8a"}).apkUrl);
    }

    @Test public void rejectsWrongVersionDebugAssetsAndUntrustedUrls() throws JSONException {
        for (String url : new String[]{"", "http://github.com/joojoooo/DubLiftApp/releases/download/v0.0.10/DubLift-0.0.10-arm64-v8a-release.apk",
                "https://example.com/update.apk", downloadUrl("arm64-v8a") + "?redirect=1",
                downloadUrl("arm64-v8a").replace("/v0.0.10/", "/v0.0.11/")}) {
            JSONObject release = release();
            release.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url);
            assertNull(findUpdate(release, "0.0.9").apkUrl);
        }
        for (String name : new String[]{"DubLift-0.0.10-arm64-v8a-debug.apk", "DubLift-0.0.11-arm64-v8a-release.apk"}) {
            JSONObject release = release();
            release.getJSONArray("assets").getJSONObject(0).put("name", name);
            assertNull(findUpdate(release, "0.0.9").apkUrl);
        }
        JSONObject release = release();
        release.put("tag_name", "0.0.10");
        release.getJSONArray("assets").getJSONObject(0).put("browser_download_url",
                downloadUrl("arm64-v8a").replace("/v0.0.10/", "/0.0.10/"));
        assertNotNull(findUpdate(release, "0.0.9").apkUrl);
    }

    private static ReleaseUpdateChecker.Release findUpdate(JSONObject release, String installed) {
        return ReleaseUpdateChecker.findUpdate(release, installed, new String[]{"arm64-v8a", "armeabi-v7a"});
    }

    private static String downloadUrl(String abi) {
        return "https://github.com/joojoooo/DubLiftApp/releases/download/v0.0.10/DubLift-0.0.10-" + abi + "-release.apk";
    }

    private static JSONObject asset(String abi) throws JSONException {
        return new JSONObject().put("name", "DubLift-0.0.10-" + abi + "-release.apk")
                .put("state", "uploaded").put("browser_download_url", downloadUrl(abi));
    }

    private static JSONObject release() throws JSONException {
        return new JSONObject().put("tag_name", "v0.0.10").put("draft", false).put("prerelease", false)
                .put("body", "## Download\nInstructions.\n\n## Changelog\n\n- Fix playback\n- Add updates\n\n## Full changelog\nLink")
                .put("assets", new JSONArray().put(asset("arm64-v8a")));
    }
}
