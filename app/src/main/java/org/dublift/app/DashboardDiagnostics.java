package org.dublift.app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, app-owned diagnostics; never reads private configuration or device-wide logs. */
final class DashboardDiagnostics {
    private static final int MAX_EVENTS = 40;
    private static final int MAX_EVENT_CHARS = 16384;
    private static final int MAX_HISTORY_CHARS = 32768;
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile(
            "(\\b(?:api[_-]?key|token|password|secret|authorization|playbackKey)[\"']?\\s*[:=]\\s*)"
                    + "(?:(?:Bearer|Basic)\\s+[^\\s,;}]+|\"[^\"]*\"|'[^']*'|[^\\s,;}]+)", Pattern.CASE_INSENSITIVE);

    private final ArrayDeque<String> events = new ArrayDeque<>();
    private String firstFailure;
    private int historyChars;

    synchronized void record(String message) {
        append(timestamp() + " " + sanitize(message));
    }

    synchronized void recordFailure(String message, Throwable failure) {
        StringWriter trace = new StringWriter();
        if (failure != null) failure.printStackTrace(new PrintWriter(trace));
        String event = limit(timestamp() + " " + sanitize(message + (failure == null ? "" : "\n" + trace)));
        if (firstFailure == null) firstFailure = event;
        append(event);
    }

    synchronized ArrayList<String> events() {
        return new ArrayList<>(events);
    }

    synchronized String firstFailure() {
        return firstFailure;
    }

    synchronized void restore(ArrayList<String> savedEvents, String savedFailure) {
        if (savedEvents != null) for (String event : savedEvents) if (event != null) append(event);
        if (savedFailure != null) firstFailure = limit(savedFailure);
    }

    synchronized String report(String environment) {
        StringBuilder report = new StringBuilder("DubLift dashboard debug info\nGenerated (UTC): ")
                .append(timestamp()).append("\n\n").append(sanitize(environment));
        report.append("\n\nFirst dashboard failure\n")
                .append(firstFailure == null ? "No failure captured." : firstFailure);
        report.append("\n\nRecent dashboard events (UTC)\n");
        for (String event : events) report.append(event).append('\n');
        return report.toString();
    }

    private void append(String event) {
        event = limit(event);
        events.addLast(event);
        historyChars += event.length();
        while (events.size() > MAX_EVENTS || historyChars > MAX_HISTORY_CHARS) {
            historyChars -= events.removeFirst().length();
        }
    }

    private static String limit(String text) {
        return text.length() <= MAX_EVENT_CHARS ? text
                : text.substring(0, MAX_EVENT_CHARS) + "\n[truncated]";
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    static String sanitize(String text) {
        Matcher urls = URL.matcher(text);
        StringBuffer safe = new StringBuffer();
        while (urls.find()) {
            String replacement = "[URL redacted]";
            try {
                URI uri = new URI(urls.group());
                if (uri.getHost() != null) {
                    String host = uri.getHost();
                    if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
                    String path = uri.getRawPath();
                    boolean local = "127.0.0.1".equals(host) || "localhost".equals(host);
                    boolean resource = path != null && !path.startsWith("/playback/")
                            && (path.endsWith(".js") || path.endsWith(".css"));
                    if (resource) path = path.substring(path.lastIndexOf('/'));
                    if (!resource && !"/".equals(path) && !(local && "/healthz".equals(path))) {
                        path = path == null || path.isEmpty() ? "" : "/[path omitted]";
                    }
                    replacement = uri.getScheme() + "://" + host
                            + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + (path == null ? "" : path);
                }
            } catch (URISyntaxException ignored) { }
            urls.appendReplacement(safe, Matcher.quoteReplacement(replacement));
        }
        urls.appendTail(safe);
        return SECRET.matcher(safe).replaceAll("$1[redacted]");
    }
}
