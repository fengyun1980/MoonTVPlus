package com.fengyun.mobile;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.Rational;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import okhttp3.OkHttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String TAG = "MoonTV";
    private static final int OVERLAY_REQUEST_CODE = 1001;
    private static final int STORAGE_WRITE_PERMISSION_REQUEST = 2001;
    private static final int STORAGE_READ_PERMISSION_REQUEST = 2002;

    private WebView webView;
    private FrameLayout rootContainer;
    private FrameLayout fullscreenContainer;
    private PlayerView exoPlayerView;  // ✅ ExoPlayer 原生播放器覆盖层

    // ✅ ExoPlayer 实例
    private ExoPlayer exoPlayer;
    private boolean exoPlayerActive = false;  // PlayerView 是否接管播放

    private View currentCustomView;
    private WebChromeClient.CustomViewCallback currentCustomViewCallback;

    private String baseUrl;
    private String pendingDownloadUrl;
    private String pendingDownloadSuggestion;
    private ProgressBar loadingIndicator;

    private boolean isRequestingPermission = false;
    private boolean isInFullscreenCustomView = false;
    private boolean isInPipMode = false;
    private boolean isInFloatingWindow = false;

    // 悬浮窗相关
    private WindowManager floatingWindowManager;
    private View floatingPlayerView;  // 悬浮窗里的 PlayerView
    private WindowManager.LayoutParams floatingLayoutParams;
    private int floatingInitialX, floatingInitialY;
    private float floatingInitialTouchX, floatingInitialTouchY;
    private boolean isDragging = false;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // ──────────────────────────────────────────────────────────────
    // Activity 生命周期
    // ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        rootContainer = findViewById(R.id.root_container);
        fullscreenContainer = findViewById(R.id.fullscreen_container);
        exoPlayerView = findViewById(R.id.exo_player_view);  // ✅ 找到 PlayerView

        // ✅ 初始化 ExoPlayer（全局单例，整个 Activity 生命周期复用）
        initExoPlayer();

        loadSavedUrl();
        initWebView();
        setupImmersive();
        loadUrl();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 如果从悬浮窗/后台回来，恢复
        if (isInFloatingWindow) {
            removeFloatingWindow();
            showExoPlayerOverlay();
            isInFloatingWindow = false;
        }

        if (exoPlayer != null && exoPlayerActive) {
            exoPlayer.setPlayWhenReady(true);
        }

        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }

        VideoPlaybackService.resumePlaybackService(this);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // PiP 模式下不要暂停 WebView（PlayerView 在上面继续播）
        if (webView != null && !isInPipMode && !exoPlayerActive) {
            webView.onPause();
        }

        // 如果有原生播放器在播，让 VideoPlaybackService 接管（上岛）
        if (exoPlayer != null && exoPlayerActive && !isInPipMode) {
            VideoPlaybackService.pausePlaybackService(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !isInPipMode) {
            // Activity 真正离开前台
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            cleanup();
        }
    }

    private void cleanup() {
        // ✅ 释放 ExoPlayer
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }

        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.clearCache(true);
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        VideoPlaybackService.stopPlaybackService(this);
        removeFloatingWindow();
    }

    // ──────────────────────────────────────────────────────────────
    // ✅ ExoPlayer 初始化
    // ──────────────────────────────────────────────────────────────

    private void initExoPlayer() {
        if (exoPlayer != null) return;

        OkHttpClient okHttp = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

        OkHttpDataSource.Factory dataSourceFactory = new OkHttpDataSource.Factory(okHttp);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);

        exoPlayer = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build();

        // 绑定到 PlayerView 覆盖层
        exoPlayerView.setPlayer(exoPlayer);
        exoPlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);

            // 监听播放状态（用于上岛）
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    Log.d(TAG, "ExoPlayer state: " + state);
                    if (state == Player.STATE_READY && exoPlayer.getPlayWhenReady()) {
                        // 播放中 → 上岛
                        if (!isInFloatingWindow) {
                            startOrUpdatePlaybackService();
                        }
                    } else if (state == Player.STATE_ENDED) {
                        stopPlaybackService();
                        hideExoPlayerOverlay();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "ExoPlayer error: " + error.getMessage());
                    Toast.makeText(MainActivity.this, "播放失败：" + error.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            });
    }

    private String getCurrentVideoTitle() {
        return "MoonTV 视频";
    }

    private String getCurrentVideoEpisode() {
        return "";
    }

    // ──────────────────────────────────────────────────────────────
    // ✅ 核心：从 WebView 提取 video URL → ExoPlayer 接管播放
    // ──────────────────────────────────────────────────────────────

    /**
     * 扫描 WebView，找到 video 的 src，让 ExoPlayer 接管播放
     */
    private void captureVideoFromWebView() {
        if (webView == null || exoPlayerActive) return;

        // 注入 JS 找 video.src（支持 src 属性和 source 子元素）
        String js =
            "(function() {" +
            "  var v = document.querySelector('video');" +
            "  if (!v) return 'no-video';" +
            "  if (v.src && v.src.length > 0) return v.src;" +
            "  var source = v.querySelector('source');" +
            "  if (source && source.src) return source.src;" +
            "  return 'no-src';" +
            "})();";

        webView.evaluateJavascript(js, result -> {
            if (result == null || result.startsWith("no-") || result.startsWith("null")) {
                Log.d(TAG, "没找到 video src: " + result);
                return;
            }

            // URL 是 JS 字符串格式，去掉引号
            String videoUrl = result.replace("\"", "").trim();
            Log.i(TAG, "✅ 提取到 video URL: " + videoUrl);

            // ✅ 让 ExoPlayer 接管
            playVideoWithExoPlayer(videoUrl);
        });
    }

    /**
     * 用 ExoPlayer 播放指定 URL
     */
    private void playVideoWithExoPlayer(String url) {
        if (exoPlayer == null) initExoPlayer();

        try {
            MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse(url))
                .build();

            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);

            // ✅ 隐藏 WebView 里的原始 video（停止它的渲染，省资源）
            hideWebViewVideo();

            // ✅ 显示 ExoPlayer PlayerView 覆盖层
            showExoPlayerOverlay();

            exoPlayerActive = true;
            Log.i(TAG, "✅ ExoPlayer 接管播放: " + url);

            Toast.makeText(this, "视频切换为原生播放器", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "ExoPlayer 播放失败: " + e.getMessage());
            Toast.makeText(this, "原生播放失败，使用网页播放: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 隐藏 WebView 里的原始 video（用 CSS visibility，不销毁，保持资源释放）
     */
    private void hideWebViewVideo() {
        if (webView == null) return;
        String js =
            "(function() {" +
            "  var v = document.querySelector('video');" +
            "  if (v) {" +
            "    v.style.opacity = '0';" +
            "    v.style.pointerEvents = 'none';" +
            "  }" +
            // 隐藏 ArtPlayer 容器
            "  var artContainer = document.querySelector('.artplayer');" +
            "  if (artContainer) artContainer.style.opacity = '0';" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    /**
     * 恢复 WebView 里的原始 video
     */
    private void showWebViewVideo() {
        if (webView == null) return;
        String js =
            "(function() {" +
            "  var v = document.querySelector('video');" +
            "  if (v) {" +
            "    v.style.opacity = '1';" +
            "    v.style.pointerEvents = 'auto';" +
            "  }" +
            "  var artContainer = document.querySelector('.artplayer');" +
            "  if (artContainer) artContainer.style.opacity = '1';" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    /** 显示 PlayerView 覆盖层 */
    private void showExoPlayerOverlay() {
        if (exoPlayerView == null) return;
        exoPlayerView.setVisibility(View.VISIBLE);
        exoPlayerView.bringToFront();
        rootContainer.requestLayout();
    }

    /** 隐藏 PlayerView 覆盖层 */
    private void hideExoPlayerOverlay() {
        if (exoPlayerView != null) {
            exoPlayerView.setVisibility(View.GONE);
        }
        exoPlayerActive = false;
        showWebViewVideo();
    }

    // ──────────────────────────────────────────────────────────────
    // ✅ PiP / 悬浮窗 / 原生上岛触发
    // ──────────────────────────────────────────────────────────────

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isInPipMode || isInFloatingWindow) return;

        // 先尝试 HTML5 video 接管（如果 ExoPlayer 没激活）
        if (!exoPlayerActive) {
            // 快速扫描 WebView 有没有 video
            mainHandler.post(this::captureVideoFromWebView);
            // 给 JS 执行留时间
            mainHandler.postDelayed(() -> {
                if (exoPlayerActive) {
                    enterPipOrFloating();
                } else {
                    // WebView 里有 video 但 ExoPlayer 没接住 → 直接进 Activity PiP
                    tryActivityPipDirectly();
                }
            }, 800);
        } else {
            // ✅ ExoPlayer 已经在播 → 直接用 PlayerView 做 PiP（100% 不黑屏）
            enterPipOrFloating();
        }
    }

    /**
     * Activity PiP（当没有 ExoPlayer 时直接用 WebView 进入）
     */
    private void tryActivityPipDirectly() {
        if (!supportsPip()) {
            Toast.makeText(this, "PiP 不可用，尝试悬浮窗...", Toast.LENGTH_SHORT).show();
            tryEnterFloatingWindow();
            return;
        }
        try {
            isInPipMode = true;
            boolean ok = enterPictureInPictureMode(
                new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build());
            if (!ok) {
                isInPipMode = false;
                tryEnterFloatingWindow();
            } else {
                Toast.makeText(this, "✅ 已进入小窗模式", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            isInPipMode = false;
            tryEnterFloatingWindow();
        }
    }

    /**
     * 优先 PiP，失败降级悬浮窗
     */
    private void enterPipOrFloating() {
        // 先启动播放服务（上岛）
        startOrUpdatePlaybackService();

        if (supportsPip()) {
            try {
                isInPipMode = true;
                boolean ok = enterPictureInPictureMode(
                    new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .build());
                Log.i(TAG, "enterPictureInPictureMode: " + ok);
                if (ok) {
                    Toast.makeText(this, "✅ 已进入画中画", Toast.LENGTH_SHORT).show();
                    return;
                }
                isInPipMode = false;
            } catch (Exception e) {
                Log.w(TAG, "Activity PiP 失败: " + e.getMessage());
                isInPipMode = false;
            }
        }

        // ✅ 降级：悬浮窗（ExoPlayer PlayerView 直接挂到 WindowManager）
        Toast.makeText(this, "PiP 不可用，尝试悬浮窗...", Toast.LENGTH_SHORT).show();
        tryEnterFloatingWindow();
    }

    /** 是否支持 Activity PiP */
    private boolean supportsPip() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** 检测 HTML5 video 是否在播放 */
    private void checkWebViewVideoPlaying(ValueCallback<Boolean> callback) {
        if (webView == null) { callback.onReceiveValue(false); return; }
        webView.evaluateJavascript(
            "(function() {" +
            "  var v = document.querySelector('video');" +
            "  if (!v) return false;" +
            "  return !v.paused && !v.ended && v.readyState >= 2;" +
            "})();",
            result -> {
                callback.onReceiveValue("true".equalsIgnoreCase(result));
            }
        );
    }

    /**
     * 进入悬浮窗 —— 把 PlayerView 从根容器移到 WindowManager
     * 如果 ExoPlayer 没激活，先 captureWebViewVideo 再等一下再进
     */
    private void tryEnterFloatingWindow() {
        // 先确保 ExoPlayer 已经接管
        if (!exoPlayerActive) {
            captureVideoFromWebView();
            mainHandler.postDelayed(this::doEnterFloatingWindow, 1000);
        } else {
            doEnterFloatingWindow();
        }
    }

    private void doEnterFloatingWindow() {
        if (!exoPlayerActive || exoPlayerView == null) {
            Log.w(TAG, "ExoPlayer 没激活，无法悬浮");
            Toast.makeText(this, "无法悬浮：视频未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_REQUEST_CODE);
            } catch (Exception e) {
                Toast.makeText(this, "请手动开启：设置 → 应用管理 → " + getString(R.string.app_name) + " → 显示在其他应用上层",
                    Toast.LENGTH_LONG).show();
            }
            return;
        }

        try {
            // ✅ 从根容器把 PlayerView 摘出来，加到 WindowManager
            FrameLayout parent = (FrameLayout) exoPlayerView.getParent();
            parent.removeView(exoPlayerView);

            floatingWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = getResources().getDisplayMetrics();

            floatingLayoutParams = new WindowManager.LayoutParams(
                dm.widthPixels * 65 / 100,        // 宽 65% 屏幕
                (dm.widthPixels * 65 / 100) * 9 / 16,  // 16:9
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                           : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

            floatingLayoutParams.gravity = Gravity.TOP | Gravity.END;
            floatingLayoutParams.x = 30;
            floatingLayoutParams.y = 150;

            floatingPlayerView = exoPlayerView;
            floatingPlayerView.setOnTouchListener(new FloatingTouchListener());

            floatingWindowManager.addView(floatingPlayerView, floatingLayoutParams);
            floatingPlayerView.setVisibility(View.VISIBLE);

            isInFloatingWindow = true;

            // 启动上岛播放服务
            startOrUpdatePlaybackService();

            Toast.makeText(this, "✅ 视频已悬浮（双击恢复全屏）", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "✅ 悬浮窗创建成功");

        } catch (Exception e) {
            Log.e(TAG, "悬浮窗创建失败: " + e.getMessage());
            Toast.makeText(this, "悬浮窗失败: " + e.getMessage(), Toast.LENGTH_LONG).show();

            // 恢复
            if (exoPlayerView.getParent() == null && rootContainer != null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
                rootContainer.addView(exoPlayerView, lp);
            }
        }
    }

    /** 移除悬浮窗，恢复全屏 */
    private void removeFloatingWindow() {
        if (floatingWindowManager != null && floatingPlayerView != null
            && floatingPlayerView.getParent() != null) {
            try {
                floatingWindowManager.removeView(floatingPlayerView);
            } catch (Exception ignored) {}
        }

        if (floatingPlayerView != null && rootContainer != null
            && floatingPlayerView.getParent() != rootContainer) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
            rootContainer.addView(floatingPlayerView, lp);
            floatingPlayerView.setVisibility(View.VISIBLE);
        }

        floatingWindowManager = null;
        floatingPlayerView = null;
        floatingLayoutParams = null;
        isInFloatingWindow = false;
    }

    /** 悬浮窗拖动 + 双击恢复 */
    private class FloatingTouchListener implements View.OnTouchListener {
        private long lastClickTime = 0;
        private float firstTouchX = 0, firstTouchY = 0;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (floatingLayoutParams == null || floatingWindowManager == null) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    floatingInitialX = floatingLayoutParams.x;
                    floatingInitialY = floatingLayoutParams.y;
                    floatingInitialTouchX = event.getRawX();
                    floatingInitialTouchY = event.getRawY();
                    firstTouchX = event.getRawX();
                    firstTouchY = event.getRawY();
                    return false;  // 让点击事件也透传给 PlayerView（播放控制）

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - floatingInitialTouchX;
                    float dy = event.getRawY() - floatingInitialTouchY;

                    if (!isDragging && Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true;
                    }

                    if (isDragging) {
                        // Gravity.TOP | RIGHT，x 为距右边距，y 为距顶距
                        floatingLayoutParams.x = floatingInitialX - (int) dx;
                        floatingLayoutParams.y = floatingInitialY + (int) dy;
                        try {
                            floatingWindowManager.updateViewLayout(floatingPlayerView, floatingLayoutParams);
                        } catch (Exception ignored) {}
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        // 检查双击
                        long now = System.currentTimeMillis();
                        if (now - lastClickTime < 400) {
                            // ✅ 双击 → 恢复全屏
                            Toast.makeText(MainActivity.this, "恢复全屏播放...", Toast.LENGTH_SHORT).show();
                            removeFloatingWindow();
                            moveTaskToFront();
                        }
                        lastClickTime = now;
                    }
                    isDragging = false;
                    break;
            }
            return false;
        }
    }

    private void moveTaskToFront() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                am.getAppTasks().get(0).moveToFront();
            } catch (Exception ignored) {}
        } else {
            moveTaskToFront(getTaskId(), false);
        }
    }

    @Override
    protected void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        this.isInPipMode = isInPictureInPictureMode;
        Log.i(TAG, "PiP 模式变化: " + isInPictureInPictureMode);

        if (!isInPictureInPictureMode) {
            // 退出 PiP → 恢复播放
            mainHandler.post(() -> {
                if (exoPlayer != null && exoPlayerActive) {
                    exoPlayer.setPlayWhenReady(true);
                } else {
                    // 尝试恢复 WebView 里的 video
                    if (webView != null) {
                        webView.evaluateJavascript(
                            "(function(){ var v=document.querySelector('video'); if(v&&v.paused) v.play(); })();",
                            null);
                    }
                }
            });
        }
    }

    // ──────────────────────────────────────────────────────────────
    // VideoPlaybackService（上岛 —— 通过 Intent 启动）
    // ──────────────────────────────────────────────────────────────

    private void startOrUpdatePlaybackService() {
        if (!exoPlayerActive) return;

        Intent intent = new Intent(this, VideoPlaybackService.class);
        intent.setAction(VideoPlaybackService.ACTION_START_PLAYBACK);
        intent.putExtra("title", getCurrentVideoTitle());
        intent.putExtra("cover_url", "");
        intent.putExtra("episode", getCurrentVideoEpisode());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void pausePlaybackService() {
        Intent intent = new Intent(this, VideoPlaybackService.class);
        intent.setAction(VideoPlaybackService.ACTION_TOGGLE_PLAY);
        startService(intent);
    }

    private void stopPlaybackService() {
        Intent intent = new Intent(this, VideoPlaybackService.class);
        intent.setAction(VideoPlaybackService.ACTION_STOP_PLAYBACK);
        startService(intent);
    }

    // ──────────────────────────────────────────────────────────────
    // 沉浸式 + WebView 配置 + URL Dialog
    // ──────────────────────────────────────────────────────────────

    private void setupImmersive() {
        // 只在非全屏视频时设 FLAG_LAYOUT_STABLE / NO_CHANGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(0x00000000);
    }

    private void loadSavedUrl() {
        SharedPreferences sp = getSharedPreferences("moontv_config", MODE_PRIVATE);
        baseUrl = sp.getString("server_url", null);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = BuildConfig.BASE_URL;
        }
    }

    private void saveUrl(String url) {
        String saved = url.trim();
        SharedPreferences sp = getSharedPreferences("moontv_config", MODE_PRIVATE);
        sp.edit().putString("server_url", saved).apply();
        baseUrl = saved;
    }

    private void loadUrl() {
        if (baseUrl == null || baseUrl.isEmpty() || baseUrl.contains("your-server")) {
            showUrlDialog();
        } else {
            webView.loadUrl(baseUrl);
        }
    }

    private void showUrlDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🖥️ 服务器地址");
        builder.setMessage("请输入 MoonTV 服务端地址\n（例如 http://192.168.1.10:3000）");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setHint("https://your-server.com");
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(20); lp.rightMargin = dp(20); lp.topMargin = dp(10); lp.bottomMargin = dp(10);
        container.addView(input, lp);
        builder.setView(container);

        builder.setPositiveButton("确定", null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btn.setOnClickListener(v -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) { Toast.makeText(this, "请输入地址", Toast.LENGTH_SHORT).show(); return; }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "http://" + url;
                }
                saveUrl(url);
                if (webView != null) webView.loadUrl(baseUrl);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showLongPressSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚙️ MoonTV 设置");
        builder.setMessage(
            "当前服务器地址:\n  " + (baseUrl != null ? baseUrl : "(未设置)") + "\n\n" +
            "选择操作:");
        builder.setItems(new CharSequence[]{
            "✏️ 修改服务器地址",
            "🌐 在浏览器打开",
            "📤 分享当前链接",
            "🧹 清除缓存",
            "❌ 退出 APP"
        }, (dialog, which) -> {
            switch (which) {
                case 0: showUrlDialog(); break;
                case 1:
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(baseUrl));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case 2:
                    if (webView != null) webView.evaluateJavascript("location.href", url -> {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, url);
                        startActivity(Intent.createChooser(shareIntent, "分享"));
                    });
                    break;
                case 3:
                    if (webView != null) {
                        webView.clearCache(true);
                        WebStorage.getInstance().deleteAllData();
                        CookieManager.getInstance().removeAllCookies(null);
                        Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case 4:
                    cleanup();
                    finish();
                    break;
            }
        });
        builder.setPositiveButton("关闭", null);
        builder.show();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    // ──────────────────────────────────────────────────────────────
    // WebView 初始化（最小化配置）
    // ──────────────────────────────────────────────────────────────

    private void initWebView() {
        webView = findViewById(R.id.webview);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " MoonTV Android");
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        // ✅ 始终保持 WebView 活跃（PiP/悬浮窗场景）
        webView.onResume();
        webView.resumeTimers();

        // ✅ JS Bridge
        webView.addJavascriptInterface(new MobileJsBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完 → 延迟扫描 video → ExoPlayer 接管
                mainHandler.postDelayed(() -> {
                    captureVideoFromWebView();
                    // 1.5 秒后再扫一次（React/Next.js 客户端渲染）
                    mainHandler.postDelayed(MainActivity.this::captureVideoFromWebView, 1500);
                }, 800);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // Web 全屏 → PlayerView 也接管一次
                currentCustomView = view;
                currentCustomViewCallback = callback;
                isInFullscreenCustomView = true;
                // 不管，我们用 ExoPlayer PlayerView 渲染
            }
        });
    }

    // ──────────────────────────────────────────────────────────────
    // 返回键智能处理
    // ──────────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        // 悬浮窗 → 恢复全屏
        if (isInFloatingWindow) {
            removeFloatingWindow();
            return;
        }
        // ExoPlayer 在播 → 先隐藏 PlayerView（返回 WebView）
        if (exoPlayerActive) {
            hideExoPlayerOverlay();
            return;
        }
        // PiP 模式 → 退出
        if (isInPipMode) {
            moveTaskToFront();
            return;
        }
        // WebView 历史栈
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // 长按 → 设置
        showLongPressSettingsDialog();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            // 长按返回键 → 设置
            Runnable showSettings = this::showLongPressSettingsDialog;
            mainHandler.postDelayed(showSettings, 1500);
            return super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQUEST_CODE) {
            // 用户回来 → 再试一次悬浮窗
            mainHandler.postDelayed(() -> {
                if (exoPlayerActive) doEnterFloatingWindow();
                else captureVideoFromWebView();
            }, 500);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // JS Bridge（供前端调用）
    // ──────────────────────────────────────────────────────────────

    public class MobileJsBridge {
        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void copyToClipboard(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("MoonTV", text));
                Toast.makeText(MainActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void onDownload(final String url, final String filename) {
            runOnUiThread(() -> {
                pendingDownloadUrl = url;
                pendingDownloadSuggestion = filename;
                handleDownloadFile(url, filename);
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ✅ 播放状态回调
        @JavascriptInterface
        public void onPlayStart(String title, String coverUrl, String episode) {
            runOnUiThread(() -> {
                // 扫描 video → ExoPlayer 接管
                captureVideoFromWebView();
                // 同时启动上岛服务
                startOrUpdatePlaybackService();
            });
        }

        @JavascriptInterface
        public void onPlayPause() {
            runOnUiThread(() -> {
                if (exoPlayer != null && exoPlayerActive) {
                    exoPlayer.setPlayWhenReady(false);
                }
                pausePlaybackService();
            });
        }

        @JavascriptInterface
        public void onPlayEnd() {
            runOnUiThread(() -> {
                if (exoPlayer != null) exoPlayer.stop();
                stopPlaybackService();
                hideExoPlayerOverlay();
            });
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 文件下载（简化版）
    // ──────────────────────────────────────────────────────────────

    private void handleDownloadFile(String url, String suggestedFilename) {
        Toast.makeText(this, "下载功能请使用网页版", Toast.LENGTH_SHORT).show();
    }
}
