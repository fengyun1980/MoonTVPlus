package com.moontvplus.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.KeyEvent;
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

import java.net.URLEncoder;

public class MainActivity extends Activity implements RemoteCommandHandler {
    private static final String PREFS_NAME = "moontv_tv_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String DEFAULT_SERVER_URL = "";

    private boolean loginAutoSubmitted = false;

    private FrameLayout root;
    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private LocalRemoteServer localRemoteServer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        root = new FrameLayout(this);
        setContentView(root);
        setupWebView();
        setupLocalRemoteServer();

        // 读已保存的服务器地址 + 账号密码
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_SERVER_URL, null);

        if (savedUrl != null && !savedUrl.isEmpty()) {
            webView.loadUrl(withLocalRemoteHash(buildTvUrl(savedUrl)));
        } else {
            askForServerUrlThenCredentials(prefs);
        }
    }

    /** 弹窗①：服务器地址 → 弹窗②：账号密码 → 加载 */
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
                    String raw = urlInput.getText().toString().trim();
                    if (raw.isEmpty()) { askForServerUrlThenCredentials(prefs); return; }
                    String normalized = buildTvUrl(raw);
                    prefs.edit().putString(KEY_SERVER_URL, normalized).apply();
                    askForCredentials(prefs, normalized);
                })
                .show();
    }

    /** 弹窗②：用户名 + 密码 */
    private void askForCredentials(final SharedPreferences prefs, final String normalizedUrl) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        final EditText uInput = new EditText(this);
        uInput.setHint("用户名");
        uInput.setSingleLine(true);
        container.addView(uInput);

        final EditText pInput = new EditText(this);
        pInput.setHint("密码");
        pInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pInput.setSingleLine(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        pInput.setLayoutParams(p);
        container.addView(pInput);

        new AlertDialog.Builder(this)
                .setTitle("② 输入账号密码")
                .setMessage("用于自动登录")
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("确定", (dialog, which) -> {
                    String u = uInput.getText().toString().trim();
                    String pw = pInput.getText().toString();
                    if (!u.isEmpty()) prefs.edit().putString(KEY_USERNAME, u).apply();
                    if (!pw.isEmpty()) prefs.edit().putString(KEY_PASSWORD, pw).apply();
                    loginAutoSubmitted = false;
                    webView.loadUrl(withLocalRemoteHash(normalizedUrl));
                })
                .setNegativeButton("跳过", (dialog, which) ->
                        webView.loadUrl(withLocalRemoteHash(normalizedUrl)))
                .show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView = new WebView(this);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.addJavascriptInterface(new LocalRemoteBridge(), "MoonTVLocalRemote");
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setUserAgentString(settings.getUserAgentString() + " MoonTVPlusAndroidTV WebView");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectLocalRemoteInfo();
                // 自动填登录表单
                if (url != null && (url.contains("/login") || url.contains("login="))
                        && !loginAutoSubmitted) {
                    mainHandler.postDelayed(this::autoFillLoginForm, 500);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                root.addView(customView, new FrameLayout.LayoutParams(
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



    private class LocalRemoteBridge {
        @JavascriptInterface
        public String getRemoteUrl() {
            return localRemoteServer == null ? "" : String.valueOf(localRemoteServer.getRemoteUrl());
        }

        @JavascriptInterface
        public int getPort() {
            return localRemoteServer == null ? -1 : localRemoteServer.getPort();
        }

    }

    private void injectLocalRemoteInfo() {
        if (webView == null || localRemoteServer == null) return;
        String url = localRemoteServer.getRemoteUrl();
        if (url == null) return;
        String safeUrl = url.replace("\\", "\\\\").replace("'", "\\'");
        String script = "window.__MOONTV_LOCAL_REMOTE_URL='" + safeUrl + "';" +
                "window.dispatchEvent(new CustomEvent('moontv:local-remote-info',{detail:{url:'" + safeUrl + "'}}));";
        webView.evaluateJavascript(script, null);
    }

    private void setupLocalRemoteServer() {
        localRemoteServer = new LocalRemoteServer(this);
        localRemoteServer.start();
    }

    private int keyCodeForRemoteKey(String key, String digit) {
        if ("up".equals(key)) return KeyEvent.KEYCODE_DPAD_UP;
        if ("down".equals(key)) return KeyEvent.KEYCODE_DPAD_DOWN;
        if ("left".equals(key)) return KeyEvent.KEYCODE_DPAD_LEFT;
        if ("right".equals(key)) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if ("ok".equals(key)) return KeyEvent.KEYCODE_DPAD_CENTER;
        if ("back".equals(key)) return KeyEvent.KEYCODE_BACK;
        if ("menu".equals(key)) return KeyEvent.KEYCODE_MENU;
        if ("home".equals(key)) return KeyEvent.KEYCODE_HOME;
        if ("playPause".equals(key)) return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if ("pageUp".equals(key)) return KeyEvent.KEYCODE_PAGE_UP;
        if ("pageDown".equals(key)) return KeyEvent.KEYCODE_PAGE_DOWN;
        if ("digit".equals(key) && digit != null && digit.length() == 1 && digit.charAt(0) >= '0' && digit.charAt(0) <= '9') {
            return KeyEvent.KEYCODE_0 + (digit.charAt(0) - '0');
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }


    private void dispatchLocalRemoteKey(String key, boolean repeat, String digit) {
        if (webView == null || key == null) return;
        String safeKey = key.replace("\\", "\\\\").replace("'", "\\'");
        String safeDigit = digit == null ? "" : digit.replace("\\", "\\\\").replace("'", "\\'");
        String script = "window.dispatchEvent(new CustomEvent('moontv:local-remote-key',{detail:{key:'"
                + safeKey + "',repeat:" + (repeat ? "true" : "false") + ",digit:'" + safeDigit + "'}}));";
        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onRemoteKey(String key, boolean repeat, String digit) {
        mainHandler.post(() -> dispatchLocalRemoteKey(key, repeat, digit));
    }

    @Override
    public void onRemoteText(String mode, String text) {
        mainHandler.post(() -> {
            if (webView == null) return;
            String safeMode = mode == null ? "replace" : mode.replace("\\", "\\\\").replace("'", "\\'");
            String safeText = text == null ? "" : text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
            String script = "window.dispatchEvent(new CustomEvent('moontv:local-remote-text',{detail:{mode:'" + safeMode + "',text:'" + safeText + "'}}));";
            webView.evaluateJavascript(script, null);
        });
    }


    private String withLocalRemoteHash(String url) {
        String remoteUrl = localRemoteServer == null ? null : localRemoteServer.getRemoteUrl();
        if (remoteUrl == null || remoteUrl.isEmpty()) return url;
        try {
            return url + "#localRemoteUrl=" + URLEncoder.encode(remoteUrl, "UTF-8");
        } catch (Exception ignored) {
            return url;
        }
    }

    private static String buildTvUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            url = DEFAULT_SERVER_URL;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/tv")) {
            return url;
        }
        return url + "/tv";
    }

    /** 自动注入 JS 填登录表单并提交 */
    private void autoFillLoginForm() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String username = prefs.getString(KEY_USERNAME, null);
        String password = prefs.getString(KEY_PASSWORD, null);
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) return;
        loginAutoSubmitted = true;

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
        if (webView != null) webView.evaluateJavascript(js, null);
    }

    private void hideCustomView() {
        if (customView == null) {
            return;
        }
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (localRemoteServer != null) {
            localRemoteServer.stop();
            localRemoteServer = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
