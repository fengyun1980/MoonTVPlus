package com.fengyun.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import com.fengyun.mobile.IslandManager.HyperOSInfo;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 播放前台服务 —— 小米超级岛自动上岛的核心
 * <p>
 * 原理：
 * 1. 启动为前台服务（Android 12+ foregroundServiceType=mediaPlayback）
 * 2. 创建 MediaSessionCompat 控制播放状态
 * 3. 发送 MediaStyle 通知（带封面、播放/暂停/上/下一集按钮）
 * 4. 在小米 HyperOS 上，系统检测到 MediaStyle 通知 → 自动以岛形式展示
 * <p>
 * 非小米设备的降级：MediaStyle 通知在所有安卓设备上都正常显示在通知栏，只是没有"岛"视觉。
 */
public class VideoPlaybackService extends Service {

    static final String ACTION_START_PLAYBACK = "com.fengyun.mobile.ACTION_START_PLAYBACK";
    static final String ACTION_STOP_PLAYBACK = "com.fengyun.mobile.ACTION_STOP_PLAYBACK";
    static final String ACTION_TOGGLE_PLAY = "com.fengyun.mobile.ACTION_TOGGLE_PLAY";
    static final String ACTION_PREVIOUS = "com.fengyun.mobile.ACTION_PREVIOUS";
    static final String ACTION_NEXT = "com.fengyun.mobile.ACTION_NEXT";

    static final String EXTRA_TITLE = "extra_title";
    static final String EXTRA_COVER_URL = "extra_cover_url";

    private static final String CHANNEL_ID = "fengyun_playback_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new LocalBinder();
    private MediaSessionCompat mediaSession;
    private NotificationManager notificationManager;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private String currentTitle = "";
    private String currentCoverUrl = "";
    private Bitmap currentCoverBitmap;
    private boolean isPlaying = false;
    private int currentPosition = 0;
    private int currentDuration = 0;

    // ──────────────────────────────────────────────────────────────
    // Binder
    // ──────────────────────────────────────────────────────────────

    public class LocalBinder extends Binder {
        VideoPlaybackService getService() {
            return VideoPlaybackService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ──────────────────────────────────────────────────────────────
    // Service 生命周期
    // ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        setupMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        switch (action != null ? action : "") {
            case ACTION_START_PLAYBACK:
                String title = intent.getStringExtra(EXTRA_TITLE);
                String coverUrl = intent.getStringExtra(EXTRA_COVER_URL);
                startPlayback(title, coverUrl);
                break;
            case ACTION_STOP_PLAYBACK:
                stopPlaybackAndSelf();
                break;
            case ACTION_TOGGLE_PLAY:
                togglePlayPause();
                break;
            case ACTION_PREVIOUS:
                sendJsEvent("previous");
                break;
            case ACTION_NEXT:
                sendJsEvent("next");
                break;
            case Intent.ACTION_MEDIA_BUTTON:
                // 系统媒体按键（有线耳机/蓝牙手柄）
                KeyEvent keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_UP) {
                    handleMediaButton(keyEvent);
                }
                break;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        notificationManager.cancel(NOTIFICATION_ID);
    }

    // ──────────────────────────────────────────────────────────────
    // 通知渠道
    // ──────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "正在播放",
                    NotificationManager.IMPORTANCE_LOW // 低优先级 → 不打扰用户，但前台服务需要
            );
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // MediaSession 设置（上岛核心）
    // ──────────────────────────────────────────────────────────────

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "FengYunPlayback");

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                notifyPlaying();
                sendJsEvent("play");
            }

            @Override
            public void onPause() {
                super.onPause();
                notifyPaused();
                sendJsEvent("pause");
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                sendJsEvent("next");
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                sendJsEvent("previous");
            }

            @Override
            public void onSeekTo(long pos) {
                super.onSeekTo(pos);
                sendJsEvent("seek:" + pos);
            }
        });

        // 设置支持的动作
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        // 初始 PlaybackState
        updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0);

        mediaSession.setActive(true);
    }

    private void updatePlaybackState(int state, long positionMs) {
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setState(state, positionMs, 1.0f)
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY
                                | PlaybackStateCompat.ACTION_PAUSE
                                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                                | PlaybackStateCompat.ACTION_SEEK_TO
                );
        mediaSession.setPlaybackState(builder.build());
    }

    private void updateMetadata(String title, Bitmap cover) {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "风云影视");

        if (cover != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover);
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
        }

        mediaSession.setMetadata(builder.build());
    }

    // ──────────────────────────────────────────────────────────────
    // 播放控制 API（供 Activity 调用）
    // ──────────────────────────────────────────────────────────────

    void restoreStateFromActivity(String title, String coverUrl) {
        if (title != null && !title.isEmpty()) {
            this.currentTitle = title;
            this.currentCoverUrl = coverUrl == null ? "" : coverUrl;
            refreshNotification();
        }
    }

    void startPlayback(String title, String coverUrl) {
        this.currentTitle = title == null ? "正在播放" : title;
        this.currentCoverUrl = coverUrl == null ? "" : coverUrl;
        this.isPlaying = true;

        // 启动前台服务（必需，否则 MediaStyle 通知会消失）
        startForeground(NOTIFICATION_ID, buildNotification());

        // 异步加载封面
        if (currentCoverUrl != null && !currentCoverUrl.isEmpty()) {
            loadCoverAsync(currentCoverUrl);
        } else {
            updateMetadata(currentTitle, null);
            refreshNotification();
        }
    }

    void notifyPlaying() {
        this.isPlaying = true;
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, currentPosition * 1000L);
        refreshNotification();
    }

    void notifyPaused() {
        this.isPlaying = false;
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, currentPosition * 1000L);
        refreshNotification();
    }

    void updateProgress(int positionSec, int durationSec) {
        this.currentPosition = positionSec;
        this.currentDuration = durationSec;
        if (isPlaying) {
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, positionSec * 1000L);
        }
    }

    void notifyActivityResumed() {
        // Activity 回到前台时刷新一次通知
        refreshNotification();
    }

    private void togglePlayPause() {
        if (isPlaying) {
            notifyPaused();
            sendJsEvent("pause");
        } else {
            notifyPlaying();
            sendJsEvent("play");
        }
    }

    private void stopPlaybackAndSelf() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
        }
        notificationManager.cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ──────────────────────────────────────────────────────────────
    // 封面异步加载
    // ──────────────────────────────────────────────────────────────

    private void loadCoverAsync(String urlStr) {
        if (executor == null) return;
        executor.execute(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                InputStream is = conn.getInputStream();
                currentCoverBitmap = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
            } catch (Exception e) {
                currentCoverBitmap = null;
            }
            mainHandler.post(() -> {
                updateMetadata(currentTitle, currentCoverBitmap);
                refreshNotification();
            });
        });
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ──────────────────────────────────────────────────────────────
    // 构建 MediaStyle 通知（超级岛上岛关键）
    // ──────────────────────────────────────────────────────────────

    private Notification buildNotification() {
        // 点击通知 → 打开 APP
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, flags);

        // 上一集
        Intent prevIntent = new Intent(this, VideoPlaybackService.class).setAction(ACTION_PREVIOUS);
        PendingIntent prevPi = PendingIntent.getService(this, 1, prevIntent, flags);

        // 播放/暂停
        Intent toggleIntent = new Intent(this, VideoPlaybackService.class).setAction(ACTION_TOGGLE_PLAY);
        PendingIntent togglePi = PendingIntent.getService(this, 2, toggleIntent, flags);

        // 下一集
        Intent nextIntent = new Intent(this, VideoPlaybackService.class).setAction(ACTION_NEXT);
        PendingIntent nextPi = PendingIntent.getService(this, 3, nextIntent, flags);

        int toggleIcon = isPlaying
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;
        CharSequence toggleText = isPlaying ? "暂停" : "播放";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle)
                .setContentText("风云影视 — 正在观看")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentPi)
                .setOngoing(true) // 持续通知（播放中不能被划掉）
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_previous, "上一集", prevPi)
                .addAction(toggleIcon, toggleText, togglePi)
                .addAction(android.R.drawable.ic_media_next, "下一集", nextPi)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2) // 紧凑视图显示三个按钮
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(PendingIntent.getService(
                                this, 99,
                                new Intent(this, VideoPlaybackService.class).setAction(ACTION_STOP_PLAYBACK),
                                flags
                        )));

        // 大图标（视频封面）
        if (currentCoverBitmap != null) {
            builder.setLargeIcon(currentCoverBitmap);
            // 小米超级岛：有大图标 = 岛上显示封面缩略图
        }

        // ─── 小米 HyperOS 自定义岛扩展（可选增强） ───
        attachHyperIslandExtras(builder);

        return builder.build();
    }

    /**
     * 在 HyperOS 上附加自定义岛通知 JSON 扩展
     * 仅当检测到支持岛时才添加，不影响其他安卓设备
     */
    private void attachHyperIslandExtras(NotificationCompat.Builder builder) {
        HyperOSInfo info = IslandManager.detect(this);
        if (!info.supportsIsland) return;

        try {
            // 构建小米岛参数 JSON
            String islandParams = buildIslandParamsJson(currentTitle);
            builder.getExtras().putString("miui.focus.param", islandParams);

            // 附加自定义图片（如果有封面的话）
            if (currentCoverBitmap != null) {
                Bundle pics = new Bundle();
                pics.putParcelable("miui.focus.pic_cover",
                        android.graphics.drawable.Icon.createWithBitmap(currentCoverBitmap));
                builder.getExtras().putBundle("miui.focus.pics", pics);
            }
        } catch (Exception ignored) {
            // 岛扩展失败不影响基础 MediaStyle 通知
        }
    }

    /**
     * 构建小米超级岛通知 JSON（媒体播放场景）
     */
    private String buildIslandParamsJson(String title) {
        String ticker = title.length() > 20 ? title.substring(0, 20) + "…" : title;
        return "{\n" +
                "  \"param_v2\": {\n" +
                "    \"protocol\": 1,\n" +
                "    \"business\": \"video_play\",\n" +
                "    \"updatable\": true,\n" +
                "    \"enableFloat\": false,\n" +
                "    \"ticker\": \"" + escapeJson(ticker) + "\",\n" +
                "    \"aodTitle\": \"" + escapeJson(title) + "\",\n" +
                "    \"param_island\": {\n" +
                "      \"bigIslandArea\": {\n" +
                "        \"imageTextInfoLeft\": {\n" +
                "          \"type\": 1,\n" +
                "          \"picInfo\": { \"type\": 1, \"pic\": \"miui.focus.pic_cover\" },\n" +
                "          \"textInfo\": {\n" +
                "            \"title\": \"" + escapeJson(title) + "\",\n" +
                "            \"content\": \"风云影视\"\n" +
                "          }\n" +
                "        }\n" +
                "      },\n" +
                "      \"smallIslandArea\": {\n" +
                "        \"picInfo\": { \"type\": 1, \"pic\": \"miui.focus.pic_cover\" }\n" +
                "      }\n" +
                "    },\n" +
                "    \"baseInfo\": {\n" +
                "      \"title\": \"正在播放\",\n" +
                "      \"content\": \"" + escapeJson(title) + "\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\t", " ");
    }

    private void refreshNotification() {
        try {
            Notification n = buildNotification();
            notificationManager.notify(NOTIFICATION_ID, n);
        } catch (Exception e) {
            // Android 13+ 没权限时会抛异常，静默处理
            e.printStackTrace();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 媒体按键处理（耳机/蓝牙手柄）
    // ──────────────────────────────────────────────────────────────

    private void handleMediaButton(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePlayPause();
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
            notifyPlaying();
            sendJsEvent("play");
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            notifyPaused();
            sendJsEvent("pause");
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            sendJsEvent("next");
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            sendJsEvent("previous");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 跨进程 JS 事件 —— 通知 WebView
    // （通过 LocalBroadcastManager 或直接 EventBus）
    // 这里用 LocalBroadcastManager 简洁实现
    // ──────────────────────────────────────────────────────────────

    private void sendJsEvent(String event) {
        // Activity 绑定了服务，直接回调
        // 但更稳健的做法是用 LocalBroadcastManager + JS Bridge 轮询
        // 这里留一个 hook 点
        android.util.Log.d("FengYun", "Service → JS event: " + event);
        // 实际项目中用 EventBus 或 LocalBroadcastManager 发送
    }
}
