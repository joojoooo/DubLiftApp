package org.dublift.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DashboardDiagnosticsTest {
    @Test public void retainsOriginalExceptionAndCausesAfterLaterEventsAndRotation() {
        DashboardDiagnostics diagnostics = new DashboardDiagnostics();
        IllegalStateException failure = new IllegalStateException("provider failed",
                new UnsatisfiedLinkError("missing WebView library"));
        failure.addSuppressed(new IllegalArgumentException("bad provider metadata"));
        diagnostics.recordFailure("WebView creation failed", failure);
        for (int i = 0; i < 100; i++) diagnostics.record("later event " + i);

        DashboardDiagnostics restored = new DashboardDiagnostics();
        restored.restore(diagnostics.events(), diagnostics.firstFailure());
        String report = restored.report("Android API: 35");
        assertTrue(report.contains("IllegalStateException: provider failed"));
        assertTrue(report.contains("Caused by: java.lang.UnsatisfiedLinkError: missing WebView library"));
        assertTrue(report.contains("Suppressed: java.lang.IllegalArgumentException: bad provider metadata"));
        assertTrue(report.contains("DashboardDiagnosticsTest.java:"));
        assertTrue(report.contains("later event 99"));
        assertFalse(report.contains("later event 0\n"));
        assertEquals(diagnostics.events(), restored.events());
    }

    @Test public void boundsLargeReportsAndMarksTruncatedFailures() {
        DashboardDiagnostics diagnostics = new DashboardDiagnostics();
        diagnostics.recordFailure("x".repeat(100000), null);
        for (int i = 0; i < 100; i++) diagnostics.record("event " + i + " " + "y".repeat(5000));
        String report = diagnostics.report("Device: test");
        assertTrue(report.contains("[truncated]"));
        assertTrue(report.contains("event 99 "));
        assertTrue(report.length() < 51000);
    }

    @Test public void removesUrlCredentialsKeysAndPlaybackPathsFromSharedErrors() {
        DashboardDiagnostics diagnostics = new DashboardDiagnostics();
        diagnostics.recordFailure("Failed http://user:private-password@127.0.0.1:7000/?key=private-key#private-fragment", null);
        diagnostics.record("Source https://example.com/addon/private-path?token=private-token");
        diagnostics.record("http://127.0.0.1:7000/playback/private-playback-key/video");
        diagnostics.record("http://127.0.0.1:7000/playback/private-playback-key/video.js");
        diagnostics.record("api_key=private-api-key password=\"private password\" Authorization='private auth'");
        diagnostics.record("Authorization: Bearer private-bearer-token");
        diagnostics.record("Script: https://example.com/private-path/app.js?token=private-token");
        String report = diagnostics.report("Script: http://127.0.0.1:7000/app.js?v=private-version");
        assertTrue(report.contains("http://127.0.0.1:7000/"));
        assertTrue(report.contains("https://example.com/[path omitted]"));
        assertTrue(report.contains("http://127.0.0.1:7000/app.js"));
        assertTrue(report.contains("api_key=[redacted]"));
        assertFalse(report.contains("private"));
    }
}
