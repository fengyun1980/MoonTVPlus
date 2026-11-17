/**
 * 风云影视 Android APP JS Bridge 封装
 *
 * 当 Web 页面运行在 Android 原生壳（FengYunMobile WebView）里时，
 * window.AndroidBridge 对象由原生注入，负责接收播放状态事件。
 *
 * 在普通浏览器（Chrome/Safari）里运行时，这些函数是空操作（noop）。
 * 所以可以安全地在任何页面调用，不会报错。
 *
 * 原生侧对应的是 com.fengyun.mobile.MainActivity.MobileJsBridge
 *
 * 用法：
 *   import { fengyunBridge } from '@/lib/fengyun-bridge';
 *   fengyunBridge.onPlayStart('视频标题', 'https://...cover.jpg', '第3集');
 */

type AndroidBridge = {
  onPlayStart?: (title: string, coverUrl: string, episode: string) => void;
  onPlayPause?: () => void;
  onPlayResume?: () => void;
  onPlayEnd?: () => void;
  onPlayProgress?: (positionSec: number, durationSec: number) => void;
  isInPip?: () => boolean;
};

function getBridge(): AndroidBridge | null {
  if (typeof window === 'undefined') return null;
  const b = (window as any).AndroidBridge as AndroidBridge | undefined;
  return b && typeof b === 'object' ? b : null;
}

export const fengyunBridge = {
  /**
   * 播放开始/切换剧集时调用
   * 原生会启动前台服务 + 发 MediaStyle 通知（小米超级岛自动上岛）
   */
  onPlayStart(title: string, coverUrl: string, episode: string = '') {
    const bridge = getBridge();
    if (!bridge?.onPlayStart) return;
    try {
      bridge.onPlayStart(title || '', coverUrl || '', episode || '');
    } catch (e) {
      // 原生异常不影响 Web 播放
      console.debug('[FengYunBridge] onPlayStart failed', e);
    }
  },

  /** 暂停播放 */
  onPlayPause() {
    const bridge = getBridge();
    if (!bridge?.onPlayPause) return;
    try { bridge.onPlayPause(); } catch (e) { /* noop */ }
  },

  /** 恢复播放 */
  onPlayResume() {
    const bridge = getBridge();
    if (!bridge?.onPlayResume) return;
    try { bridge.onPlayResume(); } catch (e) { /* noop */ }
  },

  /** 播放结束 / 退出播放页时调用 */
  onPlayEnd() {
    const bridge = getBridge();
    if (!bridge?.onPlayEnd) return;
    try { bridge.onPlayEnd(); } catch (e) { /* noop */ }
  },

  /**
   * 进度更新 —— 每秒调用一次效果最佳
   * 原生会更新 MediaSession 的 PlaybackState，岛上进度条同步前进
   */
  onPlayProgress(positionSec: number, durationSec: number) {
    const bridge = getBridge();
    if (!bridge?.onPlayProgress) return;
    try {
      bridge.onPlayProgress(
          Math.max(0, Math.floor(positionSec || 0)),
          Math.max(0, Math.floor(durationSec || 0))
      );
    } catch (e) { /* noop */ }
  },

  /** 当前是否在 PiP 画中画模式里 */
  isInPip(): boolean {
    const bridge = getBridge();
    if (!bridge?.isInPip) return false;
    try { return !!bridge.isInPip(); } catch (e) { return false; }
  },

  /** 当前是否运行在 MoonTV Android 壳里（双重检测：UA + Bridge 对象） */
  isInAndroidApp(): boolean {
    if (typeof navigator === 'undefined') return false;
    const ua = navigator.userAgent || '';
    // ✅ 主要判断：WebView 里设置了 "MoonTV Android" 后缀
    if (ua.includes('MoonTV Android')) return true;
    // fallback：Bridge 对象注入成功
    return getBridge() !== null;
  },
};

/**
 * 监听原生侧 PiP 状态变更事件
 * 原生会 dispatch CustomEvent('moontv:pip-changed') 和 'moontv:pip-exit'
 */
export function onPipChanged(callback: (isInPip: boolean) => void) {
  if (typeof window === 'undefined') return () => {};
  const handler = (e: Event) => {
    const detail = (e as CustomEvent).detail as { isInPip?: boolean } | undefined;
    callback(!!detail?.isInPip);
  };
  window.addEventListener('moontv:pip-changed', handler);
  return () => window.removeEventListener('moontv:pip-changed', handler);
}

export function onPipExit(callback: () => void) {
  if (typeof window === 'undefined') return () => {};
  const handler = () => callback();
  window.addEventListener('moontv:pip-exit', handler);
  return () => window.removeEventListener('moontv:pip-exit', handler);
}
