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
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        // ✅ 关键：用 WindowInsets 给根容器加 padding
        // 让 WebView 内容从状态栏下方开始，不会被状态栏盖住
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets statusBar = insets.getInsets(android.view.WindowInsets.Type.statusBars());
                android.graphics.Insets navBar = insets.getInsets(android.view.WindowInsets.Type.navigationBars());
                v.setPadding(0, statusBar.top, 0, navBar.bottom);
                android.util.Log.d("MoonTV", "WindowInsets: statusBar.top=" + statusBar.top + " navBar.bottom=" + navBar.bottom);
                return insets;
            });
        }
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
        s.setSupportZoom(false);          // 禁止双指缩放
        s.setBuiltInZoomControls(false);  // 禁用内置缩放控件
        s.setDisplayZoomControls(false);  // 不显示缩放按钮
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

        // ✅ 开启 WebView 远程调试（你可以用 Chrome://inspect 看真实 DOM）
        WebView.setWebContentsDebuggingEnabled(true);
        android.util.Log.i("MoonTV", "✅ WebView 调试已开启，在电脑 Chrome 访问 chrome://inspect 看 DOM");

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

                // ✅ 方案 1：注入 <style> 标签（最简单，页面一加载就生效）
                // 从源码已知工具栏里有 title='下载视频' 按钮
                String injectStyle =
                    "(function() {" +
                    "  var css = 'div[class*=\"mt-3\"] div[class*=\"bg-white\"] { display: none !important; }';" +
                    "  try {" +
                    "    // :has() 在 Android 13+ WebView 支持" +
                    "    css = 'div:has(button[title=\"下载视频\"]) { display: none !important; height: 0 !important; overflow: hidden !important; }';" +
                    "  } catch(e) {}" +
                    "  var style = document.createElement('style');" +
                    "  style.textContent = css;" +
                    "  style.id = 'moontv-hide-toolbar';" +
                    "  (document.head || document.documentElement).appendChild(style);" +
                    "  console.log('[MoonTV] ✅ Style injected');" +
                    "})();";

                // ✅ 方案 2：MutationObserver（兜底，DOM 变化时再应用一次）
                String injectObserver =
                    "(function() {" +
                    "  function hideToolbar() {" +
                    "    var btns = document.querySelectorAll('button[title*=\"下载\"], button[title*=\"PotPlayer\"], button[title*=\"App打开\"], button[title*=\"PC Client\"]');" +
                    "    btns.forEach(function(b) {" +
                    "      // 向上找 3 层找到外层容器 div.mt-3" +
                    "      var p = b.parentElement;" +
                    "      for (var i = 0; i < 5 && p; i++) {" +
                    "        if (p.className && typeof p.className === 'string' && p.className.indexOf('mt-3') >= 0) {" +
                    "          p.style.setProperty('display', 'none', 'important');" +
                    "          p.style.setProperty('height', '0', 'important');" +
                    "          p.setAttribute('hidden', '');" +
                    "          console.log('[MoonTV] ✅ Hidden toolbar container: ' + p.className);" +
                    "          break;" +
                    "        }" +
                    "        p = p.parentElement;" +
                    "      }" +
                    "    });" +
                    "    // 如果 :has 选择器没生效，手动隐藏 bg-white 那个 div" +
                    "    document.querySelectorAll('div.mt-3').forEach(function(d) {" +
                    "      if (d.querySelectorAll('div.bg-white, div.\\/50').length > 0 || d.children.length >= 1) {" +
                    "        var inner = d.querySelector('div[class*=\"bg-white\"]') || d.querySelector('div[class*=\"\\/50\"]');" +
                    "        if (inner && inner.querySelectorAll('button').length >= 3) {" +
                    "          d.style.display = 'none';" +
                    "          console.log('[MoonTV] ✅ Hidden via inner bg-white div');" +
                    "        }" +
                    "      }" +
                    "    });" +
                    "  }" +
                    "  hideToolbar();" +
                    "  // 持续监听 15 秒" +
                    "  var tries = 0;" +
                    "  var interval = setInterval(function() {" +
                    "    tries++;" +
                    "    hideToolbar();" +
                    "    if (tries > 30) clearInterval(interval);" +
                    "  }, 500);" +
                    "  console.log('[MoonTV] ✅ Observer started, will hide toolbar for 15s');" +
                    "})();";

                view.evaluateJavascript(injectStyle, null);
                view.evaluateJavascript(injectObserver, null);

                // 验证 Bridge 注入
                view.evaluateJavascript(
                        "typeof window.AndroidBridge === 'object' ? 'OK' : 'FAIL'",
                        value -> android.util.Log.d("MoonTV", "Bridge: " + value));
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
            // ✅ console.log → Android logcat
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                android.util.Log.i("MoonTV.Web", "[" + cm.lineNumber() + "] " + cm.message());
                return true;
            }

            // ─── 全屏视频处理（3 参数版本）────
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

        // ✅ 移除 padding，让全屏视频占满整个屏幕
        rootContainer.setPadding(0, 0, 0, 0);

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

    /** 是否支持 PiP（Android 8.0+） */
    private boolean supportsPip() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * 用户按 Home / 最近任务键 —— 播放中自动进入 PiP（最原生最可靠）
     * 悬浮窗作为 PiP 失败后的降级方案
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isInPipMode || isInFloatingWindow || webView == null) return;

        // 先启动播放服务（上岛），不管 PiP 成不成功都要有 MediaStyle 通知
        startOrUpdatePlaybackService();

        // 方案 1：直接尝试 PiP（同步调用，最快）
        if (supportsPip()) {
            try {
                android.util.Log.i("MoonTV", "🚀 尝试进入 PiP...");
                enterPictureInPictureModeSafe();
                android.widget.Toast.makeText(this, "✅ 已进入小窗模式",
                    android.widget.Toast.LENGTH_SHORT).show();
                return;
            } catch (Exception e) {
                android.util.Log.w("MoonTV", "⚠️ PiP 异常: " + e.getMessage());
            }
        }

        // 方案 2：降级悬浮窗（需要先强制 video 进全屏拿到 customView）
        android.widget.Toast.makeText(this, "PiP 不可用，尝试悬浮窗...",
            android.widget.Toast.LENGTH_SHORT).show();
        android.util.Log.i("MoonTV", "🚀 降级悬浮窗...");
        tryEnterFloatingWindow();
    }

    /**
     * 注入 JS 检测 video 是否在播放，然后回调
     */
    private void checkVideoPlayingAndThen(java.util.function.Consumer<Boolean> callback) {
        if (isPlaying) {
            callback.accept(true);
            return;
        }
        if (webView == null) {
            callback.accept(false);
            return;
        }
        webView.evaluateJavascript(
            "(function() {" +
            "  var v = document.querySelector('video');" +
            "  return v && !v.paused && !v.ended && v.readyState > 2 ? 'yes' : 'no';" +
            "})();",
            val -> callback.accept(val != null && val.contains("yes"))
        );
    }

    // ──────────────────────────────────────────────────────────────
    // 悬浮窗（PiP 降级，真正的播放器小窗）
    // ──────────────────────────────────────────────────────────────

    private boolean isInFloatingWindow = false;
    private android.view.WindowManager floatingWindowManager;
    private android.view.WindowManager.LayoutParams floatingLayoutParams;

    /**
     * 尝试进入悬浮窗 —— 把全屏视频容器移到系统悬浮窗
     */
    private void tryEnterFloatingWindow() {
        if (isInFloatingWindow) {
            exitFloatingWindow();
            return;
        }

        // Android 6.0+ 悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(this)) {
            android.util.Log.i("MoonTV", "⚠️ 跳转悬浮窗权限设置...");
            android.content.Intent intent = new android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            android.widget.Toast.makeText(this, "请开启「显示在其他应用上层」权限",
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        // 如果视频还没进全屏，先触发全屏
        if (customView == null) {
            android.util.Log.i("MoonTV", "视频未全屏，先触发全屏...");
            webView.evaluateJavascript(
                "(function() {" +
                "  var v = document.querySelector('video');" +
                "  if (v) {" +
                "    try { v.requestFullscreen(); } catch(e) {}" +
                "    try { v.webkitEnterFullscreen && v.webkitEnterFullscreen(); } catch(e) {}" +
                "  }" +
                "})();", null);
            mainHandler.postDelayed(() -> {
                if (customView != null) {
                    android.util.Log.i("MoonTV", "✅ customView 已拿到，创建悬浮窗");
                    createFloatingWindow();
                } else {
                    android.util.Log.w("MoonTV", "⚠️ 等了 1.2s 还是没拿到 customView，放弃悬浮窗");
                }
            }, 1200);
            return;
        }

        // 已经是全屏 → 直接创建悬浮窗
        createFloatingWindow();
    }

    private void createFloatingWindow() {
        if (customView == null) return;

        try {
            floatingWindowManager = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);

            // 窗口类型
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_PHONE;

            // 悬浮窗尺寸：屏幕宽 65%，高按视频比例
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            floatingWindowManager.getDefaultDisplay().getMetrics(dm);
            int winW = (int) (dm.widthPixels * 0.65f);
            int winH = winW * 9 / 16;

            floatingLayoutParams = new android.view.WindowManager.LayoutParams(
                winW, winH, type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
            floatingLayoutParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            floatingLayoutParams.x = 30;  // 右边距
            floatingLayoutParams.y = 150; // 顶部距

            // 1. 先从 fullscreenContainer 拿出 customView
            fullscreenContainer.removeView(customView);

            // 2. 改 LayoutParams 适配悬浮窗
            android.view.ViewGroup.LayoutParams lp = new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            customView.setLayoutParams(lp);

            // 3. 加到悬浮窗
            floatingWindowManager.addView(customView, floatingLayoutParams);

            // 4. 隐藏全屏容器 + 保持 WebView 可见（悬浮窗不影响它）
            fullscreenContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            rootContainer.setPadding(0, 0, 0, 0); // 悬浮窗不需要状态栏 padding

            // 5. 启动上岛服务
            startOrUpdatePlaybackService();

            isInFloatingWindow = true;
            android.util.Log.i("MoonTV", "✅ 悬浮窗创建成功！尺寸=" + winW + "x" + winH);

            // 6. 支持拖动 + 点击关闭
            setupFloatingControls();

        } catch (Exception e) {
            android.util.Log.e("MoonTV", "❌ 悬浮窗创建失败: " + e.getMessage(), e);
        }
    }

    private void setupFloatingControls() {
        if (customView == null) return;

        // 拖动
        final int[] startX = new int[1], startY = new int[1];
        final int[] winX = new int[1], winY = new int[1];
        final long[] lastTapTime = new long[1];
        final float[] lastTapX = new float[1], lastTapY = new float[1];

        customView.setOnTouchListener((v, event) -> {
            if (floatingLayoutParams == null) return false;

            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startX[0] = (int) event.getRawX();
                    startY[0] = (int) event.getRawY();
                    winX[0] = floatingLayoutParams.x;
                    winY[0] = floatingLayoutParams.y;
                    return false;

                case android.view.MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - startX[0];
                    int dy = (int) event.getRawY() - startY[0];
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        if ((floatingLayoutParams.gravity & android.view.Gravity.END) != 0) {
                            floatingLayoutParams.x = winX[0] - dx;
                        } else {
                            floatingLayoutParams.x = winX[0] + dx;
                        }
                        floatingLayoutParams.y = winY[0] + dy;
                        try {
                            floatingWindowManager.updateViewLayout(customView, floatingLayoutParams);
                        } catch (Exception ignored) {}
                        return true; // 拖动了就吃掉事件，不传给视频
                    }
                    return false;

                case android.view.MotionEvent.ACTION_UP:
                    // 快速点击 → 退出悬浮窗，回到全屏
                    long now = System.currentTimeMillis();
                    float ex = event.getRawX(), ey = event.getRawY();
                    if (now - lastTapTime[0] < 300
                        && Math.abs(ex - lastTapX[0]) < 30
                        && Math.abs(ey - lastTapY[0]) < 30) {
                        // 双击 → 退出悬浮窗
                        exitFloatingWindow();
                        return true;
                    }
                    lastTapTime[0] = now;
                    lastTapX[0] = ex;
                    lastTapY[0] = ey;
                    return false;
            }
            return false;
        });
    }

    /** 关闭悬浮窗，恢复全屏 */
    private void exitFloatingWindow() {
        if (!isInFloatingWindow || customView == null || floatingWindowManager == null) return;
        try {
            floatingWindowManager.removeView(customView);
            fullscreenContainer.addView(customView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fullscreenContainer.setVisibility(View.VISIBLE);
            webView.setVisibility(View.VISIBLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            setupImmersive();
            isInFloatingWindow = false;
            floatingWindowManager = null;
            floatingLayoutParams = null;
            android.util.Log.i("MoonTV", "✅ 悬浮窗已关闭，恢复全屏");
        } catch (Exception e) {
            android.util.Log.e("MoonTV", "exitFloatingWindow 失败: " + e.getMessage());
        }
    }

    /**
     * 进入 PiP（画中画）模式
     */
    private void enterPictureInPictureModeSafe() {
        if (!supportsPip() || isInPipMode) return;

        try {
            android.util.Log.i("MoonTV", "enterPictureInPictureModeSafe: 开始");

            // ✅ 提前设标记！否则 onPause 会在回调前被调用，暂停 WebView 导致黑屏
            isInPipMode = true;

            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new android.util.Rational(16, 9))
                    .build();

            boolean success = enterPictureInPictureMode(params);

            android.util.Log.i("MoonTV", "enterPictureInPictureMode 返回: " + success);

            // 如果系统拒绝了（某些国产 ROM 限制），回退标记
            if (!success) {
                isInPipMode = false;
                android.util.Log.w("MoonTV", "⚠️ 系统拒绝了 PiP，回退");
            }

        } catch (IllegalStateException e) {
            isInPipMode = false;
            android.util.Log.e("MoonTV", "PiP IllegalStateException: " + e.getMessage());
        } catch (Exception e) {
            isInPipMode = false;
            android.util.Log.e("MoonTV", "PiP Exception: " + e.getMessage());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        android.util.Log.i("MoonTV", "onPictureInPictureModeChanged: " + isInPictureInPictureMode);
        onPipModeChanged(isInPictureInPictureMode);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        android.util.Log.i("MoonTV", "onPictureInPictureModeChanged(2): " + isInPictureInPictureMode);
        onPipModeChanged(isInPictureInPictureMode);
    }

    private void onPipModeChanged(boolean nowInPip) {
        isInPipMode = nowInPip;

        if (webView == null) return;

        if (nowInPip) {
            android.widget.Toast.makeText(this, "✅ 已进入画中画",
                android.widget.Toast.LENGTH_SHORT).show();
        } else {
            android.widget.Toast.makeText(this, "已退出画中画",
                android.widget.Toast.LENGTH_SHORT).show();
            // 退出 PiP：恢复 video 播放
            String resumeVideo =
                "(function() {" +
                "  var videos = document.querySelectorAll('video');" +
                "  videos.forEach(function(v) {" +
                "    if (v.paused) v.play().catch(function(){});" +
                "  });" +
                "})();";
            webView.evaluateJavascript(resumeVideo, null);
            setupImmersive();
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
        // 1. 如果在 PiP 里，按返回 → 直接 finish()，让 PiP 继续在后台（上岛/通知接管）
        if (isInPipMode && supportsPip()) {
            finish();
            return;
        }

        // 2. 全屏视频 → 退出全屏（继续播放）
        if (customView != null) {
            exitFullscreenVideo();
            // 退出全屏后如果还在播放，启动上岛服务
            maybeStartIsland();
            return;
        }

        // 3. 视频正在播放 → 按返回直接上岛（PiP），不退出
        //    先注入 JS 检测 video 状态（兜底）
        if (supportsPip()) {
            // 先看 JS Bridge 的 isPlaying
            if (isPlaying) {
                android.util.Log.i("MoonTV", "返回键：播放中 → 直接上岛（PiP）");
                enterPictureInPictureModeSafe();
                maybeStartIsland();
                return;
            }
            // 兜底：检测 video 元素
            if (webView != null) {
                webView.evaluateJavascript(
                    "(function() {" +
                    "  var v = document.querySelector('video');" +
                    "  return v && !v.paused && !v.ended && v.readyState > 2 ? 'yes' : 'no';" +
                    "})();",
                    val -> {
                        if (val != null && val.contains("yes")) {
                            android.util.Log.i("MoonTV", "返回键：video playing → 直接上岛（PiP）");
                            mainHandler.post(() -> {
                                enterPictureInPictureModeSafe();
                                maybeStartIsland();
                            });
                        } else {
                            // 没在播放 → 正常返回栈
                            doNormalBack();
                        }
                    }
                );
                return; // 等 evaluateJavascript 回调
            }
        }

        // 4. 最后兜底：WebView 历史栈 → 退出
        doNormalBack();
    }

    /** 正常返回逻辑 */
    private void doNormalBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** 启动/更新超级岛（MediaStyle 前台服务） */
    private void maybeStartIsland() {
        // 这个方法里已经在 JS Bridge onPlayStart 时调用过 startOrUpdatePlaybackService()
        // 这里兜底：如果没启动，也尝试启动
        if (playbackService == null) {
            mainHandler.post(() -> startOrUpdatePlaybackService());
        }
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
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        // 从 PiP/悬浮窗回来：恢复 video 播放
        if (isInPipMode || isInFloatingWindow) {
            webView.evaluateJavascript(
                "(function() {" +
                "  var v = document.querySelector('video');" +
                "  if (v && v.paused) v.play().catch(function(){});" +
                "})();", null);
        }
        if (playbackService != null) playbackService.notifyActivityResumed();
    }

    @Override
    protected void onPause() {
        // ✅ PiP 模式下不要暂停 WebView！这是黑屏的根因！
        // onPause 会暂停 WebView 的渲染管线，包括 video 帧
        if (!isInPipMode && !isInFloatingWindow && webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // PiP/悬浮窗期间不要停止 WebView 渲染
        if (!isInPipMode && !isInFloatingWindow) {
            // 非 PiP 模式：可以安全暂停
            if (webView != null) {
                webView.onPause();
            }
        }
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
