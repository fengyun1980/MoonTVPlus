package com.fengyun.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 风云影视 手机版主 Activity
 * <p>
 * 核心能力：
 * 1. WebView 壳 —— 加载服务端页面，硬件加速
 * 2. JS Bridge —— 接收 Web 端播放事件，驱动原生前台服务
 * 3. PiP 画中画 —— 用户按 Home / 最近任务键时自动进入小窗播放
 * 4. 返回键栈 —— 支持网页内历史返回
 */
public class MainActivity extends Activity {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 1001;
    private static final String PREFS_NAME = "fengyun_mobile_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String DEFAULT_SERVER_URL = "";

    /** WebView 加载过登录页后，是否已自动提交过表单（避免用户手动修改后又被自动提交） */
    private boolean loginAutoSubmitted = false;

    private FrameLayout rootContainer;
    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private boolean isInPipMode = false;
    private boolean isPlaying = false;
    private String currentTitle = "";
    private String currentCover = "";

    private VideoPlaybackService playbackService;
    private boolean serviceBound = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 与播放服务绑定 */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            VideoPlaybackService.LocalBinder binder = (VideoPlaybackService.LocalBinder) service;
            playbackService = binder.getService();
            serviceBound = true;
            // 恢复服务状态
            if (isPlaying) {
                playbackService.restoreStateFromActivity(currentTitle, currentCover);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setContentView(R.layout.activity_main);
        rootContainer = findViewById(R.id.root_container);
        webView = findViewById(R.id.webview);

        setupImmersive();
        setupWebView();
        requestNotificationPermissionIfNeeded();
        bindPlaybackService();

        // 读已保存的服务器地址 + 账号密码
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_SERVER_URL, null);
        String savedUsername = prefs.getString(KEY_USERNAME, null);
        String savedPassword = prefs.getString(KEY_PASSWORD, null);

        if (savedUrl != null && !savedUrl.isEmpty()) {
            // 服务器地址已存，加载页面（登录页会自动注入表单）
            webView.loadUrl(buildAppUrl(savedUrl));
        } else {
            // 没有服务器地址 → 先弹窗填地址，存完再弹窗填账号密码
            askForServerUrlThenCredentials(prefs);
        }
        // 账号密码没存的话，下次进登录页时 Web 端也会让用户手动填
    }

    // ──────────────────────────────────────────────────────────────
    // 沉浸式 & 全面屏适配
    // ──────────────────────────────────────────────────────────────

    private void setupImmersive() {
        Window window = getWindow();
        // 允许内容延伸到状态栏/导航栏（适配全面屏、刘海屏、灵动岛区域）
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        // 保持屏幕常亮（播放视频时）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupImmersive();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // WebView 核心设置
    // ──────────────────────────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        // 硬件加速（播放视频必需）
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        // JS Bridge —— Web 端通过 window.AndroidBridge.onPlayStart(...) 调用
        webView.addJavascriptInterface(new MobileJsBridge(), "AndroidBridge");

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // 允许自动播放
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setUserAgentString(s.getUserAgentString() + " FengYunMobile WebView");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 外链（tel:, mailto: 等）交给系统处理
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme != null && (scheme.equals("tel") || scheme.equals("mailto") || scheme.equals("intent"))) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 进入登录页 → 尝试自动填表单并提交（只做一次，避免用户手动改密码后被反复覆盖）
                if (url != null && (url.contains("/login") || url.contains("login="))
                        && !loginAutoSubmitted) {
                    mainHandler.postDelayed(() -> autoFillLoginForm(), 500);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.proceed(); // 内网自签名证书放行
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // 全屏视频（HTML5 video 进入全屏）
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                rootContainer.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    private void hideCustomView() {
        if (customView == null) return;
        rootContainer.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    /** 弹窗①：输入服务器地址 → 存完 → 弹窗②：输入账号密码 → 存完 → 加载 WebView */
    private void askForServerUrlThenCredentials(final SharedPreferences prefs) {
        final EditText urlInput = new EditText(this);
        urlInput.setHint("例如 http://your-server.com:8880");
        urlInput.setSingleLine(true);
        urlInput.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("① 输入服务器地址")
                .setMessage("首次使用需要填写你的服务端地址")
                .setView(urlInput)
                .setCancelable(false)
                .setPositiveButton("下一步", (dialog, which) -> {
                    String rawUrl = urlInput.getText().toString().trim();
                    if (rawUrl.isEmpty()) {
                        // 地址不能为空，重新弹
                        askForServerUrlThenCredentials(prefs);
                        return;
                    }
                    String normalized = buildAppUrl(rawUrl);
                    prefs.edit().putString(KEY_SERVER_URL, normalized).apply();
                    // 继续弹第二个框
                    askForCredentials(prefs, normalized);
                })
                .show();
    }

    /** 弹窗②：输入用户名 + 密码 */
    private void askForCredentials(final SharedPreferences prefs, final String normalizedUrl) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        final EditText usernameInput = new EditText(this);
        usernameInput.setHint("用户名");
        usernameInput.setSingleLine(true);
        container.addView(usernameInput);

        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("密码");
        passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setSingleLine(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        passwordInput.setLayoutParams(p);
        container.addView(passwordInput);

        new AlertDialog.Builder(this)
                .setTitle("② 输入账号密码")
                .setMessage("用于自动登录")
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("确定", (dialog, which) -> {
                    String u = usernameInput.getText().toString().trim();
                    String pw = passwordInput.getText().toString();
                    if (!u.isEmpty()) prefs.edit().putString(KEY_USERNAME, u).apply();
                    if (!pw.isEmpty()) prefs.edit().putString(KEY_PASSWORD, pw).apply();
                    loginAutoSubmitted = false;
                    webView.loadUrl(normalizedUrl);
                })
                .setNegativeButton("跳过", (dialog, which) -> {
                    // 不存账号密码，直接进入，之后在 Web 登录页手动填
                    webView.loadUrl(normalizedUrl);
                })
                .show();
    }

    /** 重置弹窗：修改服务器地址 + 账号密码 */
    private void showResetSettingsDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("服务器地址");
        urlInput.setText(prefs.getString(KEY_SERVER_URL, ""));
        urlInput.setSingleLine(true);
        container.addView(urlInput);

        final EditText userInput = new EditText(this);
        userInput.setHint("用户名（可留空）");
        userInput.setText(prefs.getString(KEY_USERNAME, ""));
        userInput.setSingleLine(true);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p2.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        userInput.setLayoutParams(p2);
        container.addView(userInput);

        final EditText passInput = new EditText(this);
        passInput.setHint("密码（可留空）");
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setText(prefs.getString(KEY_PASSWORD, ""));
        passInput.setSingleLine(true);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p3.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        passInput.setLayoutParams(p3);
        container.addView(passInput);

        new AlertDialog.Builder(this)
                .setTitle("修改服务器和账号")
                .setView(container)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = buildAppUrl(urlInput.getText().toString().trim());
                    String u = userInput.getText().toString().trim();
                    String pw = passInput.getText().toString();
                    prefs.edit()
                            .putString(KEY_SERVER_URL, url)
                            .putString(KEY_USERNAME, u)
                            .putString(KEY_PASSWORD, pw)
                            .apply();
                    loginAutoSubmitted = false;
                    webView.loadUrl(url);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @SuppressWarnings("unused")
    private void showResetServerUrlDialog() {
        showResetSettingsDialog();
    }

    private static String buildAppUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) url = DEFAULT_SERVER_URL;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url; // 手机版走首页 /
    }

    /**
     * WebView 加载到登录页时自动注入 JS 填表单并提交。
     * React 受控组件不能直接 .value = 'xxx'，必须绕过 native setter 再触发 input 事件。
     */
    private void autoFillLoginForm() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final String username = prefs.getString(KEY_USERNAME, null);
        final String password = prefs.getString(KEY_PASSWORD, null);
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return; // 没存账号密码，让用户手动填
        }
        loginAutoSubmitted = true;

        // 转义 JS 字符串里的特殊字符
        String jsUser = username.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
        String jsPass = password.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");

        String js = "(function(){"
                + "var u=document.getElementById('username');"
                + "var p=document.getElementById('password');"
                + "if(!u||!p) return;"
                + "var setter=Object.getOwnPropertyDescriptor("
                + "window.HTMLInputElement.prototype,'value').set;"
                + "setter.call(u,'" + jsUser + "');"
                + "u.dispatchEvent(new Event('input',{bubbles:true}));"
                + "setter.call(p,'" + jsPass + "');"
                + "p.dispatchEvent(new Event('input',{bubbles:true}));"
                + "var r=document.getElementById('remember-password');"
                + "if(r&&!r.checked) r.click();"
                + "var btn=document.querySelector('button[type=\"submit\"]')"
                + "||document.querySelector('button');"
                + "if(btn){btn.click();}"
                + "})();";

        if (webView != null) {
            webView.evaluateJavascript(js, null);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // JS Bridge —— Web 端 ↔ 原生通信
    // ──────────────────────────────────────────────────────────────

    private class MobileJsBridge {

        /** Web 端播放开始时调用 */
        @JavascriptInterface
        public void onPlayStart(final String title, final String coverUrl, final String episode) {
            mainHandler.post(() -> {
                isPlaying = true;
                currentTitle = title == null ? "" : title;
                currentCover = coverUrl == null ? "" : coverUrl;
                startOrUpdatePlaybackService();
            });
        }

        /** Web 端暂停 */
        @JavascriptInterface
        public void onPlayPause() {
            mainHandler.post(() -> {
                isPlaying = false;
                if (playbackService != null) playbackService.notifyPaused();
            });
        }

        /** Web 端恢复播放 */
        @JavascriptInterface
        public void onPlayResume() {
            mainHandler.post(() -> {
                isPlaying = true;
                if (playbackService != null) playbackService.notifyPlaying();
            });
        }

        /** Web 端停止播放 */
        @JavascriptInterface
        public void onPlayEnd() {
            mainHandler.post(() -> {
                isPlaying = false;
                currentTitle = "";
                currentCover = "";
                stopPlaybackService();
            });
        }

        /** 进度更新（秒） */
        @JavascriptInterface
        public void onPlayProgress(final int positionSec, final int durationSec) {
            mainHandler.post(() -> {
                if (playbackService != null) {
                    playbackService.updateProgress(positionSec, durationSec);
                }
            });
        }

        /** 当前是否在 PiP 模式 */
        @JavascriptInterface
        public boolean isInPip() {
            return isInPipMode;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 播放服务管理
    // ──────────────────────────────────────────────────────────────

    private void bindPlaybackService() {
        Intent intent = new Intent(this, VideoPlaybackService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startOrUpdatePlaybackService() {
        Intent intent = new Intent(this, VideoPlaybackService.class);
        intent.setAction(VideoPlaybackService.ACTION_START_PLAYBACK);
        intent.putExtra(VideoPlaybackService.EXTRA_TITLE, currentTitle);
        intent.putExtra(VideoPlaybackService.EXTRA_COVER_URL, currentCover);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        // 等 bind 回来后同步状态
        mainHandler.postDelayed(() -> {
            if (playbackService != null) {
                playbackService.restoreStateFromActivity(currentTitle, currentCover);
            }
        }, 300);
    }

    private void stopPlaybackService() {
        Intent intent = new Intent(this, VideoPlaybackService.class);
        intent.setAction(VideoPlaybackService.ACTION_STOP_PLAYBACK);
        startService(intent);
    }

    // ──────────────────────────────────────────────────────────────
    // PiP 画中画核心
    // ──────────────────────────────────────────────────────────────

    /**
     * 是否支持画中画（Android 8.0+）
     */
    private boolean supportsPip() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * 用户按 Home / 最近任务键触发 —— 播放中自动进入 PiP
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (supportsPip() && isPlaying && !isInPipMode) {
            enterPictureInPictureModeSafe();
        }
    }

    /**
     * 构建 PiP 参数（宽高比跟随视频）
     */
    private void enterPictureInPictureModeSafe() {
        if (!supportsPip() || isInPipMode) return;
        try {
            // 默认 16:9，匹配视频常见比例
            Rational aspectRatio = new Rational(16, 9);

            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .setAutoEnterEnabled(false)
                    .setSeamlessResizeEnabled(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 支持 source rect hint
                Rect sourceRect = new Rect();
                webView.getGlobalVisibleRect(sourceRect);
                builder.setSourceRectHint(sourceRect);
            }

            enterPictureInPictureMode(builder.build());
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 某些 ROM 可能限制 PiP
            e.printStackTrace();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        onPipModeChanged(isInPictureInPictureMode);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        onPipModeChanged(isInPictureInPictureMode);
    }

    private void onPipModeChanged(boolean nowInPip) {
        isInPipMode = nowInPip;

        // 告诉 WebView 当前状态，Web 端可以据此做 UI 调整
        if (webView != null) {
            String script = "window.dispatchEvent(new CustomEvent('moontv:pip-changed',{detail:{isInPip:"
                    + (nowInPip ? "true" : "false") + "}}));";
            webView.evaluateJavascript(script, null);
        }

        if (nowInPip) {
            // 进入 PiP：保持 WebView 继续播放
            // WebView 不需要额外处理，进入 PiP 不会 destroy Activity
        } else {
            // 退出 PiP：恢复全屏
            setupImmersive();
            // 通知 Web 端可以恢复大窗口 UI
            if (webView != null) {
                String script = "window.dispatchEvent(new CustomEvent('moontv:pip-exit',{}));";
                webView.evaluateJavascript(script, null);
            }
        }
    }

    /**
     * 返回键 —— 优先 PiP → 其次 WebView 历史 → 最后退出
     */
    @Override
    public void onBackPressed() {
        // 1. 如果在 PiP 里，先退出 PiP
        if (isInPipMode && supportsPip()) {
            try {
                finish(); // 系统会自动从 PiP 恢复到全屏
                return;
            } catch (Exception ignored) {}
        }
        // 2. 全屏视频
        if (customView != null) {
            hideCustomView();
            return;
        }
        // 3. WebView 历史栈
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // 4. 播放中先进入 PiP 而不是退出
        if (supportsPip() && isPlaying) {
            enterPictureInPictureModeSafe();
            return;
        }
        super.onBackPressed();
    }

    // ──────────────────────────────────────────────────────────────
    // 通知权限（Android 13+）
    // ──────────────────────────────────────────────────────────────

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 通知权限被拒绝也不崩溃，只是 MediaStyle 通知可能不显示
        // 小米超级岛在 HyperOS 上有部分能力不受通知权限影响（前台服务自动上岛）
    }

    // ──────────────────────────────────────────────────────────────
    // Activity 生命周期
    // ──────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (playbackService != null) playbackService.notifyActivityResumed();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 注意：不要在这里自动暂停视频！
        // 用户按 Home 进入 PiP 时会触发 onStop，我们希望视频继续播放
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        stopPlaybackService();
        if (webView != null) {
            rootContainer.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
