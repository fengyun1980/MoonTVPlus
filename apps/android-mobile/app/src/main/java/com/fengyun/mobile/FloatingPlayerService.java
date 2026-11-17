package com.fengyun.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 悬浮播放器服务 —— 系统级悬浮小窗（独立于 APP 窗口）
 * <p>
 * 用户按 Home 键或手动触发后，视频变成一个可拖动的小窗挂在桌面上。
 * 核心能力：
 * 1. WindowManager.addView 创建 TYPE_APPLICATION_OVERLAY 悬浮窗
 * 2. 悬浮窗内嵌一个小 WebView，加载同一个播放 URL
 * 3. 支持拖动（顶部标题栏）
 * 4. 支持关闭 / 返回主 APP
 * 5. Android 8+ 以前台服务启动，避免被系统杀掉
 * <p>
 * 前置条件：用户必须先在系统设置里授予「显示在其他应用上层」权限。
 * AndroidManifest 声明了 SYSTEM_ALERT_WINDOW，首次启动会引导用户跳转授权。
 */
public class FloatingPlayerService extends Service {

    static final String ACTION_SHOW_FLOATING = "com.fengyun.mobile.ACTION_SHOW_FLOATING";
    static final String ACTION_HIDE_FLOATING = "com.fengyun.mobile.ACTION_HIDE_FLOATING";
    static final String ACTION_TOGGLE_FLOATING = "com.fengyun.mobile.ACTION_TOGGLE_FLOATING";
    static final String EXTRA_PLAY_URL = "extra_play_url";

    private static final String CHANNEL_ID = "fengyun_floating_channel";
    private static final int NOTIFICATION_ID = 1002;

    private WindowManager windowManager;
    private View floatingRoot;
    private WebView floatingWebView;
    private WindowManager.LayoutParams params;

    private static boolean isFloatingShown = false;
    private static String currentPlayUrl = null;

    // ──────────────────────────────────────────────────────────────
    // 静态状态查询（供 Activity 调用）
    // ──────────────────────────────────────────────────────────────

    public static boolean isShown() {
        return isFloatingShown;
    }

    public static boolean canDrawOver(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(ctx);
        }
        return true; // Android 5.x 及以下默认有
    }

    // ──────────────────────────────────────────────────────────────
    // Service 生命周期
    // ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        switch (action != null ? action : "") {
            case ACTION_SHOW_FLOATING:
                String url = intent.getStringExtra(EXTRA_PLAY_URL);
                if (url != null && !url.isEmpty()) currentPlayUrl = url;
                showFloating();
                break;
            case ACTION_HIDE_FLOATING:
                hideFloating();
                stopSelf();
                break;
            case ACTION_TOGGLE_FLOATING:
                if (isFloatingShown) hideFloating();
                else showFloating();
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideFloating();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ──────────────────────────────────────────────────────────────
    // 核心：创建/销毁悬浮窗
    // ──────────────────────────────────────────────────────────────

    private void showFloating() {
        if (isFloatingShown) return;

        if (!canDrawOver(this)) {
            Toast.makeText(this, "请先授权「显示在其他应用上层」", Toast.LENGTH_LONG).show();
            // 引导用户跳转到系统授权页（Activity 里会处理跳转，这里只提示）
            stopSelf();
            return;
        }

        // Android 14+ 必须 startForeground 后才能 addView
        startForeground(NOTIFICATION_ID, buildNotification("悬浮播放中"));

        try {
            floatingRoot = buildFloatingView();
            params = buildLayoutParams();
            windowManager.addView(floatingRoot, params);
            isFloatingShown = true;
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    private void hideFloating() {
        if (isFloatingShown && floatingRoot != null) {
            try {
                windowManager.removeView(floatingRoot);
            } catch (Exception ignored) {}
            floatingRoot = null;
        }
        if (floatingWebView != null) {
            floatingWebView.destroy();
            floatingWebView = null;
        }
        isFloatingShown = false;
    }

    // ──────────────────────────────────────────────────────────────
    // 构建悬浮窗布局
    // ──────────────────────────────────────────────────────────────

    private View buildFloatingView() {
        // 根容器：FrameLayout，WebView 填满 + 顶部标题栏盖在上面
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // WebView
        floatingWebView = new WebView(this);
        floatingWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        floatingWebView.setFocusable(true);
        floatingWebView.requestFocus();

        WebSettings s = floatingWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        floatingWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return false;
            }
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                handler.proceed();
            }
        });
        floatingWebView.setWebChromeClient(new WebChromeClient());

        FrameLayout.LayoutParams webLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(floatingWebView, webLp);

        // 顶部标题栏（40dp 高，半透明黑，整个区域都是拖动 handle）
        int titleBarH = (int) (40 * getResources().getDisplayMetrics().density);
        View titleBar = new View(this);
        titleBar.setBackgroundColor(0x66000000); // 40% 透明黑
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, titleBarH);
        tbLp.gravity = Gravity.TOP;
        root.addView(titleBar, tbLp);

        // 关闭按钮（右上角 ×）
        ImageView closeBtn = new ImageView(this);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setContentDescription("关闭悬浮窗");
        int iconSize = (int) (28 * getResources().getDisplayMetrics().density);
        int closeMargin = (int) (6 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(iconSize, iconSize);
        cbLp.gravity = Gravity.TOP | Gravity.END;
        cbLp.topMargin = closeMargin;
        cbLp.rightMargin = closeMargin;
        closeBtn.setLayoutParams(cbLp);
        closeBtn.setOnClickListener(v -> {
            hideFloating();
            stopSelf();
        });
        root.addView(closeBtn);

        // 点击悬浮窗其他位置 → 返回主 APP
        root.setOnTouchListener(new View.OnTouchListener() {
            private long downTime = 0;
            private float downX = 0, downY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downTime = System.currentTimeMillis();
                        downX = event.getRawX();
                        downY = event.getRawY();
                        break;
                    case MotionEvent.ACTION_UP:
                        long dt = System.currentTimeMillis() - downTime;
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        // 短按 + 没移动 → 认为是点击，返回主 APP
                        if (dt < 300 && Math.abs(dx) < 20 && Math.abs(dy) < 20) {
                            Intent backToApp = new Intent(FloatingPlayerService.this, MainActivity.class);
                            backToApp.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(backToApp);
                        }
                        break;
                }
                return false; // 不消费事件，让 WebView 也能收到
            }
        });

        // 标题栏拖动
        titleBar.setOnTouchListener(new DragListener());

        // 加载播放页（加上 floating=1 参数让 Web 端进入纯播放模式）
        if (currentPlayUrl != null && !currentPlayUrl.isEmpty()) {
            String urlWithFlag = currentPlayUrl;
            String separator = urlWithFlag.contains("?") ? "&" : "?";
            urlWithFlag += separator + "floating=1";
            floatingWebView.loadUrl(urlWithFlag);
        }

        return root;
    }

    private WindowManager.LayoutParams buildLayoutParams() {
        int w = (int) (320 * getResources().getDisplayMetrics().density);  // 320dp
        int h = (int) (180 * getResources().getDisplayMetrics().density);  // 180dp (16:9)

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        return new WindowManager.LayoutParams(
                w, h,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
    }

    /** 标题栏拖动监听 */
    private class DragListener implements View.OnTouchListener {
        private float downX, downY;
        private float initialTouchX, initialTouchY;
        private boolean moved = false;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = params.x;
                    downY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true;
                    params.x = (int) (downX + dx);
                    params.y = (int) (downY + dy);
                    try {
                        windowManager.updateViewLayout(floatingRoot, params);
                    } catch (Exception ignored) {}
                    return true;
            }
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 前台服务通知
    // ──────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "悬浮播放器", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 100, openIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("风云影视")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
