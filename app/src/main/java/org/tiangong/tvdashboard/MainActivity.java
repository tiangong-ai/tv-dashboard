package org.tiangong.tvdashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.util.Arrays;
import java.util.List;

/** A small TV launcher with its own Gecko engine, independent of the system WebView. */
public final class MainActivity extends Activity {
    private static final String DEFAULT_URL = BuildConfig.DEFAULT_DASHBOARD_URL;
    private static final long RETRY_DELAY_MS = 15_000L;
    private static GeckoRuntime runtime;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private GeckoView browser;
    private GeckoSession session;
    private TextView status;
    private LinearLayout errorPanel;
    private TextView errorText;
    private Button retryButton;
    private AlertDialog dialog;
    private String configuredUrl;
    private String currentUrl;
    private String navigationUrl;
    private String failedUrl;
    private boolean foreground;
    private boolean failed;
    private boolean processStopped;
    private boolean browserReady;
    private boolean destroyed;
    private android.window.OnBackInvokedCallback backCallback;

    private final Runnable retry = () -> {
        if (foreground && failed && !destroyed) {
            navigate(failedUrl);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = () -> {
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                else showMenu();
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyFullscreen();
        preferences = getSharedPreferences("dashboard", MODE_PRIVATE);
        configuredUrl = validUrl(preferences.getString("url", DEFAULT_URL));
        if (configuredUrl == null) configuredUrl = DEFAULT_URL;
        currentUrl = savedInstanceState == null ? null : validUrl(savedInstanceState.getString("currentUrl"));
        createViews();

        if (runtime == null) {
            runtime = GeckoRuntime.create(getApplicationContext(),
                    new GeckoRuntimeSettings.Builder().javaScriptEnabled(true).build());
        }
        showStatus("正在准备看板…");
        runtime.getWebExtensionController().ensureBuiltIn(
                "resource://android/assets/tv-fit/", "tv-fit@tiangong.local").then(extension -> {
                    handler.post(this::onBrowserReady);
                    return null;
                }, error -> {
                    Log.w("TiangongTV", "Unable to install TV layout extension", error);
                    // The dashboard remains available if the optional layout fix fails.
                    handler.post(this::onBrowserReady);
                    return null;
                });
    }

    private void onBrowserReady() {
        if (destroyed) return;
        browserReady = true;
        startBrowserIfReady();
    }

    private void startBrowserIfReady() {
        // Installation may complete after onResume or while the Activity is in the
        // background. Only the live foreground Activity starts its first page.
        if (!browserReady || !foreground || destroyed || session != null) return;
        createSession();
        navigate(currentUrl == null ? configuredUrl : currentUrl);
    }

    private void createSession() {
        // A content crash closes its session. Use a fresh session and detach the old view
        // binding; callbacks from the old session must never change the recovery UI.
        GeckoSession previous = session;
        session = null;
        browser.releaseSession();
        if (previous != null && previous.isOpen()) previous.close();
        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                // Respect the dashboard's width=device-width viewport so its 1920x1080
                // canvas scales to the TV bounds rather than a synthetic desktop width.
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .displayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
                .build());
        processStopped = false;
        installDelegates();
        session.open(runtime);
        browser.setSession(session);
        session.setActive(foreground);
    }

    private void createViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        browser = new GeckoView(this);
        root.addView(browser, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xD0222222);
        status.setTextSize(16);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        status.setClickable(false);
        status.setFocusable(false);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        root.addView(status, statusParams);

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(32), dp(32), dp(32), dp(32));
        errorPanel.setBackgroundColor(0xF5111111);
        errorText = new TextView(this);
        errorText.setTextSize(22);
        errorText.setTextColor(Color.WHITE);
        errorText.setGravity(Gravity.CENTER);
        errorPanel.addView(errorText, new LinearLayout.LayoutParams(-1, -2));
        retryButton = new Button(this);
        retryButton.setText("立即重试");
        retryButton.setOnClickListener(view -> navigate(failedUrl));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(260), -2);
        buttonParams.topMargin = dp(24);
        errorPanel.addView(retryButton, buttonParams);
        Button menuButton = new Button(this);
        menuButton.setText("打开操作菜单");
        menuButton.setOnClickListener(view -> showMenu());
        errorPanel.addView(menuButton, new LinearLayout.LayoutParams(dp(260), -2));
        root.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
        errorPanel.setVisibility(View.GONE);
        setContentView(root);
        browser.requestFocus();
    }

    private void installDelegates() {
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession source, LoadRequest request) {
                // No external intents or popup sessions are dispatched by this application.
                if (!isCurrentSession(source) || request.target == TARGET_WINDOW_NEW
                        || validUrl(request.uri) == null) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                navigationUrl = request.uri;
                clearFailure();
                showStatus("正在加载…");
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }

            @Override
            public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession source, LoadRequest request) {
                return GeckoResult.fromValue(isCurrentSession(source) && validUrl(request.uri) != null
                        ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession source, String uri) {
                return null;
            }

            @Override
            public void onLocationChange(GeckoSession source, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                    Boolean hasUserGesture) {
                if (isCurrentSession(source) && validUrl(url) != null) currentUrl = url;
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession source, String uri, WebRequestError error) {
                // NavigationDelegate handles document loads, not failed scripts/images. Ignore
                // errors from a superseded navigation or from a blocked non-web scheme.
                if (isCurrentSession(source) && validUrl(uri) != null && sameDocument(uri, navigationUrl)) {
                    showFailure(uri, "网络错误 " + error.code);
                }
                // Abort the failed document; our native layer supplies the error UI. Returning
                // null avoids needing to whitelist data/about URIs for a browser error page.
                return null;
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession source, String url) {
                if (isCurrentSession(source) && validUrl(url) != null) {
                    navigationUrl = url;
                    clearFailure();
                    showStatus("正在加载…");
                }
            }

            @Override
            public void onPageStop(GeckoSession source, boolean success) {
                if (!isCurrentSession(source)) return;
                if (success && !failed) {
                    status.setVisibility(View.GONE);
                } else if (!failed) {
                    // A stopped/cancelled load is not necessarily a network failure. Actual
                    // document errors are handled in onLoadError, with their failed URL.
                    showStatus("加载已停止，按菜单键或返回键刷新");
                }
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(GeckoSession source) {
                onContentStopped(source, "网页进程意外停止");
            }

            @Override
            public void onKill(GeckoSession source) {
                onContentStopped(source, "网页进程被系统回收");
            }
        });
    }

    private boolean isCurrentSession(GeckoSession source) {
        return !destroyed && source == session && !processStopped;
    }

    private void onContentStopped(GeckoSession source, String reason) {
        if (!isCurrentSession(source)) return;
        String url = validUrl(failedUrl);
        if (url == null) url = validUrl(navigationUrl);
        if (url == null) url = validUrl(currentUrl);
        if (url == null) url = configuredUrl;
        processStopped = true;
        showFailure(url, reason);
    }

    private void navigate(String url) {
        String normalized = validUrl(url);
        if (normalized == null || destroyed) return;
        if (processStopped) {
            // The automatic retry waits until the Activity is in front. A menu action
            // can choose a different address while the stopped session is recovering.
            if (!foreground) {
                failedUrl = normalized;
                return;
            }
            createSession();
        }
        if (session == null || !session.isOpen()) return;
        clearFailure();
        navigationUrl = normalized;
        showStatus("正在加载…");
        session.loadUri(normalized);
    }

    private void refresh() {
        if (failed) {
            navigate(failedUrl);
        } else if (session != null && session.isOpen()) {
            clearFailure();
            showStatus("正在刷新…");
            session.reload();
        }
    }

    private void showFailure(String url, String reason) {
        if (destroyed) return;
        failed = true;
        failedUrl = url;
        status.setVisibility(View.GONE);
        String heading = processStopped ? "看板显示已暂停" : "无法连接看板";
        String recovery = processStopped ? "15 秒后自动重新打开看板。"
                : "15 秒后自动重试。请确认电视与服务器在同一网络。";
        errorText.setText(heading + "\n\n" + url + "\n" + reason
                + "\n\n" + recovery + "\n按菜单键或返回键修改地址。");
        errorPanel.setVisibility(View.VISIBLE);
        retryButton.requestFocus();
        scheduleRetry();
    }

    private void clearFailure() {
        handler.removeCallbacks(retry);
        failed = false;
        failedUrl = null;
        if (errorPanel.getVisibility() == View.VISIBLE) {
            errorPanel.setVisibility(View.GONE);
            browser.requestFocus();
        }
    }

    private void scheduleRetry() {
        handler.removeCallbacks(retry);
        if (foreground && failed && !destroyed) handler.postDelayed(retry, RETRY_DELAY_MS);
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void showMenu() {
        if (destroyed || (dialog != null && dialog.isShowing())) return;
        String[] choices = {"刷新当前页面", "打开大屏看板", "打开工作台", "修改看板地址", "系统信息", "退出应用"};
        dialog = new AlertDialog.Builder(this)
                .setTitle("天工电视看板")
                .setItems(choices, (selectedDialog, index) -> {
                    dialog = null;
                    switch (index) {
                        case 0: refresh(); break;
                        case 1: navigate(serverPage("display/")); break;
                        case 2: navigate(serverPage("")); break;
                        case 3: showAddressEditor(); break;
                        case 4: showSystemInfo(); break;
                        case 5: finish(); break;
                        default: break;
                    }
                })
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
        dialog.getListView().requestFocus();
        dialog.getListView().setSelection(0);
    }

    private String serverPage(String path) {
        return DashboardUrls.serverPage(configuredUrl, path, BuildConfig.DASHBOARD_BASE_URL);
    }

    private void showAddressEditor() {
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(20);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(configuredUrl);
        address.setSelectAllOnFocus(true);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(24), dp(8), dp(24), dp(8));
        container.addView(address, new LinearLayout.LayoutParams(-1, -2));
        dialog = new AlertDialog.Builder(this)
                .setTitle("启动时打开的看板地址")
                .setMessage("填写完整的 http:// 或 https:// 地址。\n示例：" + DEFAULT_URL)
                .setView(container)
                .setPositiveButton("保存并打开", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = validUrl(address.getText().toString());
            if (value == null) {
                address.setError("请输入有效的 HTTP(S) 地址，不包含账号密码");
                address.requestFocus();
                return;
            }
            configuredUrl = value;
            preferences.edit().putString("url", configuredUrl).apply();
            dialog.dismiss();
            navigate(configuredUrl);
        });
        address.requestFocus();
    }

    private void showSystemInfo() {
        String info = "设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid：" + Build.VERSION.RELEASE
                + "\nAPI 级别：" + Build.VERSION.SDK_INT
                + "\n支持的架构：" + Arrays.toString(Build.SUPPORTED_ABIS)
                + "\n浏览器内核：GeckoView " + BuildConfig.ENGINE_VERSION
                + "\n应用版本：" + BuildConfig.VERSION_NAME
                + "\n启动地址：" + configuredUrl
                + "\n当前地址：" + (currentUrl == null ? navigationUrl : currentUrl);
        dialog = new AlertDialog.Builder(this).setTitle("系统信息")
                .setMessage(info).setPositiveButton("关闭", null).create();
        dialog.show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU || event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (dialog != null && dialog.isShowing()) return super.dispatchKeyEvent(event);
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) showMenu();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("GestureBackNavigation") // API 33+ uses the callback registered in onCreate.
    @Override
    public void onBackPressed() {
        showMenu();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        applyFullscreen();
        startBrowserIfReady();
        if (session != null && session.isOpen()) session.setActive(true);
        scheduleRetry();
    }

    @Override
    protected void onPause() {
        foreground = false;
        handler.removeCallbacks(retry);
        if (session != null && session.isOpen()) session.setActive(false);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("currentUrl", failed ? failedUrl : (currentUrl == null ? navigationUrl : currentUrl));
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        handler.removeCallbacksAndMessages(null);
        if (dialog != null) dialog.dismiss();
        if (browser != null) browser.releaseSession();
        if (session != null && session.isOpen()) {
            session.setActive(false);
            session.stop();
            session.close();
        }
        session = null;
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @SuppressWarnings("deprecation")
    private void applyFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Require an actual network host, valid port, and no embedded credentials. */
    private static String validUrl(String value) {
        return DashboardUrls.validPage(value);
    }

    private static boolean sameDocument(String first, String second) {
        if (first == null || second == null) return false;
        return first.split("#", 2)[0].equals(second.split("#", 2)[0]);
    }
}
