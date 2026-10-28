package com.alstudio.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.view.InputDevice;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AL Studio — a bloat-free home screen. The UI lives in assets/index.html and talks
 * to Android through the small "ALBridge" interface below.
 */
public class MainActivity extends Activity {

    private static final String UI_URL = "file:///android_asset/index.html";
    private static final int ICON_PX = 96;

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String appsCache = null;
    private ValueCallback<Uri[]> fileCallback;
    private static final int PICK_FILE = 41;
    private static final int REQ_VPN = 42;
    private NetController net;
    private Runnable pendingNet;     // what to do once Android allows the VPN

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { scanApps(true); }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("alstudio", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#07080d"));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "ALBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(p.createIntent(), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    toast("No photo picker on this device");
                    return false;
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Only our own page runs inside AL Studio; everything else opens outside.
                String url = r.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) return false;
                openExternal(url);
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(UI_URL);
        web.requestFocus();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        net = NetController.get(this);
        net.setListener(() -> main.post(this::pushNet));
        // Bring the ad blocker back if it was on and Android still allows it.
        if (net.adblockWanted() && VpnService.prepare(this) == null) net.setAdblock(true);

        goImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        if (web != null) web.evaluateJavascript("window.ALResume && ALResume()", null);
    }

    private void pushNet() {
        if (web != null && net != null) web.evaluateJavascript("window.ALOnNet && ALOnNet(" + net.statusJson() + ")", null);
    }

    /** Runs the action now if the VPN permission is already granted, otherwise asks first. */
    private void withVpnPermission(Runnable action, Runnable onDenied) {
        Intent ask = VpnService.prepare(this);
        if (ask == null) { action.run(); return; }
        pendingNet = action;
        pendingDenied = onDenied;
        try { startActivityForResult(ask, REQ_VPN); }
        catch (Exception e) { pendingNet = null; if (onDenied != null) onDenied.run(); }
    }
    private Runnable pendingDenied;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @SuppressWarnings("deprecation")
    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    // Home pressed while we're already the home screen: jump back to the top.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (web != null) web.evaluateJavascript("window.ALHome && ALHome()", null);
    }

    // A home screen never closes on Back; the page decides what Back does.
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.ALBack && ALBack()", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Some TV remotes send Back as BUTTON_B; treat it the same.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B && e.getAction() == KeyEvent.ACTION_UP) {
            onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            Runnable ok = pendingNet, no = pendingDenied;
            pendingNet = null; pendingDenied = null;
            if (res == RESULT_OK) { if (ok != null) ok.run(); }
            else { if (no != null) no.run(); toast("VPN permission is needed for that"); pushNet(); }
            return;
        }
        if (req == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) {}
        if (net != null) net.setListener(null);
        worker.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------------- Apps ----------------

    private void scanApps(boolean refresh) {
        if (!refresh && appsCache != null) { deliverApps(appsCache); return; }
        worker.execute(() -> {
            String json = buildAppList();
            appsCache = json;
            main.post(() -> deliverApps(json));
        });
    }

    private void deliverApps(String json) {
        if (web != null) web.evaluateJavascript("window.ALOnApps && ALOnApps(" + json + ")", null);
    }

    private String buildAppList() {
        PackageManager pm = getPackageManager();
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : categories) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
                seen.add(pkg);
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", String.valueOf(ri.loadLabel(pm)));
                    o.put("pkg", pkg);
                    o.put("cls", ri.activityInfo.name);
                    o.put("system", (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    String icon = iconToData(ri.loadIcon(pm));
                    if (icon != null) o.put("icon", icon);
                    out.put(o);
                } catch (Exception ignored) {}
            }
        }
        return out.toString();
    }

    private String iconToData(Drawable d) {
        if (d == null) return null;
        try {
            Bitmap b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, ICON_PX, ICON_PX);
            d.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private void launch(String pkg, String cls) {
        PackageManager pm = getPackageManager();
        Intent i = null;
        if (cls != null && !cls.isEmpty()) {
            i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(pkg, cls));
        }
        if (i == null || pm.resolveActivity(i, 0) == null) {
            i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        }
        if (i == null) { toast("That app isn't installed any more"); scanApps(true); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception e) { toast("Couldn't open that app"); }
    }

    private void openExternal(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException e) { toast("No app or browser can open that link"); }
    }

    private void openSettings(String action) {
        String a;
        switch (action) {
            case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "sound": a = Settings.ACTION_SOUND_SETTINGS; break;
            case "display": a = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "home": a = Settings.ACTION_HOME_SETTINGS; break;
            case "remote":
                // Android TV's own "pair remote or accessory" screen, if the device has it
                try {
                    startActivity(new Intent("com.google.android.intent.action.CONNECT_INPUT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (Exception ignored) {}
                a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            default: a = Settings.ACTION_SETTINGS;
        }
        Intent i = new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { toast("Settings aren't available on this device"); }
        }
    }

    private String remotes() {
        JSONArray out = new JSONArray();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            String name = d.getName() == null ? "" : d.getName();
            String low = name.toLowerCase();
            if (low.matches(".*(gpio|power|headset|uinput|virtual|button|sensor|fingerprint|touch).*")) continue;
            if (low.contains("cec")) name = "TV remote (HDMI-CEC)";
            int s = d.getSources();
            boolean pad = (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
            boolean dpad = (s & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
            boolean kb = d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
            if (!(pad || dpad || kb)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("type", pad ? "controller" : dpad ? "remote" : "keyboard");
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    private void toast(String msg) { main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()); }

    // ---------------- Bridge for the page ----------------

    private class Bridge {
        @android.webkit.JavascriptInterface
        public String hello() {
            return "{\"ok\":true,\"os\":\"android\",\"sdk\":" + Build.VERSION.SDK_INT + "}";
        }

        @android.webkit.JavascriptInterface
        public String loadState() { return prefs.getString("state", null); }

        @android.webkit.JavascriptInterface
        public void saveState(String json) { prefs.edit().putString("state", json).apply(); }

        @android.webkit.JavascriptInterface
        public void requestApps(boolean refresh) { main.post(() -> scanApps(refresh)); }

        @android.webkit.JavascriptInterface
        public void launchApp(String pkg, String cls) { main.post(() -> launch(pkg, cls)); }

        @android.webkit.JavascriptInterface
        public void openUrl(String url) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
            main.post(() -> openExternal(url));
        }

        @android.webkit.JavascriptInterface
        public void openSystem(String action) { main.post(() -> openSettings(action)); }

        @android.webkit.JavascriptInterface
        public String getRemotes() { return remotes(); }

        @android.webkit.JavascriptInterface
        public String netStatus() { return net == null ? "{}" : net.statusJson(); }

        @android.webkit.JavascriptInterface
        public void setAdblock(boolean on) {
            main.post(() -> {
                if (!on) { net.setAdblock(false); return; }
                withVpnPermission(() -> net.setAdblock(true), () -> net.setAdblock(false));
            });
        }

        @android.webkit.JavascriptInterface
        public void vpnConnect(String id, String name, String conf) {
            main.post(() -> withVpnPermission(() -> net.connect(id, name, conf), null));
        }

        @android.webkit.JavascriptInterface
        public void vpnDisconnect() { main.post(() -> net.disconnect()); }
    }
}
