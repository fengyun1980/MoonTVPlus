package com.fengyun.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
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
    private static final String PREFS_NAME = "fengyun_prefs";
    private static final String KEY_BASE_URL = "base_url";

    private FrameLayout rootContainer;
    private FrameLayout fullscreenContainer;
    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private boolean isInPipMode = false;
    private boolean isPlaying = false;
    private String currentTitle = "";
    private String currentCover = "";
    private String currentBaseUrl = "";

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
        fullscreenContainer = findViewById(R.id.fullscreen_container);
        webView = findViewById(R.id.webview);

        setupImmersive();
        setupWebView();
        requestNotificationPermissionIfNeeded();
        bindPlaybackService();

        // 从 SharedPreferences 读取 BASE_URL，没有则弹窗让用户填
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_BASE_URL, null);

        if (savedUrl != null && !savedUrl.trim().isEmpty()) {
            currentBaseUrl = buildAppUrl(savedUrl);
            webView.loadUrl(currentBaseUrl);
        } else {
            showBaseUrlDialog(true);
        }
    }

    /**
     * 弹出服务器地址输入对话框
     * @param firstLaunch true=首次启动，必须填；false=用户主动修改
     */
    private void showBaseUrlDialog(boolean firstLaunch) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString(KEY_BASE_URL, "");

        EditText input = new EditText(this);
        input.setHint("https://your-server.com");
        input.setText(current);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(48, 24, 48, 0);
        input.setLayoutParams(lp);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(firstLaunch ? "🏝️ 欢迎使用 MoonTV" : "修改服务器地址")
                .setMessage(firstLaunch
                        ? "请输入你的服务端地址（例如 https://your-server.com）\n\n" +
                          "后续可在 APP 设置里修改。长按返回键也可弹出此对话框。"
                        : "输入新的服务端地址：")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) {
                        // 空地址再弹一次
                        showBaseUrlDialog(firstLaunch);
                        return;
                    }
                    String url = buildAppUrl(raw);
                    prefs.edit().putString(KEY_BASE_URL, url).apply();
                    currentBaseUrl = url;
                    if (firstLaunch) {
                        webView.loadUrl(url);
                    } else {
                        webView.loadUrl(url);
                    }
                })
                .setNegativeButton(firstLaunch ? "退出" : "取消", (dialog, which) -> {
                    if (firstLaunch) {
                        finish();
                    }
                });

        builder.setCancelable(!firstLaunch); // 首次启动不可取消
        builder.show();
    }

    // ──────────────────────────────────────────────────────────────
    // 沉浸式 & 全面屏适配
    // ──────────────────────────────────────────────────────────────

    private void setupImmersive() {
        Window window = getWindow();
        // Android 11+ (API 30+) 用 setDecorFitsSystemWindows
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            // Android 10 及以下用旧 flag
            @SuppressLint("InlinedApi")
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        // 保持屏幕常亮（播放视频时）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        // ⚠️ 重要：先开 JS，再注入 Bridge（顺序反了小米 HyperOS 上会失效）
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
        s.setUserAgentString(s.getUserAgentString() + " MoonTV Android");

        // WebView 默认已硬件加速，不要 setLayerType 覆盖它！
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        // JS Bridge —— 在 setJavaScriptEnabled(true) 之后注入
        webView.addJavascriptInterface(new MobileJsBridge(), "AndroidBridge");

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
                // 页面加载完后验证 AndroidBridge 注入成功
                view.evaluateJavascript(
                        "typeof window.AndroidBridge === 'object' ? 'OK' : 'FAIL'",
                        value -> {
                            android.util.Log.d("MoonTV", "Bridge injection: " + value
                                    + " at url=" + url);
                        }
                );
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.proceed(); // 内网自签名证书放行
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    android.util.Log.e("MoonTV", "WebView 加载错误: " + error.getDescription()
                            + " code=" + error.getErrorCode()
                            + " url=" + request.getUrl());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // ─── 全屏视频处理（3 参数版本，Android 2.1+）────
            @Override
            public void onShowCustomView(View view, int requestedOrientation, CustomViewCallback callback) {
                android.util.Log.i("MoonTV", "onShowCustomView (3-param) orient=" + requestedOrientation);
                enterFullscreenVideo(view, callback);
            }

            // ─── 全屏视频处理（2 参数版本，旧版 fallback）────
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                android.util.Log.i("MoonTV", "onShowCustomView (2-param)");
                enterFullscreenVideo(view, callback);
            }

            @Override
            public void onHideCustomView() {
                android.util.Log.i("MoonTV", "onHideCustomView");
                exitFullscreenVideo();
            }
        });

        // WebChromeClient 全屏视频的核心实现（抽出来避免两个版本重复）
    }

    /**
     * 进入全屏视频 —— 隐藏所有系统栏，视频占满整个屏幕，强制横屏
     */
    private void enterFullscreenVideo(View videoView, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = videoView;
        customViewCallback = callback;

        // 隐藏 WebView
        webView.setVisibility(View.GONE);

        // 把 video view 加到全屏容器
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        fullscreenContainer.addView(videoView, lp);
        fullscreenContainer.setVisibility(View.VISIBLE);

        // ⚠️ 关键：强制横屏（全屏视频时屏幕自动旋转）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // 强制全屏 —— 隐藏状态栏、导航栏，延伸到刘海/挖孔屏区域
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        android.util.Log.i("MoonTV", "✅ 视频已进入全屏 + 强制横屏");
    }

    /**
     * 退出全屏视频 —— 恢复竖屏 + 系统 UI
     */
    private void exitFullscreenVideo() {
        if (customView == null) return;

        // ⚠️ 恢复竖屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        setupImmersive();

        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        customView = null;

        webView.setVisibility(View.VISIBLE);
        webView.requestFocus();

        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        android.util.Log.i("MoonTV", "✅ 已退出全屏 + 恢复竖屏");
    }

    // ──────────────────────────────────────────────────────────────
    // Activity 生命周期（全屏视频时处理配置变更）
    // ──────────────────────────────────────────────────────────────

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 如果在全屏视频中，重新应用全屏 flag（某些机型旋转后会丢失）
        if (customView != null) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private static String buildAppUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) url = "http://192.168.1.10:3000";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url; // 手机版走首页 /
    }

    // ──────────────────────────────────────────────────────────────
    // JS Bridge —— Web 端 ↔ 原生通信
    // ──────────────────────────────────────────────────────────────

    private class MobileJsBridge {
        private static final String TAG = "MoonTV.Bridge";

        /** Web 端播放开始时调用 */
        @JavascriptInterface
        public void onPlayStart(final String title, final String coverUrl, final String episode) {
            android.util.Log.i(TAG, "onPlayStart title=" + title + " episode=" + episode
                    + " cover=" + (coverUrl != null ? coverUrl.substring(0, Math.min(60, coverUrl.length())) : "null"));
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
            android.util.Log.i(TAG, "onPlayPause");
            mainHandler.post(() -> {
                isPlaying = false;
                if (playbackService != null) playbackService.notifyPaused();
            });
        }

        /** Web 端恢复播放 */
        @JavascriptInterface
        public void onPlayResume() {
            android.util.Log.i(TAG, "onPlayResume");
            mainHandler.post(() -> {
                isPlaying = true;
                if (playbackService != null) playbackService.notifyPlaying();
            });
        }

        /** Web 端停止播放 */
        @JavascriptInterface
        public void onPlayEnd() {
            android.util.Log.i(TAG, "onPlayEnd");
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
            // 进度日志可能刷屏，用 debug 级别
            android.util.Log.d(TAG, "onPlayProgress " + positionSec + "/" + durationSec);
            mainHandler.post(() -> {
                if (playbackService != null) {
                    playbackService.updateProgress(positionSec, durationSec);
                }
            });
        }

        /** 当前是否在 PiP 模式 */
        @JavascriptInterface
        public boolean isInPip() {
            android.util.Log.d(TAG, "isInPip=" + isInPipMode);
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
     * 长按返回键弹出"修改服务器地址"对话框
     */
    private long lastBackPressTime = 0;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 长按返回键 → 修改服务器地址
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.isLongPress()) {
            showBaseUrlDialog(false);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

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
            exitFullscreenVideo();
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
