# 风云影视 · Android 手机版

**风云影视**（原 MoonTVPlus）手机端安卓壳工程。基于 Next.js Web 应用 + WebView，专为小米 HyperOS 适配：

- 🏝️ **小米超级岛** —— 播放时 MediaStyle 通知自动上岛，支持封面、进度、播放控件
- 🖼️ **PiP 画中画** —— 按 Home / 最近任务键自动进入小窗播放
- 🎬 **全屏视频** —— HTML5 `<video>` 全屏支持，硬件加速
- 📱 **全面屏适配** —— 沉浸式状态栏、刘海屏、灵动岛区域
- 🔙 **返回键栈** —— 支持网页历史返回 + PiP 退出 + 退出保护
- 🤖 **跨系统降级** —— 非小米设备正常使用 MediaStyle 通知，功能不丢失

---

## 构建参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `BASE_URL` | `http://192.168.1.10:3000` | 你的服务端地址（**不带 `/tv`**，手机端走首页 `/`） |
| `APP_NAME` | `风云影视` | APP 桌面显示名称 |
| `VERSION_NAME` | `1.0.0` | 版本名 |
| `VERSION_CODE` | `1` | 版本号（整数） |
| `MIN_SDK` | `23` | 最低 Android API（Android 6.0+） |

## 本地构建

```bash
cd apps/android-mobile

# Debug APK
./gradlew assembleDebug -PBASE_URL="https://your-server.com"

# Release APK（需要配置签名）
./gradlew assembleRelease -PBASE_URL="https://your-server.com" \
    -PANDROID_KEYSTORE_PATH=/path/to/keystore.jks \
    -PANDROID_KEYSTORE_PASSWORD=yourpass \
    -PANDROID_KEY_ALIAS=mykey \
    -PANDROID_KEY_PASSWORD=keypass
```

构建产物：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 签名配置

通过环境变量传入（推荐用于 CI）：

```bash
export ANDROID_KEYSTORE_PATH=/path/to/keystore.jks
export ANDROID_KEYSTORE_PASSWORD=storepass
export ANDROID_KEY_ALIAS=mykey
export ANDROID_KEY_PASSWORD=keypass

./gradlew assembleRelease -PBASE_URL="https://your-server.com"
```

---

## 🏝️ 小米超级岛上岛原理

**你什么都不用额外做，MediaStyle 通知自动上岛！**

1. `VideoPlaybackService` 是一个前台服务（`foregroundServiceType=mediaPlayback`）
2. 它创建 `MediaSessionCompat` + `MediaStyle` 通知（带封面图、播放/暂停/上下集按钮）
3. **HyperOS 检测到 MediaStyle 通知 → 自动以"超级岛"胶囊形式展示**
4. 用户在岛上看到：视频封面缩略图、标题、进度条、播放控件

额外还会附加自定义岛 JSON（`miui.focus.param`）增强展示效果，但即使这段代码失效，基础 MediaStyle 上岛依然有效。

**合规提醒**：媒体通知只能在**真正播放时**发送，不能营销滥用。

## 🖼️ PiP 画中画小窗

- **触发**：播放中按 Home 键或最近任务键，自动进入小窗
- **返回**：点击 PiP 窗口或在主界面按返回键，恢复全屏
- **限制**：Android 8.0+ 原生支持，小米/华为/三星均正常工作

## 🔗 Web 端接入 JS Bridge

播放时 Web 端需要调用 JS Bridge 告诉原生：

```javascript
// 播放开始
window.AndroidBridge?.onPlayStart(
  video.title,          // 标题
  video.cover,          // 封面图 URL
  `第${video.episode}集`
);

// 暂停
window.AndroidBridge?.onPlayPause();

// 恢复
window.AndroidBridge?.onPlayResume();

// 播放结束
window.AndroidBridge?.onPlayEnd();

// 进度更新（每秒调用一次）
window.AndroidBridge?.onPlayProgress(currentSecond, totalSecond);

// 查询是否在 PiP 中
if (window.AndroidBridge?.isInPip?.()) { /* 调整 UI */ }
```

Web 端还会收到以下自定义事件：

```javascript
// PiP 状态变更
window.addEventListener('moontv:pip-changed', (e) => {
  console.log('是否在小窗:', e.detail.isInPip);
});

// 退出 PiP 恢复全屏
window.addEventListener('moontv:pip-exit', () => { /* 恢复全屏 UI */ });
```

---

## 目录结构

```
apps/android-mobile/
├── build.gradle.kts                         # 根
├── settings.gradle.kts
├── gradle.properties
├── README.md
└── app/
    ├── build.gradle.kts                     # AGP 8.7.3, compileSdk 35, Java 17
    └── src/main/
        ├── AndroidManifest.xml              # PiP + 前台服务 + 通知权限
        ├── java/com/moontvplus/mobile/
        │   ├── MainActivity.java            # WebView 壳 + PiP + JS Bridge
        │   ├── VideoPlaybackService.java    # MediaSession + MediaStyle 上岛核心
        │   └── IslandManager.java           # HyperOS 系统能力检测
        └── res/
            ├── drawable/logo.png
            ├── layout/activity_main.xml
            ├── values/{colors,strings,styles}.xml
            └── xml/network_security_config.xml
```

## 依赖

| 库 | 版本 | 用途 |
|----|------|------|
| AndroidX Media | 1.7.0 | MediaSessionCompat / MediaStyle 上岛 |
| AndroidX Core | 1.13.1 | 通知权限兼容等 |

## 最低要求

| 项目 | 值 |
|------|-----|
| minSdk | 23（Android 6.0 Marshmallow） |
| targetSdk | 35（Android 15） |
| compileSdk | 35 |
| JDK | 17 |
| AGP | 8.7.3 |
| Gradle | 8.x |
| Android Studio | Hedgehog / Iguana / Jellyfish / Koala / Ladybug |

---

## 常见问题

**Q: 只有小米才能上岛吗？**
A: 是的，超级岛是 HyperOS 独占。但 MediaStyle 通知在所有安卓设备上正常降级到通知栏。

**Q: Android 13+ 通知权限怎么办？**
A: APP 启动时会自动申请。被拒绝后 MediaStyle 通知可能不显示，但 MediaSession 依然有效。

**Q: 为什么我的 PiP 不工作？**
A: 需要 Android 8.0+，某些 ROM（MIUI、ColorOS）有"禁止小窗"开关，检查系统设置。

**Q: 视频在后台不播放了？**
A: WebView 在后台可能会被系统限制。前台服务（VideoPlaybackService）会保持 APP 活跃，但 HTML5 `<video>` 需要 Web 端配合 `play()` 调用恢复。

**Q: 包名想改？**
A: 修改 `app/build.gradle.kts` 里的 `namespace` 和 `applicationId`，以及 `AndroidManifest.xml` 的 `package` 属性，还有所有 Java 文件的 `package` 声明。

---

## License

沿用主项目 MIT License。
