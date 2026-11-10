package com.jiuguan.client;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Locale;

public class MainActivity extends Activity implements BrowserService.Client {
    private static final int UPLOAD = 20;
    private static final int EXPORT = 21;
    private static final int NOTIFY = 22;
    private static final int BG = 0xff111820;
    private static final int CARD = 0xff1b252f;
    private static final int MINT = 0xff86d8b5;
    private static final int TEXT = 0xffedf4f3;
    private static final int MUTED = 0xff9daeb7;
    private FrameLayout root;
    private FrameLayout webHost;
    private ProgressBar progress;
    private TextView error;
    private EditText addressInput;
    private Switch backgroundSwitch;
    private RadioButton mobileModeButton, computerModeButton;
    private Button connectButton, returnToTavernButton;
    private TextView connectionStatus;
    private SharedPreferences prefs;
    private BrowserService service;
    private boolean bound;
    private boolean connecting;
    private boolean showingBrowser;
    private boolean exporting;
    private boolean resumed;
    private AlertDialog authDialog;
    private HttpAuthHandler authHandler;
    private boolean stayInSettings;
    private StartupPolicy startup;
    private boolean automaticOpening, applyingConnectionSettings;
    private TextView automaticStatus;
    private Runnable automaticControlTimeout;
    private AlertDialog relayDialog;
    private TextView relayStatusView, relayTrafficView;
    private RadioButton dialogMobileMode, dialogComputerMode;
    private Button relayRetryButton;
    private AlertDialog diagnosticsDialog;
    private TextView diagnosticsText;
    private String latestDiagnostics = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable relayTicker = new Runnable() {
        @Override public void run() {
            if (relayDialog == null || !relayDialog.isShowing() || !resumed) return;
            updateRelayDialog();
            ui.postDelayed(this, 1000);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((BrowserService.LocalBinder) binder).getService();
            service.attach(MainActivity.this, MainActivity.this);
            attachBrowserBehindConfiguration();
            if (connecting) connect();
            else connectionChanged();
            maybeOpenAutomatically();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            showHome();
            toast("后台会话已结束，请重新打开酒馆。未完成的生成不会自动重试。");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("client", MODE_PRIVATE);
        exporting = state != null && state.getBoolean("exporting", false);
        stayInSettings = state != null && state.getBoolean("stayInSettings", false);
        startup = new StartupPolicy(state != null && state.getBoolean("startupConsidered", false)
                && !state.getBoolean("resumeSuccessfulSession", false));
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return insets;
            });
        } else root.setFitsSystemWindows(true);
        setContentView(root);
        showHome();
        if (state != null && state.getBoolean("automaticOpening", false) && !stayInSettings) showAutomaticOpening();
        bound = bindService(new Intent(this, BrowserService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private void showHome() {
        clearAutomaticControlTimeout();
        showingBrowser = false;
        automaticOpening = false;
        automaticStatus = null;
        if (service != null && service.browser().getParent() instanceof ViewGroup)
            ((ViewGroup) service.browser().getParent()).removeView(service.browser());
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView title = label("酒馆口袋 · 连接设置", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(page, title, 0);
        add(page, label("成功连接后，下次自动进入；连接失败时回到这里修改。", 14, MUTED), 8);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(20), dp(18), dp(20));
        form.setBackground(shape(CARD, 18));
        add(page, form, 18);
        add(form, label("联网方式（二选一）", 15, TEXT), 0);
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(LinearLayout.VERTICAL);
        computerModeButton = modeButton("使用电脑网络");
        mobileModeButton = modeButton("使用手机流量");
        modes.addView(computerModeButton);
        modes.addView(mobileModeButton);
        boolean mobile = service != null && service.isRelayEnabled() || prefs.getBoolean("relay", false);
        modes.check(mobile ? mobileModeButton.getId() : computerModeButton.getId());
        add(form, modes, 6);
        add(form, label("电脑能上网：选电脑网络。\n电脑只有内网：选手机流量，手机同时开启 Wi-Fi 和移动数据。\n需要代理的 API：先开启手机代理，本应用会优先使用它的出口。", 12, MUTED), 4);
        add(form, label("酒馆地址", 14, TEXT), 20);
        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setTextColor(TEXT);
        addressInput.setHintTextColor(MUTED);
        addressInput.setTextSize(15);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setHint("http://电脑的局域网IP:端口");
        addressInput.setText(prefs.getString("address", getString(R.string.default_server_url)));
        addressInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        addressInput.setBackground(shape(0xff111a22, 10));
        add(form, addressInput, 12);
        add(form, label("填写你手机浏览器里能打开的同一个地址。", 12, MUTED), 10);

        backgroundSwitch = new Switch(this);
        backgroundSwitch.setText("后台保持");
        backgroundSwitch.setTextColor(TEXT);
        backgroundSwitch.setTextSize(15);
        backgroundSwitch.setChecked(prefs.getBoolean("background", true));
        backgroundSwitch.setPadding(0, dp(10), 0, dp(4));
        add(form, backgroundSwitch, 14);
        add(form, label("常驻通知并保持网络，会增加耗电；不用时可停止。", 12, MUTED), 3);

        connectionStatus = label("", 13, MINT);
        add(form, connectionStatus, 10);

        connectButton = new Button(this);
        connectButton.setText("打开酒馆");
        connectButton.setTextSize(16);
        connectButton.setAllCaps(false);
        connectButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        connectButton.setTextColor(BG);
        connectButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MINT));
        connectButton.setEnabled(service != null);
        connectButton.setOnClickListener(v -> {
            if (service != null && service.isConnecting()) cancelOpening(); else connect();
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(54));
        buttonParams.topMargin = dp(24);
        form.addView(connectButton, buttonParams);

        returnToTavernButton = new Button(this);
        returnToTavernButton.setText("返回当前酒馆");
        returnToTavernButton.setAllCaps(false);
        returnToTavernButton.setOnClickListener(v -> {
            stayInSettings = false;
            if (service != null && service.hasLoadedPage()) showBrowser();
        });
        add(form, returnToTavernButton, 6);

        TextView loginSettings = label("账号与自动登录  ›", 14, MINT);
        loginSettings.setPadding(0, dp(12), 0, dp(12));
        loginSettings.setOnClickListener(v -> showLoginSettings());
        add(page, loginSettings, 12);

        TextView diagnostics = label("连接诊断（模型连接失败时查看）  ›", 14, MINT);
        diagnostics.setPadding(0, dp(12), 0, dp(12));
        diagnostics.setOnClickListener(v -> showDiagnostics());
        add(page, diagnostics, 4);

        TextView battery = label("设置后台运行权限  ›", 14, MINT);
        battery.setPadding(0, dp(16), 0, dp(16));
        battery.setOnClickListener(v -> batteryHelp());
        add(page, battery, 10);
        add(page, label("手机和电脑需在同一局域网，电脑上的酒馆需保持运行。\n进入网页后，右侧“连接设置”可随时回到这里。", 12, MUTED), 4);
        attachBrowserBehindConfiguration();
        updateConnectionUi();
    }

    private void attachBrowserBehindConfiguration() {
        if (service == null || showingBrowser) return;
        WebView browser = service.browser();
        if (browser.getParent() == root) return;
        if (browser.getParent() instanceof ViewGroup) ((ViewGroup) browser.getParent()).removeView(browser);
        // Keep WebView attached while the opaque native configuration page stays in front.
        root.addView(browser, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private void updateConnectionUi() {
        if (automaticOpening) {
            if (automaticStatus != null && service != null) automaticStatus.setText(service.hasLoadedPage()
                    ? "网页已连接，正在确认网络设置…" : service.connectionMessage());
            return;
        }
        if (showingBrowser || connectButton == null) return;
        boolean loading = service != null && service.isConnecting();
        boolean loaded = service != null && service.hasLoadedPage();
        connectButton.setEnabled(service != null);
        connectButton.setText(loading ? "取消连接，继续设置" : loaded ? "应用设置并重新连接" : "打开酒馆");
        returnToTavernButton.setVisibility(loaded ? View.VISIBLE : View.GONE);
        addressInput.setEnabled(!loading);
        computerModeButton.setEnabled(!loading);
        mobileModeButton.setEnabled(!loading);
        backgroundSwitch.setEnabled(!loading);
        connectionStatus.setText(service == null ? "正在准备客户端…" : service.connectionMessage());
        connectionStatus.setVisibility(connectionStatus.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void cancelOpening() {
        startup.consume();
        stayInSettings = true;
        connecting = false;
        if (authDialog != null) authDialog.dismiss();
        authHandler = null;
        if (service != null) {
            if (automaticOpening) service.cancelStartupConnection("已取消自动连接，可修改设置后重新打开。");
            else service.cancelConnection();
        }
        if (showingBrowser || automaticOpening) showHome(); else updateConnectionUi();
    }

    private void openConnectionSettings() {
        startup.consume();
        stayInSettings = true;
        if (service != null && service.isConnecting()) service.cancelConnection();
        showHome();
    }

    @Override public void connectionChanged() {
        if (service == null || isFinishing() || isDestroyed()) return;
        rememberSuccessfulStartup();
        if (service.hasLoadedPage()) {
            if (automaticOpening && !service.canRememberStartup() && !service.startupNeedsLogin()) {
                if (!service.startupProblem().isEmpty()) {
                    failAutomaticOpening(service.startupProblem());
                } else {
                    armAutomaticControlTimeout();
                    updateConnectionUi();
                }
                return;
            }
            if (!showingBrowser && !stayInSettings) showBrowser();
        } else if (!service.isConnecting() && (showingBrowser || automaticOpening)) {
            stayInSettings = true;
            showHome();
        }
        updateConnectionUi();
    }

    private void rememberSuccessfulStartup() {
        if (applyingConnectionSettings || service == null || !service.canRememberStartup()) return;
        String address = service.address();
        boolean mobile = service.isRelayEnabled(), background = service.isKeepingAlive();
        // Settings are saved before connecting; never certify an old page under new settings.
        if (!StartupPolicy.hasMatchingSuccess(prefs.getString("address", ""), prefs.getBoolean("relay", false),
                prefs.getBoolean("background", true), address, mobile, background)) return;
        if (StartupPolicy.hasMatchingSuccess(address, mobile, background,
                prefs.getString("startupSuccessAddress", ""), prefs.getBoolean("startupSuccessMobile", false),
                prefs.getBoolean("startupSuccessBackground", true))) return;
        prefs.edit().putString("startupSuccessAddress", address).putBoolean("startupSuccessMobile", mobile)
                .putBoolean("startupSuccessBackground", background).apply();
    }

    private void maybeOpenAutomatically() {
        boolean configured = addressInput != null && StartupPolicy.hasMatchingSuccess(addressInput.getText().toString(),
                mobileModeButton.isChecked(), backgroundSwitch.isChecked() || mobileModeButton.isChecked(),
                prefs.getString("startupSuccessAddress", ""), prefs.getBoolean("startupSuccessMobile", false),
                prefs.getBoolean("startupSuccessBackground", true));
        if (!startup.claim(resumed, service != null, stayInSettings,
                service != null && (service.isConnecting() || service.hasLoadedPage() || !service.address().isEmpty()), configured)) return;
        showAutomaticOpening();
        if (!connect()) { stayInSettings = true; showHome(); }
    }

    private void showAutomaticOpening() {
        automaticOpening = true;
        showingBrowser = false;
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(28), dp(28), dp(28), dp(28));
        panel.setBackgroundColor(BG);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        TextView title = label("正在自动打开酒馆", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(panel, title, 0);
        add(panel, label(mobileModeButton.isChecked() ? "使用手机流量" : "使用电脑网络", 15, MINT), 12);
        automaticStatus = label("正在连接上次成功使用的酒馆…", 14, MUTED);
        add(panel, automaticStatus, 18);
        add(panel, label("连接失败会返回配置界面，也可以随时取消。", 12, MUTED), 12);
        Button settings = new Button(this);
        settings.setText("取消连接，修改设置");
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> cancelOpening());
        add(panel, settings, 24);
        attachBrowserBehindConfiguration();
    }

    private void clearAutomaticControlTimeout() {
        if (automaticControlTimeout != null) ui.removeCallbacks(automaticControlTimeout);
        automaticControlTimeout = null;
    }

    private void armAutomaticControlTimeout() {
        if (automaticControlTimeout != null) return;
        // Native pairing has its own 5-second request budget; give its result time to reach UI.
        automaticControlTimeout = () -> {
            if (automaticOpening && service != null && !service.canRememberStartup())
                failAutomaticOpening("网页已打开，但未能确认网络连接。请检查电脑插件和手机网络后重试。");
        };
        ui.postDelayed(automaticControlTimeout, 6000);
    }

    private void failAutomaticOpening(String message) {
        stayInSettings = true;
        automaticOpening = false;
        clearAutomaticControlTimeout();
        if (service != null) service.cancelStartupConnection(message);
        showHome();
    }

    private boolean connect() {
        startup.consume();
        if (service == null) { connecting = true; return false; }
        final String url;
        try { url = UrlPolicy.normalize(addressInput.getText().toString()); }
        catch (IllegalArgumentException e) { addressInput.setError(e.getMessage()); return false; }
        connecting = false;
        boolean useRelay = mobileModeButton.isChecked();
        boolean background = backgroundSwitch.isChecked() || useRelay;
        applyingConnectionSettings = true;
        try {
            prefs.edit().putString("address", url).putBoolean("background", background).putBoolean("relay", useRelay).apply();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(addressInput.getWindowToken(), 0);
            if (background && !enableBackground()) return false;
            if (useRelay) {
                service.startRelay(url);
                if (!service.isRelayEnabled()) { toast(service.relayStatus()); return false; }
            } else service.stopRelay();
            if (!background) service.disableKeepingAlive();
            stayInSettings = false;
            service.navigate(url);
        } finally { applyingConnectionSettings = false; }
        connectionChanged();
        return true;
    }

    private boolean enableBackground() {
        Intent intent = new Intent(this, BrowserService.class).setAction(BrowserService.ENABLE);
        try { startForegroundService(intent); }
        catch (RuntimeException e) { toast("后台服务未能启动，请检查系统后台运行权限。"); return false; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notificationAsked", false)) {
            prefs.edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY);
        }
        return true;
    }

    private void showBrowser() {
        if (service == null) return;
        clearAutomaticControlTimeout();
        automaticOpening = false;
        automaticStatus = null;
        showingBrowser = true;
        root.removeAllViews();
        webHost = new FrameLayout(this);
        root.addView(webHost, new FrameLayout.LayoutParams(-1, -1));
        WebView browser = service.browser();
        if (browser.getParent() instanceof ViewGroup) ((ViewGroup) browser.getParent()).removeView(browser);
        webHost.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
        progress.setVisibility(View.GONE);
        webHost.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));
        error = label("", 13, TEXT);
        error.setPadding(dp(16), dp(14), dp(16), dp(14));
        error.setBackground(shape(0xee50352d, 8));
        error.setVisibility(View.GONE);
        error.setOnClickListener(v -> error.setVisibility(View.GONE));
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        errorParams.setMargins(dp(10), dp(10), dp(10), 0);
        webHost.addView(error, errorParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(dp(92), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        controlParams.rightMargin = dp(6);
        webHost.addView(controls, controlParams);
        TextView settings = label("连接设置", 14, TEXT);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(shape(0xee263c3e, 12));
        settings.setContentDescription("连接设置：更改地址或选择电脑网络、手机流量");
        controls.addView(settings, new LinearLayout.LayoutParams(-1, dp(44)));
        settings.setOnClickListener(v -> openConnectionSettings());
        TextView menu = label("更多", 13, TEXT);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("酒馆口袋更多菜单，可上下拖动");
        menu.setBackground(shape(0xcc263c3e, 12));
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(-1, dp(36));
        menuParams.topMargin = dp(4);
        controls.addView(menu, menuParams);
        menu.setOnClickListener(this::showMenu);
        // Both native controls remain available even if the webpage has not loaded.
        View.OnTouchListener dragControls = new View.OnTouchListener() {
            float startY, translation;
            boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startY = event.getRawY(); translation = controls.getTranslationY(); dragging = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float delta = event.getRawY() - startY;
                    if (Math.abs(delta) > dp(8)) dragging = true;
                    if (dragging) {
                        float limit = Math.max(0, (webHost.getHeight() - controls.getHeight()) / 2f);
                        controls.setTranslationY(Math.max(-limit, Math.min(limit, translation + delta)));
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (!dragging) v.performClick();
                }
                return true;
            }
        };
        settings.setOnTouchListener(dragControls);
        menu.setOnTouchListener(dragControls);
    }

    private void showMenu(View anchor) {
        if (service == null) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "后台保持").setCheckable(true).setChecked(service.isKeepingAlive());
        menu.getMenu().add(0, 2, 1, "后台运行设置");
        menu.getMenu().add(0, 6, 2, "账号与自动登录");
        menu.getMenu().add(0, 7, 2, "连接方式：电脑 / 手机流量");
        menu.getMenu().add(0, 8, 2, "连接诊断");
        menu.getMenu().add(0, 3, 2, "刷新页面…");
        menu.getMenu().add(0, 4, 3, "在浏览器中打开");
        menu.getMenu().add(0, 5, 4, "更换地址 / 断开…");
        menu.setOnMenuItemClickListener(item -> {
            if (service == null) return true;
            switch (item.getItemId()) {
                case 1:
                    if (service.isRelayEnabled()) { toast("手机流量模式需要后台保持，请先切换为“使用电脑网络”。"); break; }
                    boolean enabled = !service.isKeepingAlive();
                    prefs.edit().putBoolean("background", enabled).apply();
                    if (enabled) enableBackground(); else service.disableKeepingAlive();
                    toast(enabled ? "已请求开启后台保持" : "后台保持已关闭");
                    break;
                case 2: batteryHelp(); break;
                case 3:
                    new AlertDialog.Builder(this).setTitle("刷新页面？")
                            .setMessage("刷新会中断当前生成，未发送的输入也可能丢失。")
                            .setNegativeButton("取消", null).setPositiveButton("刷新", (d, w) -> {
                                if (service != null) {
                                    if (error != null) error.setVisibility(View.GONE);
                                    service.reload();
                                }
                            }).show();
                    break;
                case 4: openExternal(Uri.parse(service.address())); break;
                case 5:
                    new AlertDialog.Builder(this).setTitle("断开并更换地址？")
                            .setMessage("这会结束后台保持，并中断正在进行的生成。")
                            .setNegativeButton("取消", null).setPositiveButton("断开", (d, w) -> {
                                if (service != null) service.disconnect();
                            }).show();
                    break;
                case 6: showLoginSettings(); break;
                case 7: showRelaySettings(); break;
                case 8: showDiagnostics(); break;
                default: break;
            }
            return true;
        });
        menu.show();
    }

    private void showRelaySettings() {
        if (service == null) { toast("正在准备客户端，请稍后再试"); return; }
        if (relayDialog != null && relayDialog.isShowing()) return;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(12));
        add(content, label("电脑照常启动酒馆，手机选择本次使用的网络。", 14, TEXT), 0);
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(LinearLayout.VERTICAL);
        dialogComputerMode = modeButton("使用电脑网络");
        dialogMobileMode = modeButton("使用手机流量");
        modes.addView(dialogComputerMode);
        modes.addView(dialogMobileMode);
        modes.check(service.isRelayEnabled() || prefs.getBoolean("relay", false)
                ? dialogMobileMode.getId() : dialogComputerMode.getId());
        add(content, modes, 14);
        relayStatusView = label("", 14, MINT);
        add(content, relayStatusView, 14);
        relayTrafficView = label("", 12, MUTED);
        add(content, relayTrafficView, 4);
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            chooseConnectionMode(checkedId == dialogMobileMode.getId());
            updateRelayDialog();
        });
        relayRetryButton = new Button(this);
        relayRetryButton.setText("重试连接");
        relayRetryButton.setAllCaps(false);
        relayRetryButton.setOnClickListener(v -> {
            if (service == null) return;
            if (dialogMobileMode.isChecked()) chooseConnectionMode(true);
            service.requestPluginSync();
            updateRelayDialog();
        });
        add(content, relayRetryButton, 14);
        Button diagnose = new Button(this);
        diagnose.setText("连接诊断");
        diagnose.setAllCaps(false);
        diagnose.setOnClickListener(v -> showDiagnostics());
        add(content, diagnose, 6);
        add(content, label("首次使用流量模式：在电脑安装一次配套酒馆插件，然后重启酒馆。打开酒馆并登录后会自动连接。\n\n手机需同时连接局域网 Wi-Fi 和移动数据。已开启且覆盖本应用的手机代理会优先用于外网；代理实际使用流量还是 Wi-Fi 由代理和系统决定。校园网断网时，请先确认手机代理本身能用。\n\n切换网络或代理可能中断当前回复，请在本轮结束后操作。", 12, MUTED), 16);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        relayDialog = new AlertDialog.Builder(this).setTitle("连接方式")
                .setView(scroll).setPositiveButton("完成", null).create();
        relayDialog.setOnDismissListener(d -> ui.removeCallbacks(relayTicker));
        relayDialog.show();
        updateRelayDialog();
        ui.post(relayTicker);
    }

    private void chooseConnectionMode(boolean mobile) {
        if (service == null) return;
        if (!mobile) {
            prefs.edit().putBoolean("relay", false).apply();
            service.stopRelay();
            if (computerModeButton != null) computerModeButton.setChecked(true);
            return;
        }
        final String serverAddress;
        try { serverAddress = UrlPolicy.normalize(showingBrowser ? service.address() : addressInput.getText().toString()); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        if (!enableBackground()) return;
        prefs.edit().putString("address", serverAddress).putBoolean("relay", true).putBoolean("background", true).apply();
        if (mobileModeButton != null) mobileModeButton.setChecked(true);
        if (backgroundSwitch != null) backgroundSwitch.setChecked(true);
        service.startRelay(serverAddress);
    }

    private void updateRelayDialog() {
        if (service == null || relayDialog == null || !relayDialog.isShowing()) return;
        relayStatusView.setText(service.relayStatus());
        relayTrafficView.setText(String.format(Locale.CHINA, "本次中转累计：发送 %.2f MB · 接收 %.2f MB（含局域网网页）",
                service.relayUploadedBytes() / 1048576.0, service.relayDownloadedBytes() / 1048576.0));
        relayTrafficView.setVisibility(dialogMobileMode.isChecked() ? View.VISIBLE : View.GONE);
    }

    private RadioButton modeButton(String title) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(title);
        button.setTextColor(TEXT);
        button.setTextSize(15);
        button.setButtonTintList(android.content.res.ColorStateList.valueOf(MINT));
        button.setPadding(0, dp(6), 0, dp(6));
        return button;
    }

    private void showDiagnostics() {
        startup.consume();
        if (service == null) { toast("正在准备客户端，请稍后再试。"); return; }
        if (diagnosticsDialog != null && diagnosticsDialog.isShowing()) return;
        diagnosticsText = label("正在读取手机和电脑的连接信息…", 13, TEXT);
        diagnosticsText.setTextIsSelectable(true);
        diagnosticsText.setPadding(dp(22), dp(16), dp(22), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(diagnosticsText);
        latestDiagnostics = "";
        diagnosticsDialog = new AlertDialog.Builder(this).setTitle("连接诊断")
                .setView(scroll).setPositiveButton("关闭", null)
                .setNeutralButton("复制诊断", null).setNegativeButton("刷新", null).create();
        diagnosticsDialog.show();
        diagnosticsDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (latestDiagnostics.isEmpty()) return;
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("酒馆连接诊断", latestDiagnostics));
            toast("诊断已复制：含目标域名和状态，不含请求正文。");
        });
        diagnosticsDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> refreshDiagnostics());
        refreshDiagnostics();
    }

    private void refreshDiagnostics() {
        if (service == null || diagnosticsDialog == null || !diagnosticsDialog.isShowing()) return;
        AlertDialog current = diagnosticsDialog;
        current.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        current.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        diagnosticsText.setText("正在读取手机和电脑的连接信息…");
        service.diagnoseRelay(report -> {
            if (isFinishing() || isDestroyed() || diagnosticsDialog != current || !current.isShowing()) return;
            latestDiagnostics = report;
            diagnosticsText.setText(report);
            current.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            current.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
        });
    }

    @Override public void relayChanged() {
        rememberSuccessfulStartup();
        updateRelayDialog();
        if (automaticOpening && service != null && service.hasLoadedPage()) connectionChanged();
        else updateConnectionUi();
    }

    private void showLoginSettings() {
        startup.consume();
        if (service == null) { toast("正在准备客户端，请稍后再试"); return; }
        final String serverAddress;
        try {
            serverAddress = UrlPolicy.normalize(showingBrowser ? service.address() : addressInput.getText().toString());
        } catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        CheckBox automatic = new CheckBox(this);
        automatic.setText("打开酒馆时自动填写并登录");
        automatic.setPadding(dp(18), dp(12), dp(18), dp(12));
        automatic.setChecked(service.credentials().hasAutoLogin(serverAddress));
        new AlertDialog.Builder(this).setTitle("账号与自动登录")
                .setMessage(AuthScope.origin(serverAddress) + "\n\n连接成功后，下次启动会自动打开酒馆；此选项控制是否自动提交已保存的账号密码。关闭它仍会保留账号，连接时需要手动登录。清除保存信息不会退出当前会话。")
                .setView(automatic).setNegativeButton("取消", null)
                .setNeutralButton("清除账号密码", (d, w) -> {
                    if (service != null) {
                        service.forgetLogin(serverAddress);
                        prefs.edit().putBoolean("remember:" + AuthScope.origin(serverAddress), false).apply();
                        toast("已清除该地址保存的账号密码和自动登录设置");
                    }
                })
                .setPositiveButton("保存设置", (d, w) -> {
                    if (service == null) return;
                    try {
                        service.setAutomaticLogin(serverAddress, automatic.isChecked());
                        if (automatic.isChecked() && !service.credentials().hasAutoLogin(serverAddress))
                            toast("请先在登录框中勾选“记住账号密码”，成功登录后即可开启自动登录。");
                        else toast("登录设置已保存");
                    } catch (GeneralSecurityException e) { toast("未能安全保存设置，请重新登录后再试。"); }
                }).show();
    }

    private void batteryHelp() {
        startup.consume();
        PowerManager manager = getSystemService(PowerManager.class);
        boolean allowed = manager.isIgnoringBatteryOptimizations(getPackageName());
        new AlertDialog.Builder(this).setTitle("让后台连接更稳定")
                .setMessage((allowed ? "系统电池优化：已豁免。\n\n" : "系统电池优化：尚未豁免。\n\n")
                        + "1. 允许通知，开启客户端的“后台保持”。\n"
                        + "2. 在电池设置里选择“不限制 / 允许后台运行”；如有自启动开关，也请允许。\n"
                        + "3. 可在最近任务里锁定本应用。\n\n"
                        + "厂商省电、断网、电脑休眠或强行停止仍可能中断生成；客户端不会自动重新生成。")
                .setNegativeButton("知道了", null)
                .setNeutralButton("应用设置", (d, w) -> launchSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .setPositiveButton(allowed ? "电池设置" : "允许后台运行", (d, w) -> {
                    Intent intent = allowed ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                    launchSettings(intent);
                }).show();
    }

    private void launchSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException ignored) { toast("请在系统设置中找到酒馆口袋，允许后台运行。"); }
        }
    }

    @Override public void progress(int value) {
        if (!showingBrowser || progress == null) return;
        progress.setProgress(value);
        progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
    }

    @Override public void pageError(String message) {
        if (showingBrowser && error != null) {
            error.setText(message);
            error.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        }
        updateConnectionUi();
    }

    @Override public void httpAuth(HttpAuthHandler handler, String host, String realm, String scope, boolean rejected) {
        if (!resumed || isFinishing() || isDestroyed()) return;
        if (authHandler == handler && authDialog != null && authDialog.isShowing()) return;
        if (authDialog != null) authDialog.dismiss();
        if (authHandler != null && service != null) service.cancelAuth(authHandler);
        authHandler = handler;
        CredentialStore.Entry saved = service == null ? null : service.credentials().get(scope);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        fields.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        EditText username = new EditText(this);
        username.setHint("用户名");
        username.setSingleLine(true);
        username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (saved != null) username.setText(saved.username);
        fields.addView(username);
        EditText password = new EditText(this);
        password.setHint("密码");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (saved != null) password.setText(saved.password);
        fields.addView(password);

        CheckBox remember = new CheckBox(this);
        remember.setText("记住账号密码");
        remember.setChecked(saved != null || prefs.getBoolean("remember:" + AuthScope.origin(service.address()), true));
        fields.addView(remember);
        CheckBox automatic = new CheckBox(this);
        automatic.setText("自动登录");
        automatic.setChecked(remember.isChecked() && (saved == null || saved.autoLogin));
        fields.addView(automatic);
        remember.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) automatic.setChecked(false);
        });
        automatic.setOnCheckedChangeListener((button, checked) -> {
            if (checked) remember.setChecked(true);
        });
        TextView hint = label("登录成功后，使用安卓系统密钥在本机加密保存。", 12, MUTED);
        hint.setPadding(0, dp(6), 0, dp(12));
        fields.addView(hint);
        authDialog = new AlertDialog.Builder(this).setTitle("登录酒馆")
                .setMessage(AuthScope.origin(service.address()) + (rejected ? "\n账号或密码未通过验证，请修改后重试。" : ""))
                .setView(fields).setNegativeButton("取消", (d, w) -> {
                    if (service != null) service.cancelAuth(handler);
                    authHandler = null;
                })
                .setPositiveButton("登录", null)
                .setOnCancelListener(d -> {
                    if (service != null) service.cancelAuth(handler);
                    authHandler = null;
                }).create();
        authDialog.show();
        authDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (username.getText().toString().isEmpty()) { username.setError("请填写用户名"); return; }
            if (service != null) {
                prefs.edit().putBoolean("remember:" + scope.substring(0, scope.indexOf('\n')), remember.isChecked()).apply();
                service.submitAuth(handler, scope, username.getText().toString(), password.getText().toString(),
                        remember.isChecked(), automatic.isChecked());
            }
            authHandler = null;
            authDialog.dismiss();
        });
    }

    @Override public void chooseFile(WebChromeClient.FileChooserParams params) {
        try { startActivityForResult(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE), UPLOAD); }
        catch (ActivityNotFoundException e) {
            if (service != null) service.completeFileChooser(null);
            toast("系统没有可用的文件选择器。");
        }
    }

    @Override public void exportReady(String name, String mime) {
        // An export may finish after Home/lock. Keep the bytes in the service until foregrounded.
        if (!resumed || exporting || isFinishing() || isDestroyed()) return;
        exporting = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(intent, EXPORT); }
        catch (ActivityNotFoundException e) {
            exporting = false;
            if (service != null) service.clearExport();
            toast("系统没有可用的文件保存器。");
        }
    }

    @Override public void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                || "mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme))) {
            toast("该链接类型暂不支持"); return;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException e) { toast("未找到可以打开链接的应用"); }
    }

    @Override public void sessionStopped() {
        startup.consume();
        prefs.edit().putBoolean("relay", false).apply();
        stayInSettings = true;
        showHome();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == UPLOAD && service != null) service.completeFileChooser(WebChromeClient.FileChooserParams.parseResult(result, data));
        if (request == EXPORT) {
            exporting = false;
            if (service == null) return;
            byte[] bytes = service.pendingExport();
            service.clearExport();
            if (result != RESULT_OK || data == null || data.getData() == null || bytes == null) return;
            Uri destination = data.getData();
            Context app = getApplicationContext();
            new Thread(() -> {
                try (OutputStream out = app.getContentResolver().openOutputStream(destination)) {
                    if (out == null) throw new IOException("无法打开文件");
                    out.write(bytes);
                    runOnUiThread(() -> toast("文件已保存"));
                } catch (IOException | SecurityException e) {
                    runOnUiThread(() -> toast("保存失败，请检查所选位置是否可写。"));
                }
            }, "save-export").start();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("exporting", exporting);
        state.putBoolean("stayInSettings", stayInSettings);
        state.putBoolean("startupConsidered", startup.considered());
        state.putBoolean("automaticOpening", automaticOpening);
        // Android may restore an Activity bundle after killing its process. Reopen a formerly
        // successful page only if the new service is empty; a retained service must not reload.
        state.putBoolean("resumeSuccessfulSession", !stayInSettings && service != null && service.canRememberStartup());
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (service != null) {
            service.deliverPendingExport();
            service.deliverPendingAuth();
            service.requestPluginSync();
        }
        connectionChanged();
        maybeOpenAutomatically();
        ui.removeCallbacks(relayTicker);
        ui.post(relayTicker);
    }

    @Override protected void onPause() {
        resumed = false;
        ui.removeCallbacks(relayTicker);
        // Deliberately do not call WebView.onPause() or pauseTimers(): the session may be streaming.
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFY && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED))
            toast("通知未获允许。后台保持仍可运行，可在应用设置中允许通知以便随时停止。");
    }

    @Override public void onBackPressed() {
        if (service != null && service.isConnecting()) {
            cancelOpening();
        } else if (!showingBrowser && service != null && service.hasLoadedPage()) {
            stayInSettings = false;
            showBrowser();
        } else if (showingBrowser) {
            // Back sends the app to the background; it must not navigate away from an active generation.
            moveTaskToBack(true);
        } else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (relayDialog != null) relayDialog.dismiss();
        if (diagnosticsDialog != null) diagnosticsDialog.dismiss();
        if (!isChangingConfigurations() && authHandler != null && service != null) service.cancelAuth(authHandler);
        if (authDialog != null) authDialog.dismiss();
        if (service != null) service.detach(this);
        if (bound) unbindService(connection);
        super.onDestroy();
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(sp); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
