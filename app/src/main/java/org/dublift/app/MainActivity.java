package org.dublift.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.security.NetworkSecurityPolicy;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String DASHBOARD = "http://127.0.0.1:7000/";
    private static final long UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final int SURFACE = Color.rgb(20, 34, 51);
    private static final int ACCENT = Color.rgb(79, 222, 183);
    private static final int MUTED = Color.rgb(211, 224, 235);
    private static final int STOPPED = Color.rgb(244, 133, 139);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService checks = Executors.newSingleThreadExecutor();
    private final ExecutorService updateChecks = Executors.newSingleThreadExecutor();
    private final DashboardDiagnostics diagnostics = new DashboardDiagnostics();
    private ReleaseUpdateChecker.Release availableUpdate;
    private AlertDialog updateDialog;
    private String installedVersion;
    private boolean updateCheckComplete;
    private boolean updateChecking;
    private boolean manualUpdateCheck;
    private ApkUpdateManager.Progress downloadProgress;
    private AlertDialog downloadDialog;
    private AlertDialog updateMessageDialog;
    private String pendingUpdateMessage;
    private AlertDialog installPermissionDialog;
    private ProgressBar downloadBar;
    private TextView downloadStatus;
    private boolean downloadDialogReady;
    private boolean downloadUiHidden;
    private boolean downloadPolling;
    private boolean autoInstallDownload;
    private boolean installPreparing;
    private boolean awaitingInstallPermission;
    private boolean installerOpen;
    private static final int INSTALL_PERMISSION_REQUEST = 2;
    private static final int INSTALL_APK_REQUEST = 3;
    private final Runnable downloadPoll = this::pollDownload;
    private boolean resumed;
    private WebView webView;
    private FrameLayout dashboardPage;
    private TextView fallbackAddress;
    private TextView fallbackStatus;
    private Button copyLanButton;
    private String lanDashboardUrl;
    private Boolean lastServerHealthy;
    private LinearLayout dock;
    private LinearLayout serverToggle;
    private GradientDrawable statusShape;
    private View statusControl;
    private View firstDockAction;
    private boolean dockHidden;
    private int scrollTravel;
    private boolean dashboardLoaded;
    private boolean tvMode;
    private boolean active;
    private boolean serverEnabled = true;
    private String serverStatus = "Server starting";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            diagnostics.restore(state.getStringArrayList("dashboardDiagnosticEvents"),
                    state.getString("dashboardFirstFailure"));
        }
        diagnostics.record("Activity created");
        tvMode = isTelevision();
        diagnostics.record("Television navigation: " + tvMode);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(SURFACE);

        FrameLayout page = new FrameLayout(this);
        dashboardPage = page;
        page.setBackgroundColor(SURFACE);
        if (Build.VERSION.SDK_INT >= 35) {
            page.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
        }

        createDashboard(page);

        createDock();
        FrameLayout.LayoutParams dockLayout = new FrameLayout.LayoutParams(
                -2, dp(62), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockLayout.bottomMargin = dp(14);
        page.addView(dock, dockLayout);
        setContentView(page);

        if (state != null) {
            updateCheckComplete = state.getBoolean("updateCheckComplete");
            manualUpdateCheck = state.getBoolean("manualUpdateCheck");
            autoInstallDownload = state.getBoolean("autoInstallDownload");
            awaitingInstallPermission = state.getBoolean("awaitingInstallPermission");
            installerOpen = state.getBoolean("installerOpen");
            downloadUiHidden = state.getBoolean("downloadUiHidden");
            pendingUpdateMessage = state.getString("updateMessage");
            String tag = state.getString("updateTag");
            if (tag != null) {
                availableUpdate = new ReleaseUpdateChecker.Release(tag, state.getString("updateChangelog", ""),
                        state.getString("updateApkUrl"), state.getString("updateAbi"));
            }
        }
        try {
            installedVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            updateCheckComplete = true;
        }
        ApkUpdateManager.cleanupInstalled(this);
        if (!updateCheckComplete && !ApkUpdateManager.hasDownload(this)) checkForUpdate(manualUpdateCheck);

        startServer(DubLiftService.ACTION_START);
        active = true;
        checkServer();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            maybeRequestBatteryExemption();
        }
    }

    private boolean isTelevision() {
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        PackageManager packageManager = getPackageManager();
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
                || packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION);
    }

    @SuppressLint("WebViewApiAvailability") // API 25 uses guarded construction without an AndroidX dependency.
    private void createDashboard(FrameLayout page) {
        try {
            boolean supported = getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW);
            diagnostics.record("WebView feature declared: " + supported);
            if (!supported) {
                diagnostics.recordFailure("Device does not declare android.software.webview", null);
            } else if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo provider = WebView.getCurrentWebViewPackage();
                diagnostics.record("Selected WebView provider at startup: " + describePackage(provider));
                if (provider == null) {
                    diagnostics.recordFailure("Android reports no selected WebView provider", null);
                } else {
                    webView = createWebView();
                }
            } else {
                diagnostics.record("Provider query unavailable on API 25; attempting WebView construction");
                webView = createWebView();
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // API 25 has no provider-query API; construction also catches broken providers.
            Log.w("DubLift", "Dashboard WebView unavailable", unavailable);
            diagnostics.recordFailure("WebView creation failed", unavailable);
        }
        if (webView == null) {
            createDashboardFallback(page, true);
        } else {
            diagnostics.record("WebView created and configured");
            page.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private WebView createWebView() {
        WebView dashboard = new WebView(this);
        dashboard.setBackgroundColor(SURFACE);
        dashboard.getSettings().setJavaScriptEnabled(true);
        dashboard.getSettings().setDomStorageEnabled(true);
        dashboard.getSettings().setAllowFileAccess(false);
        dashboard.getSettings().setAllowContentAccess(false);
        dashboard.setWebViewClient(Build.VERSION.SDK_INT >= 26
                ? new RendererAwareDashboardClient() : new DashboardClient());
        dashboard.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                        || message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                    String event = "JavaScript " + message.messageLevel() + ": " + message.message()
                            + "\nSource: " + message.sourceId() + "\nLine: " + message.lineNumber();
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        diagnostics.recordFailure(event, null);
                    } else {
                        diagnostics.record(event);
                    }
                }
                return false;
            }
        });
        dashboard.setOnScrollChangeListener((view, scrollX, scrollY, oldX, oldY) -> {
            if (tvMode) return;
            if (scrollY <= dp(4)) {
                scrollTravel = 0;
                showDock();
                return;
            }
            int delta = scrollY - oldY;
            if (delta > 0) scrollTravel = Math.max(0, scrollTravel) + delta;
            else if (delta < 0) scrollTravel = Math.min(0, scrollTravel) + delta;
            if (scrollTravel >= dp(24)) {
                scrollTravel = 0;
                hideDock();
            } else if (scrollTravel <= -dp(12)) {
                scrollTravel = 0;
                showDock();
            }
        });
        return dashboard;
    }

    private class DashboardClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return openExternal(request.getUrl());
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return openExternal(Uri.parse(url));
        }
        @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
            if (!"about:blank".equals(url)) diagnostics.record("Page started: " + url);
        }
        @Override public void onPageFinished(WebView view, String url) {
            if (view == webView && !"about:blank".equals(url)) {
                diagnostics.record("Page finished: progress=" + view.getProgress()
                        + "; contentHeight=" + view.getContentHeight() + "; URL=" + url);
            }
        }
        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            String message = "WebView load error: code=" + error.getErrorCode() + "; "
                    + error.getDescription() + "; mainFrame=" + request.isForMainFrame()
                    + "; URL=" + request.getUrl();
            diagnostics.recordFailure(message, null);
            if (request.isForMainFrame()) showLoadFailure(view);
        }
        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            String message = "WebView HTTP error: status=" + response.getStatusCode() + "; "
                    + response.getReasonPhrase() + "; mainFrame=" + request.isForMainFrame()
                    + "; URL=" + request.getUrl();
            diagnostics.recordFailure(message, null);
            if (request.isForMainFrame()) showLoadFailure(view);
        }
    }

    @SuppressLint("UseRequiresApi") // Private class is created only by the SDK-guarded factory above.
    @TargetApi(26)
    private final class RendererAwareDashboardClient extends DashboardClient {
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            diagnostics.recordFailure("WebView renderer exited: crashed=" + detail.didCrash()
                    + "; priority=" + detail.rendererPriorityAtExit(), null);
            showLoadFailure(view);
            return true;
        }
    }

    private void showLoadFailure(WebView view) {
        if (view != webView || isFinishing() || isDestroyed()) return;
        dashboardPage.removeView(view);
        webView = null;
        dashboardLoaded = false;
        view.destroy();
        createDashboardFallback(dashboardPage, false);
        showDock();
    }

    private void createDashboardFallback(FrameLayout page, boolean providerUnavailable) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(36), dp(24), dp(100));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView title = fallbackText(content, getString(providerUnavailable
                ? R.string.dashboard_no_webview_title : R.string.dashboard_load_failed_title), 24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        fallbackText(content, getString(providerUnavailable
                ? R.string.dashboard_no_webview_explanation : R.string.dashboard_load_failed_explanation), 17);
        fallbackText(content, getString(R.string.dashboard_no_webview_alternatives), 17);

        fallbackStatus = fallbackText(content, serverStatus, 17);
        Button browser = new Button(this);
        browser.setText(R.string.dashboard_open_browser);
        browser.setOnClickListener(view -> openDashboardInBrowser());
        LinearLayout.LayoutParams browserLayout = new LinearLayout.LayoutParams(-1, -2);
        browserLayout.bottomMargin = dp(20);
        content.addView(browser, browserLayout);

        LinearLayout lanRow = new LinearLayout(this);
        lanRow.setOrientation(LinearLayout.HORIZONTAL);
        lanRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lanLayout = new LinearLayout.LayoutParams(-1, -2);
        lanLayout.bottomMargin = dp(20);
        content.addView(lanRow, lanLayout);
        fallbackAddress = fallbackText(lanRow,
                getString(R.string.dashboard_lan_address_loading), 18);
        fallbackAddress.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        fallbackAddress.setTextIsSelectable(true);
        copyLanButton = new Button(this);
        copyLanButton.setText(R.string.dashboard_copy_lan);
        copyLanButton.setContentDescription(getString(R.string.dashboard_copy_lan_description));
        copyLanButton.setVisibility(View.GONE);
        copyLanButton.setOnClickListener(view -> {
            if (lanDashboardUrl != null) copyToClipboard(R.string.dashboard_lan_clip_label,
                    lanDashboardUrl, R.string.dashboard_lan_copied);
        });
        LinearLayout.LayoutParams copyLayout = new LinearLayout.LayoutParams(-2, -2);
        copyLayout.leftMargin = dp(12);
        lanRow.addView(copyLanButton, copyLayout);

        Button debug = new Button(this);
        debug.setText(R.string.dashboard_copy_debug);
        debug.setOnClickListener(view -> copyDebugInfo());
        content.addView(debug, new LinearLayout.LayoutParams(-1, -2));
        page.addView(scroll, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView fallbackText(LinearLayout content, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(size);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(20);
        content.addView(view, layout);
        return view;
    }

    @SuppressWarnings("deprecation") // Poll all LAN networks, including ones without Internet, on API 25+.
    private String findLanDashboardUrl() {
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivity == null) return null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties == null) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()
                        && !address.isAnyLocalAddress()) {
                    return "http://" + address.getHostAddress() + ":7000/";
                }
            }
        }
        return null;
    }

    private void updateFallbackAddress(String url) {
        if (fallbackAddress == null) return;
        if (url == null) {
            lanDashboardUrl = null;
            copyLanButton.setVisibility(View.GONE);
            fallbackAddress.setText(R.string.dashboard_no_lan_address);
            return;
        }
        if (url.equals(lanDashboardUrl)) return;
        lanDashboardUrl = url;
        fallbackAddress.setText(url);
        copyLanButton.setVisibility(View.VISIBLE);
    }

    private void openDashboardInBrowser() {
        diagnostics.record("External browser requested: " + DASHBOARD);
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(DASHBOARD));
        browser.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException noBrowser) {
            diagnostics.record("No external browser available");
            Toast.makeText(this, R.string.dashboard_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private void copyToClipboard(int label, String text, int confirmation) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            Toast.makeText(this, R.string.dashboard_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(label), text));
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show();
    }

    private void copyDebugInfo() {
        copyToClipboard(R.string.dashboard_debug_clip_label, diagnostics.report(debugEnvironment()),
                R.string.dashboard_debug_copied);
    }

    @SuppressLint("WebViewApiAvailability")
    private String debugEnvironment() {
        StringBuilder info = new StringBuilder("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append("\nBrand/device: ").append(Build.BRAND).append('/')
                .append(Build.DEVICE).append("\nAndroid: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\nSecurity patch: ")
                .append(Build.VERSION.SECURITY_PATCH).append("\nBuild: ").append(Build.FINGERPRINT)
                .append("\nABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS))
                .append("\nApp: ").append(installedPackage(getPackageName()))
                .append("\nWebView feature declared: ")
                .append(getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW));
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                info.append("\nSelected WebView provider now: ").append(describePackage(WebView.getCurrentWebViewPackage()));
            } catch (RuntimeException | LinkageError failure) {
                info.append("\nSelected WebView provider now: query failed (see events)");
                diagnostics.recordFailure("WebView provider query failed while collecting debug info", failure);
            }
        } else {
            info.append("\nSelected WebView provider: query unavailable on API 25");
        }
        info.append("\nGoogle System WebView: ").append(installedPackage("com.google.android.webview"))
                .append("\nAOSP System WebView: ").append(installedPackage("com.android.webview"))
                .append("\nChrome: ").append(installedPackage("com.android.chrome"))
                .append("\nApp target SDK: ").append(getApplicationInfo().targetSdkVersion)
                .append("\nDebug build: ").append((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                .append("\nLocal dashboard: ").append(DASHBOARD)
                .append("\nCleartext localhost permitted: ")
                .append(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
                .append("\nServer enabled: ").append(DubLiftService.isEnabled(this))
                .append("\nLast health check: ").append(lastServerHealthy == null ? "not completed" : lastServerHealthy)
                .append("\nServer status: ").append(serverStatus)
                .append("\nLast server startup error: ")
                .append(getSharedPreferences("runtime", MODE_PRIVATE).getString("lastError", "none recorded"))
                .append("\nLAN dashboard: ").append(lanDashboardUrl == null ? "not available/checked" : lanDashboardUrl)
                .append("\nEmbedded WebView: ").append(webView == null ? "unavailable" : "created");
        if (webView != null) {
            info.append("\nCurrent page: ").append(webView.getUrl()).append("\nPage progress: ")
                    .append(webView.getProgress()).append("\nPage content height: ").append(webView.getContentHeight());
        }
        return info.toString();
    }

    private String installedPackage(String name) {
        try {
            return describePackage(getPackageManager().getPackageInfo(name, 0));
        } catch (PackageManager.NameNotFoundException missing) {
            return "not installed or not visible";
        } catch (RuntimeException failure) {
            return "query failed: " + failure;
        }
    }

    private String describePackage(PackageInfo info) {
        if (info == null) return "none";
        long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        String description = info.packageName + " " + info.versionName + " (" + version + ")";
        if (info.applicationInfo != null) description += "; enabled=" + info.applicationInfo.enabled;
        try {
            description += "; enabledSetting=" + getPackageManager().getApplicationEnabledSetting(info.packageName);
        } catch (RuntimeException ignored) { }
        return description;
    }

    private void createDock() {
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(dp(7), 0, dp(7), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(248, 20, 34, 51));
        background.setCornerRadius(dp(31));
        background.setStroke(dp(1), Color.rgb(47, 73, 91));
        dock.setBackground(background);
        dock.setElevation(dp(10));

        FrameLayout statusArea = new FrameLayout(this);
        statusArea.setContentDescription(serverStatus);
        statusArea.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.dashboard_debug_dialog_title)
                .setMessage(getString(R.string.dashboard_debug_dialog_message, serverStatus))
                .setPositiveButton(R.string.dashboard_copy_debug, (dialog, which) -> copyDebugInfo())
                .setNegativeButton(android.R.string.cancel, null).show());
        makeDockControlFocusable(statusArea);
        statusControl = statusArea;
        View dot = new View(this);
        statusShape = new GradientDrawable();
        statusShape.setShape(GradientDrawable.OVAL);
        statusShape.setColor(Color.rgb(234, 181, 91));
        dot.setBackground(statusShape);
        statusArea.addView(dot, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
        dock.addView(statusArea, new LinearLayout.LayoutParams(dp(36), -1));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(62, 84, 102));
        LinearLayout.LayoutParams dividerLayout = new LinearLayout.LayoutParams(dp(1), dp(28));
        dividerLayout.setMargins(dp(1), 0, dp(3), 0);
        dock.addView(divider, dividerLayout);

        View restart = dockButton(R.drawable.ic_restart, "Restart", MUTED);
        restart.setOnClickListener(v -> {
            clearDashboard();
            startServer(DubLiftService.ACTION_RESTART);
            showDock();
        });
        firstDockAction = restart;
        dock.addView(restart, new LinearLayout.LayoutParams(dp(62), -1));

        View battery = dockButton(R.drawable.ic_battery, "Battery", MUTED);
        battery.setOnClickListener(v -> requestBatteryExemption());
        dock.addView(battery, new LinearLayout.LayoutParams(dp(62), -1));

        View updates = dockButton(R.drawable.ic_update, "Updates", MUTED);
        updates.setContentDescription("Check for updates");
        if (Build.VERSION.SDK_INT >= 26) updates.setTooltipText("Check for updates");
        updates.setOnClickListener(v -> {
            showDock();
            if (ApkUpdateManager.hasDownload(this)) {
                downloadUiHidden = false;
                pollDownload();
            } else {
                checkForUpdate(true);
            }
        });
        dock.addView(updates, new LinearLayout.LayoutParams(dp(62), -1));

        serverToggle = dockButton(R.drawable.ic_stop, "Stop", STOPPED);
        serverToggle.setOnClickListener(v -> {
            if (serverEnabled) {
                startService(new Intent(this, DubLiftService.class).setAction(DubLiftService.ACTION_STOP));
                clearDashboard();
                setServerStatus("Server stopped", STOPPED);
                setServerToggle(false);
            } else {
                startServer(DubLiftService.ACTION_START);
            }
            showDock();
        });
        dock.addView(serverToggle, new LinearLayout.LayoutParams(dp(62), -1));
    }

    private void checkForUpdate(boolean manual) {
        if (updateChecking) {
            manualUpdateCheck |= manual;
            if (manual) Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (installedVersion == null) {
            if (manual) showUpdateMessage("Could not read the installed app version.");
            return;
        }
        if (updateDialog != null) updateDialog.dismiss();
        availableUpdate = null;
        updateChecking = true;
        manualUpdateCheck = manual;
        updateCheckComplete = false;
        if (manual) Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show();
        getSharedPreferences("runtime", MODE_PRIVATE).edit()
                .putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
        updateChecks.execute(() -> {
            ReleaseUpdateChecker.Release release = null;
            boolean failed = false;
            try {
                release = ReleaseUpdateChecker.check(installedVersion, Build.SUPPORTED_ABIS);
            } catch (IOException | org.json.JSONException | RuntimeException failure) {
                failed = true;
            }
            ReleaseUpdateChecker.Release result = release;
            boolean checkFailed = failed;
            handler.post(() -> {
                updateChecking = false;
                if (isFinishing() || isDestroyed()) return;
                updateCheckComplete = true;
                if (result != null) {
                    availableUpdate = result;
                    showAvailableUpdate();
                } else if (manualUpdateCheck) {
                    showUpdateMessage(checkFailed
                            ? "Could not check for updates. Check your internet connection and try again."
                            : "DubLift " + installedVersion + " is up to date.");
                }
                manualUpdateCheck = false;
            });
        });
    }

    private void maybeCheckForUpdate() {
        if (installedVersion == null || updateChecking || updateDialog != null || availableUpdate != null
                || ApkUpdateManager.hasDownload(this)) {
            return;
        }
        long lastCheck = getSharedPreferences("runtime", MODE_PRIVATE).getLong("lastUpdateCheck", 0);
        long now = System.currentTimeMillis();
        if (lastCheck == 0 || now - lastCheck >= UPDATE_CHECK_INTERVAL_MS || now < lastCheck) {
            checkForUpdate(false);
        }
    }

    private void showAvailableUpdate() {
        // Wait until notification/battery permission screens have returned control to the app.
        if (!resumed || !hasWindowFocus() || isFinishing() || isDestroyed()
                || availableUpdate == null || updateDialog != null || ApkUpdateManager.hasDownload(this)) return;
        ReleaseUpdateChecker.Release release = availableUpdate;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("DubLift update available")
                .setMessage("Installed: " + installedVersion + "\nAvailable: " + release.version()
                        + (release.apkUrl == null ? "\n\nNo compatible APK is available for this device."
                        : "\nAPK: " + release.abi)
                        + "\n\nWhat's new\n\n" + release.changelog)
                .setPositiveButton(release.apkUrl == null ? "Open release page" : "Download and install",
                        (dialog, which) -> {
                            if (release.apkUrl == null) openReleasePage(release.pageUrl());
                            else downloadUpdate(release);
                        })
                .setNegativeButton("Later", null);
        if (release.apkUrl != null) {
            builder.setNeutralButton("Release page", (dialog, which) -> openReleasePage(release.pageUrl()));
        }
        updateDialog = builder.create();
        updateDialog.setOnDismissListener(dialog -> {
            availableUpdate = null;
            updateDialog = null;
        });
        updateDialog.show();
    }

    private void showUpdateMessage(String message) {
        pendingUpdateMessage = message;
        showPendingUpdateMessage();
    }

    private void showPendingUpdateMessage() {
        if (pendingUpdateMessage == null || updateMessageDialog != null || !resumed
                || !hasWindowFocus() || isFinishing() || isDestroyed()) return;
        updateMessageDialog = new AlertDialog.Builder(this).setTitle("DubLift updates")
                .setMessage(pendingUpdateMessage).setPositiveButton(android.R.string.ok, null).create();
        updateMessageDialog.setOnDismissListener(dialog -> {
            updateMessageDialog = null;
            pendingUpdateMessage = null;
        });
        updateMessageDialog.show();
    }

    private void downloadUpdate(ReleaseUpdateChecker.Release release) {
        try {
            ApkUpdateManager.download(this, release);
            downloadProgress = null;
            autoInstallDownload = true;
            downloadUiHidden = false;
            pollDownload();
        } catch (IOException | RuntimeException failure) {
            showUpdateMessage("Could not start the download. " + failure.getMessage());
        }
    }

    private void pollDownload() {
        handler.removeCallbacks(downloadPoll);
        if (!resumed || downloadPolling || !ApkUpdateManager.hasDownload(this)) return;
        downloadPolling = true;
        // Bind the result to this download; cancel/retry can replace it while a query is in flight.
        long downloadId = ApkUpdateManager.downloadId(this);
        updateChecks.execute(() -> {
            ApkUpdateManager.Progress progress = null;
            String error = null;
            try {
                progress = ApkUpdateManager.progress(this);
            } catch (IOException | RuntimeException failure) {
                error = failure.getMessage();
            }
            ApkUpdateManager.Progress result = progress;
            String failure = error;
            handler.post(() -> {
                downloadPolling = false;
                if (isFinishing() || isDestroyed() || !ApkUpdateManager.hasDownload(this)) return;
                if (downloadId != ApkUpdateManager.downloadId(this)) {
                    pollDownload();
                    return;
                }
                downloadProgress = result;
                if (result == null || result.status == DownloadManager.STATUS_FAILED) {
                    ApkUpdateManager.clear(this);
                    autoInstallDownload = false;
                    closeDownloadDialog();
                    showUpdateMessage("Update download failed. "
                            + (result != null && result.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
                            ? "Free some storage and try again."
                            : "Check your connection and storage, then use Updates to try again.")
                            + (failure == null ? "" : "\n" + failure));
                    return;
                }
                showDownloadState();
                if (resumed && result.status != DownloadManager.STATUS_SUCCESSFUL) {
                    handler.postDelayed(downloadPoll, 1000);
                }
            });
        });
    }

    private void showDownloadState() {
        if (!resumed || (!hasWindowFocus() && downloadDialog == null) || isFinishing() || isDestroyed()
                || !ApkUpdateManager.hasDownload(this) || downloadProgress == null || downloadUiHidden || installPreparing
                || awaitingInstallPermission || installerOpen) return;
        boolean ready = downloadProgress.status == DownloadManager.STATUS_SUCCESSFUL;
        if (ready && autoInstallDownload) {
            autoInstallDownload = false;
            closeDownloadDialog();
            installDownloadedUpdate();
            return;
        }
        if (downloadDialog != null && downloadDialogReady != ready) closeDownloadDialog();
        if (downloadDialog == null) {
            downloadDialogReady = ready;
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(ready ? "Update ready to install" : "Downloading DubLift update");
            if (ready) {
                builder.setMessage("DubLift " + ApkUpdateManager.tag(this)
                                + " is downloaded. Android will ask you to confirm installation."
                                + "\n\nThe APK is deleted automatically after the app is updated.")
                        .setPositiveButton("Install update", (dialog, which) -> installDownloadedUpdate())
                        .setNegativeButton("Delete download", (dialog, which) -> {
                            ApkUpdateManager.clear(this);
                            downloadProgress = null;
                        })
                        .setNeutralButton("Later", null);
            } else {
                LinearLayout content = new LinearLayout(this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(dp(24), dp(12), dp(24), 0);
                downloadStatus = new TextView(this);
                content.addView(downloadStatus);
                downloadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                content.addView(downloadBar, new LinearLayout.LayoutParams(-1, dp(32)));
                builder.setView(content).setNegativeButton("Cancel download", (dialog, which) -> {
                    autoInstallDownload = false;
                    ApkUpdateManager.clear(this);
                    downloadProgress = null;
                });
            }
            downloadDialog = builder.create();
            downloadDialog.setOnDismissListener(dialog -> {
                downloadDialog = null;
                if (!installPreparing) downloadUiHidden = true;
                autoInstallDownload = false;
            });
            downloadDialog.show();
        }
        if (!ready && downloadBar != null) {
            boolean known = downloadProgress.total > 0;
            int percent = known ? (int) (100 * downloadProgress.downloaded / downloadProgress.total) : 0;
            downloadBar.setIndeterminate(!known);
            downloadBar.setProgress(percent);
            downloadStatus.setText(downloadProgress.status == DownloadManager.STATUS_PAUSED
                    ? "Download paused. Android will retry when the connection or storage is available."
                    : known ? percent + "% downloaded" : "Waiting for the download…");
        }
    }

    private void closeDownloadDialog() {
        if (downloadDialog == null) return;
        downloadDialog.setOnDismissListener(null);
        downloadDialog.dismiss();
        downloadDialog = null;
    }

    private void installDownloadedUpdate() {
        if (installPreparing || installerOpen || !ApkUpdateManager.hasDownload(this)) return;
        installPreparing = true;
        updateChecks.execute(() -> {
            String error = null;
            try {
                ApkUpdateManager.validateApk(this);
            } catch (IOException | PackageManager.NameNotFoundException | RuntimeException failure) {
                error = failure.getMessage();
            }
            String failure = error;
            handler.post(() -> {
                installPreparing = false;
                if (isFinishing() || isDestroyed()) return;
                if (failure != null) {
                    ApkUpdateManager.clear(this);
                    downloadProgress = null;
                    showUpdateMessage("Cannot install the update. " + failure);
                } else if (!resumed || !hasWindowFocus()) {
                    autoInstallDownload = true;
                } else {
                    requestUpdateInstallation();
                }
            });
        });
    }

    private void requestUpdateInstallation() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            installPermissionDialog = new AlertDialog.Builder(this).setTitle("Allow DubLift updates")
                    .setMessage("Allow DubLift to install apps in the next system screen, then return here. "
                            + "On Fire TV this may be under My Fire TV → Developer options → Install unknown apps.")
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        awaitingInstallPermission = true;
                        try {
                            startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION_REQUEST);
                        } catch (ActivityNotFoundException | SecurityException unavailable) {
                            try {
                                startActivityForResult(new Intent(Settings.ACTION_SECURITY_SETTINGS),
                                        INSTALL_PERMISSION_REQUEST);
                            } catch (ActivityNotFoundException | SecurityException missing) {
                                awaitingInstallPermission = false;
                                showUpdateMessage("Open your device settings and allow DubLift to install unknown apps, "
                                        + "then return and press Updates.");
                            }
                        }
                    }).setNegativeButton(android.R.string.cancel, null).create();
            installPermissionDialog.setOnDismissListener(dialog -> installPermissionDialog = null);
            installPermissionDialog.show();
            return;
        }
        launchUpdateInstaller();
    }

    @SuppressWarnings("deprecation") // ACTION_INSTALL_PACKAGE and a content grant work on API 25 and TV installers.
    private void launchUpdateInstaller() {
        Uri apk = ApkUpdateManager.apkUri(this);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(apk, ApkUpdateManager.MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        install.setClipData(ClipData.newRawUri("DubLift update", apk));
        installerOpen = true;
        try {
            startActivityForResult(install, INSTALL_APK_REQUEST);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                // Some TV firmware only registers its installer for ACTION_VIEW.
                startActivityForResult(new Intent(install).setAction(Intent.ACTION_VIEW), INSTALL_APK_REQUEST);
            } catch (ActivityNotFoundException | SecurityException missing) {
                installerOpen = false;
                showUpdateMessage("No system APK installer is available. Enable app installation in device settings "
                        + "and press Updates to retry.");
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INSTALL_PERMISSION_REQUEST) {
            awaitingInstallPermission = false;
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
                autoInstallDownload = true;
                downloadUiHidden = false;
            } else {
                showUpdateMessage("Installation permission was not granted. Allow DubLift to install unknown apps "
                        + "in device settings, then press Updates to retry.");
            }
        } else if (requestCode == INSTALL_APK_REQUEST) {
            installerOpen = false;
            // Keep an unsuccessful/cancelled install available for retry or explicit deletion.
            // MY_PACKAGE_REPLACED handles success even when installation kills this process.
            ApkUpdateManager.cleanupInstalled(this);
            if (ApkUpdateManager.hasDownload(this)) {
                Toast.makeText(this, "Update not installed. Press Updates to retry or delete the download.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openReleasePage(String url) {
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        browser.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "No browser can open the release page", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ApkUpdateManager.cleanupInstalled(this);
        pollDownload();
        showPendingUpdateMessage();
        showAvailableUpdate();
        maybeCheckForUpdate();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(downloadPoll);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            showPendingUpdateMessage();
            showAvailableUpdate();
            showDownloadState();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putStringArrayList("dashboardDiagnosticEvents", diagnostics.events());
        state.putString("dashboardFirstFailure", diagnostics.firstFailure());
        state.putBoolean("updateCheckComplete", updateCheckComplete);
        state.putBoolean("manualUpdateCheck", manualUpdateCheck);
        state.putBoolean("autoInstallDownload", autoInstallDownload || installPreparing);
        state.putBoolean("awaitingInstallPermission", awaitingInstallPermission);
        state.putBoolean("installerOpen", installerOpen);
        state.putBoolean("downloadUiHidden", downloadUiHidden);
        state.putString("updateMessage", pendingUpdateMessage);
        if (availableUpdate != null) {
            state.putString("updateTag", availableUpdate.tag);
            state.putString("updateChangelog", availableUpdate.changelog);
            state.putString("updateApkUrl", availableUpdate.apkUrl);
            state.putString("updateAbi", availableUpdate.abi);
        }
    }

    private LinearLayout dockButton(int icon, String label, int color) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(label);
        button.setBackgroundResource(dockControlRipple());
        makeDockControlFocusable(button);

        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(color));
        button.addView(image, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(color);
        caption.setTextSize(10);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionLayout = new LinearLayout.LayoutParams(-1, -2);
        captionLayout.topMargin = dp(3);
        button.addView(caption, captionLayout);
        return button;
    }

    private void makeDockControlFocusable(View control) {
        if (!tvMode) return;
        control.setFocusable(true);
        control.setFocusableInTouchMode(true);
    }

    private int dockControlRipple() {
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        return ripple.resourceId;
    }

    private void setServerToggle(boolean enabled) {
        serverEnabled = enabled;
        String label = enabled ? "Stop" : "Start";
        int color = enabled ? STOPPED : ACCENT;
        ImageView icon = (ImageView) serverToggle.getChildAt(0);
        TextView caption = (TextView) serverToggle.getChildAt(1);
        icon.setImageResource(enabled ? R.drawable.ic_stop : R.drawable.ic_start);
        icon.setImageTintList(ColorStateList.valueOf(color));
        caption.setText(label);
        caption.setTextColor(color);
        serverToggle.setContentDescription(label);
    }

    private void hideDock() {
        if (tvMode || dockHidden) return;
        dockHidden = true;
        dock.animate().cancel();
        dock.animate().translationY(dp(86)).alpha(0f).setDuration(190)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> { if (dockHidden) dock.setVisibility(View.INVISIBLE); })
                .start();
    }

    private void showDock() {
        if (!dockHidden) return;
        dockHidden = false;
        dock.animate().cancel();
        dock.setVisibility(View.VISIBLE);
        dock.animate().translationY(0f).alpha(1f).setDuration(190)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void setServerStatus(String message, int color) {
        if (!message.equals(serverStatus)) diagnostics.record("Server status: " + message);
        serverStatus = message;
        statusShape.setColor(color);
        statusControl.setContentDescription(message);
        if (fallbackStatus != null) {
            fallbackStatus.setText(message);
            fallbackStatus.setTextColor(color);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startServer(String action) {
        diagnostics.record("Server action: " + action);
        AndroidCompat.startServer(this, new Intent(this, DubLiftService.class).setAction(action));
        setServerStatus("Server starting", Color.rgb(234, 181, 91));
        setServerToggle(true);
    }

    private void checkServer() {
        checks.execute(() -> {
            boolean ready = DubLiftService.healthy();
            String lanUrl = fallbackAddress != null ? findLanDashboardUrl() : null;
            handler.post(() -> {
                if (!active) return;
                if (lastServerHealthy == null || lastServerHealthy != ready) {
                    diagnostics.record("Local server health check: " + (ready ? "healthy" : "not healthy"));
                }
                lastServerHealthy = ready;
                updateFallbackAddress(lanUrl);
                if (!DubLiftService.isEnabled(this)) {
                    setServerStatus("Server stopped", STOPPED);
                    setServerToggle(false);
                    if (dashboardLoaded) {
                        clearDashboard();
                    }
                } else if (ready) {
                    setServerStatus(webView != null ? "Server running at 127.0.0.1:7000" : "Server running", ACCENT);
                    setServerToggle(true);
                    if (webView != null && !dashboardLoaded) {
                        dashboardLoaded = true;
                        webView.loadUrl(DASHBOARD);
                    }
                } else {
                    setServerStatus("Server starting", Color.rgb(234, 181, 91));
                    setServerToggle(true);
                }
                handler.postDelayed(this::checkServer, 2000);
            });
        });
    }

    private void clearDashboard() {
        dashboardLoaded = false;
        if (webView != null) webView.loadUrl("about:blank");
    }

    private boolean openExternal(Uri uri) {
        if ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() == 7000) return false;
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme) && !"stremio".equals(scheme)) {
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void requestBatteryExemption() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && power.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Background battery restriction already disabled", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(request);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void maybeRequestBatteryExemption() {
        if (getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("batteryAsked", false)) return;
        getSharedPreferences("runtime", MODE_PRIVATE).edit().putBoolean("batteryAsked", true).apply();
        requestBatteryExemption();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) maybeRequestBatteryExemption();
    }

    @Override public void onBackPressed() {
        handleBackNavigation();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (tvMode && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                handleBackNavigation();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleBackNavigation() {
        if (tvMode) {
            if (dock != null && dock.hasFocus()) {
                if (webView != null) {
                    webView.requestFocus();
                } else {
                    super.onBackPressed();
                }
            } else if (dock != null) {
                showDock();
                View target = firstDockAction != null ? firstDockAction : statusControl;
                if (target != null && target.requestFocus()) return;
                if (webView != null) webView.requestFocus();
            }
            return;
        }
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        active = false;
        checks.shutdownNow();
        updateChecks.shutdownNow();
        if (updateDialog != null) updateDialog.dismiss();
        closeDownloadDialog();
        if (updateMessageDialog != null) {
            updateMessageDialog.setOnDismissListener(null);
            updateMessageDialog.dismiss();
        }
        if (installPermissionDialog != null) installPermissionDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
