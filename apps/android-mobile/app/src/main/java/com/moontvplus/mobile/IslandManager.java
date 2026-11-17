package com.moontvplus.mobile;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;

/**
 * 小米 HyperOS 系统能力检测工具
 * <p>
 * 检测维度：
 * 1. 是否小米/红米设备
 * 2. HyperOS 版本（OS1 / OS2 / OS3）
 * 3. 是否支持超级岛（persist.sys.feature.island）
 * 4. 焦点通知协议版本（notification_focus_protocol）
 * 5. 应用是否已获得焦点通知权限
 * <p>
 * 在非小米设备上返回 "不支持"，MediaStyle 通知仍可正常降级运行。
 */
public final class IslandManager {

    private IslandManager() {}

    public static class HyperOSInfo {
        /** true = 检测到小米/红米设备 */
        public boolean isXiaomiDevice;
        /** 0 = 非 HyperOS / MIUI，1 = OS1，2 = OS2，3 = OS3 */
        public int hyperOSVersion;
        /** true = 系统支持超级岛（OS3+） */
        public boolean supportsIsland;
        /** 焦点通知协议版本 */
        public int focusProtocolVersion;
        /** true = 应用已获得焦点通知权限 */
        public boolean hasFocusPermission;
    }

    /**
     * 检测当前设备的 HyperOS / 超级岛支持情况
     */
    public static HyperOSInfo detect(Context context) {
        HyperOSInfo info = new HyperOSInfo();

        // 1. 是否小米设备
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";
        info.isXiaomiDevice = manufacturer.contains("xiaomi")
                || manufacturer.contains("redmi")
                || manufacturer.contains("poco")
                || brand.contains("xiaomi")
                || brand.contains("redmi")
                || brand.contains("poco");

        // 非小米设备直接返回
        if (!info.isXiaomiDevice) {
            return info;
        }

        // 2. HyperOS 版本（ro.mi.os.version.code）
        info.hyperOSVersion = getSystemPropertyInt("ro.mi.os.version.code", 0);

        // 3. 是否支持超级岛（OS3+ 的 persist.sys.feature.island）
        info.supportsIsland = getSystemPropertyBool("persist.sys.feature.island", false);

        // 4. 焦点通知协议版本
        // 1 = OS1 模板, 2 = OS2 模板, 3 = OS3 超级岛模板
        try {
            info.focusProtocolVersion = Settings.System.getInt(
                    context.getContentResolver(),
                    "notification_focus_protocol", 0
            );
        } catch (Exception ignored) {
            info.focusProtocolVersion = 0;
        }

        // 如果协议版本 >= 3，肯定支持岛
        if (info.focusProtocolVersion >= 3) {
            info.supportsIsland = true;
        }

        // 5. 应用是否有焦点通知权限
        info.hasFocusPermission = hasFocusNotificationPermission(context);

        return info;
    }

    /**
     * 查询应用是否已获得"焦点通知"权限
     * 权限被关闭时，HyperOS 会把 MediaStyle 通知退化为普通通知
     */
    public static boolean hasFocusNotificationPermission(Context ctx) {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://miui.statusbar.notification.public");
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("package", ctx.getPackageName());
            android.os.Bundle bundle = ctx.getContentResolver().call(uri, "canShowFocus", null, extras);
            return bundle != null && bundle.getBoolean("canShowFocus", false);
        } catch (Exception e) {
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 系统属性读取（反射）
    // ──────────────────────────────────────────────────────────────

    private static String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getDeclaredMethod("get", String.class, String.class);
            Object result = method.invoke(null, key, defaultValue);
            return result instanceof String ? (String) result : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static int getSystemPropertyInt(String key, int defaultValue) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getDeclaredMethod("getInt", String.class, int.class);
            Object result = method.invoke(null, key, defaultValue);
            return result instanceof Integer ? (Integer) result : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean getSystemPropertyBool(String key, boolean defaultValue) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getDeclaredMethod("getBoolean", String.class, boolean.class);
            Object result = method.invoke(null, key, defaultValue);
            return result instanceof Boolean ? (Boolean) result : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 便捷方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 快速判断：当前设备是否会显示小米超级岛
     */
    public static boolean willShowIsland(Context context) {
        HyperOSInfo info = detect(context);
        return info.isXiaomiDevice && info.supportsIsland && info.hasFocusPermission;
    }
}
