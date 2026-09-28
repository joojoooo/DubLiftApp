package org.dublift.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String DASHBOARD = "http://127.0.0.1:7000/";
    private static final int SURFACE = Color.rgb(20, 34, 51);
    private static final int ACCENT = Color.rgb(79, 222, 183);
    private static final int MUTED = Color.rgb(211, 224, 235);
    private static final int STOPPED = Color.rgb(244, 133, 139);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService checks = Executors.newSingleThreadExecutor();
    private WebView webView;
    private LinearLayout dock;
    private LinearLayout serverToggle;
    private GradientDrawable statusShape;
    private View statusControl;
    private boolean dockHidden;
    private int scrollTravel;
    private boolean dashboardLoaded;
    private boolean active;
    private boolean serverEnabled = true;
    private String serverStatus = "Server starting";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(SURFACE);

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(SURFACE);
        if (Build.VERSION.SDK_INT >= 35) {
            page.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
        }

        webView = new WebView(this);
        webView.setBackgroundColor(SURFACE);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternal(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternal(Uri.parse(url));
            }
        });
        webView.setOnScrollChangeListener((view, scrollX, scrollY, oldX, oldY) -> {
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
        page.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        createDock();
        FrameLayout.LayoutParams dockLayout = new FrameLayout.LayoutParams(
                -2, dp(62), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockLayout.bottomMargin = dp(14);
        page.addView(dock, dockLayout);
        setContentView(page);

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
        statusArea.setOnClickListener(v -> Toast.makeText(this, serverStatus, Toast.LENGTH_SHORT).show());
        statusArea.setTooltipText("Server status");
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
            dashboardLoaded = false;
            webView.loadUrl("about:blank");
            startServer(DubLiftService.ACTION_RESTART);
            showDock();
        });
        dock.addView(restart, new LinearLayout.LayoutParams(dp(66), -1));

        View battery = dockButton(R.drawable.ic_battery, "Battery", MUTED);
        battery.setOnClickListener(v -> requestBatteryExemption());
        dock.addView(battery, new LinearLayout.LayoutParams(dp(66), -1));

        serverToggle = dockButton(R.drawable.ic_stop, "Stop", STOPPED);
        serverToggle.setOnClickListener(v -> {
            if (serverEnabled) {
                startService(new Intent(this, DubLiftService.class).setAction(DubLiftService.ACTION_STOP));
                dashboardLoaded = false;
                webView.loadUrl("about:blank");
                setServerStatus("Server stopped", STOPPED);
                setServerToggle(false);
            } else {
                startServer(DubLiftService.ACTION_START);
            }
            showDock();
        });
        dock.addView(serverToggle, new LinearLayout.LayoutParams(dp(66), -1));
    }

    private LinearLayout dockButton(int icon, String label, int color) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(label);
        button.setTooltipText(label);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);

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
        serverToggle.setTooltipText(label);
    }

    private void hideDock() {
        if (dockHidden) return;
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
        serverStatus = message;
        statusShape.setColor(color);
        statusControl.setContentDescription(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startServer(String action) {
        startForegroundService(new Intent(this, DubLiftService.class).setAction(action));
        setServerStatus("Server starting", Color.rgb(234, 181, 91));
        setServerToggle(true);
    }

    private void checkServer() {
        checks.execute(() -> {
            boolean ready = DubLiftService.healthy();
            handler.post(() -> {
                if (!active) return;
                if (!DubLiftService.isEnabled(this)) {
                    setServerStatus("Server stopped", STOPPED);
                    setServerToggle(false);
                    if (dashboardLoaded) {
                        dashboardLoaded = false;
                        webView.loadUrl("about:blank");
                    }
                } else if (ready) {
                    setServerStatus("Server running at 127.0.0.1:7000", ACCENT);
                    setServerToggle(true);
                    if (!dashboardLoaded) {
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
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        active = false;
        checks.shutdownNow();
        webView.destroy();
        super.onDestroy();
    }
}
