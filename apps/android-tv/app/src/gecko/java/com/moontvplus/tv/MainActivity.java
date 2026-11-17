package com.moontvplus.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.net.URLEncoder;

public class MainActivity extends Activity implements RemoteCommandHandler {
    private static GeckoRuntime runtime;
    private static final String PREFS_NAME = "moontv_tv_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String DEFAULT_SERVER_URL = "";

    private boolean loginAutoSubmitted = false;

    private GeckoSession session;
    private GeckoView geckoView;
    private boolean canGoBack = false;
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

        geckoView = new GeckoView(this);
        geckoView.setFocusable(true);
        geckoView.setFocusableInTouchMode(true);
        geckoView.requestFocus();
        setContentView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        if (runtime == null) {
            runtime = GeckoRuntime.create(this);
        }

        session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBackValue) {
                canGoBack = canGoBackValue;
            }
        });
        // GeckoView 页面加载完成回调 → 自动填登录表单
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onSessionLoad(GeckoSession session, int loadState, String url) {
                GeckoSession.ContentDelegate.super.onSessionLoad(session, loadState, url);
                if (url != null && (url.contains("/login") || url.contains("login="))
                        && !loginAutoSubmitted) {
                    mainHandler.postDelayed(MainActivity.this::autoFillLoginForm, 500);
                }
            }
        });
        session.open(runtime);
        geckoView.setSession(session);
        setupLocalRemoteServer();

        // 读已保存的服务器地址 + 账号密码
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_SERVER_URL, null);

        if (savedUrl != null && !savedUrl.isEmpty()) {
            session.loadUri(withLocalRemoteHash(buildTvUrl(savedUrl)));
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
                    session.loadUri(withLocalRemoteHash(normalizedUrl));
                })
                .setNegativeButton("跳过", (dialog, which) ->
                        session.loadUri(withLocalRemoteHash(normalizedUrl)))
                .show();
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

    @Override
    public void onRemoteKey(String key, boolean repeat, String digit) {
        mainHandler.post(() -> dispatchLocalRemoteKey(key, repeat, digit));
    }

    @Override
    public void onRemoteText(String mode, String text) {
        mainHandler.post(() -> dispatchLocalRemoteText(mode, text));
    }

    private void dispatchLocalRemoteKey(String key, boolean repeat, String digit) {
        if (session == null || key == null) return;
        String script = "javascript:window.dispatchEvent(new CustomEvent('moontv:local-remote-key',{detail:{key:"
                + JSONObject.quote(key)
                + ",repeat:" + (repeat ? "true" : "false")
                + ",digit:" + JSONObject.quote(digit == null ? "" : digit)
                + "}}));void(0)";
        session.loadUri(script);
    }

    private void dispatchLocalRemoteText(String mode, String text) {
        if (session == null) return;
        String script = "javascript:window.dispatchEvent(new CustomEvent('moontv:local-remote-text',{detail:{mode:"
                + JSONObject.quote(mode == null ? "replace" : mode)
                + ",text:" + JSONObject.quote(text == null ? "" : text)
                + "}}));void(0)";
        session.loadUri(script);
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

    /** GeckoView 版自动注入 JS 填登录表单 */
    private void autoFillLoginForm() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String username = prefs.getString(KEY_USERNAME, null);
        String password = prefs.getString(KEY_PASSWORD, null);
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) return;
        loginAutoSubmitted = true;

        String jsUser = username.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
        String jsPass = password.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");

        String js = "javascript:"
                + "(function(){"
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
                + "})();void(0)";
        if (session != null) session.loadUri(js);
    }

    @Override
    public void onBackPressed() {
        if (session != null && canGoBack) {
            session.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (localRemoteServer != null) {
            localRemoteServer.stop();
            localRemoteServer = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
