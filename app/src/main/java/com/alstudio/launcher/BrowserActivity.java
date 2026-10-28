package com.alstudio.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * AL Studio's own browser: websites open here, inside AL Studio, with AL Studio's bar.
 * It's a separate screen from the launcher page, so websites never get access to
 * AL Studio's settings or controls.
 */
public class BrowserActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private WebView web;
    private LinearLayout topBar;
    private TextView title, host;
    private ProgressBar progress;
    private ImageView fwd;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#0d0f17"));
        getWindow().setNavigationBarColor(Color.BLACK);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#07080d"));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ----- AL Studio bar -----
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        topBar.setBackgroundColor(Color.parseColor("#0d0f17"));

        ImageView logo = button(R.drawable.ic_bar_logo, "AL Studio home", v -> goHome());
        GradientDrawable logoBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF5B8FFF, 0xFF2A2F8A});
        logoBg.setShape(GradientDrawable.OVAL);
        logo.setBackground(logoBg);
        topBar.addView(logo);
        topBar.addView(button(R.drawable.ic_bar_back, "Back", v -> goBack()));
        fwd = button(R.drawable.ic_bar_forward, "Forward", v -> { if (web.canGoForward()) web.goForward(); });
        topBar.addView(fwd);
        topBar.addView(button(R.drawable.ic_bar_reload, "Reload", v -> web.reload()));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, dp(10), 0);
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.getPaint().setFakeBoldText(true);
        host = new TextView(this);
        host.setTextColor(0x99FFFFFF);
        host.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        host.setSingleLine(true);
        host.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);
        titles.addView(host);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topBar.addView(button(R.drawable.ic_bar_close, "Close", v -> close()));
        col.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF4F8CFF));
        col.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // ----- The website -----
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                // Links to apps (intent:, market:, mailto:, etc.) open outside.
                try {
                    Intent i = "intent".equals(scheme) ? Intent.parseUri(r.getUrl().toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, r.getUrl());
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    Toast.makeText(BrowserActivity.this, "Can't open that link here", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                host.setText(Uri.parse(url).getHost());
                fwd.setAlpha(web.canGoForward() ? 1f : 0.35f);
                if (url.startsWith("http")) Hosted.setSite(BrowserActivity.this, url, v.getTitle());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView v, String t) { title.setText(t); }

            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            // Full-screen video
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                topBar.setVisibility(View.GONE);
                immersive(true);
            }

            @Override public void onHideCustomView() { exitFullscreen(); }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { Toast.makeText(this, "No app can download that", Toast.LENGTH_SHORT).show(); }
        });
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        load(getIntent());
    }

    private ImageView button(int res, String label, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setContentDescription(label);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        b.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x1FFFFFFF);
        b.setBackground(bg);
        b.setOnClickListener(click);
        // Clear focus ring for TV remotes
        b.setOnFocusChangeListener((v, has) -> { v.setScaleX(has ? 1.15f : 1f); v.setScaleY(has ? 1.15f : 1f); bg.setColor(has ? 0xFFFFFFFF : 0x1FFFFFFF); if (has) ((ImageView) v).setColorFilter(0xFF111111); else ((ImageView) v).clearColorFilter(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void load(Intent i) {
        String url = i == null ? null : i.getStringExtra(EXTRA_URL);
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) { finish(); return; }
        String t = i.getStringExtra(EXTRA_TITLE);
        title.setText(t == null ? "" : t);
        host.setText(Uri.parse(url).getHost());
        web.loadUrl(url);
        Hosted.setSite(this, url, t);
    }

    static final String EXTRA_RESUME = "resume";

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Coming back to a site that's already open: keep it exactly where it was.
        if (intent == null || intent.getBooleanExtra(EXTRA_RESUME, false) || intent.getStringExtra(EXTRA_URL) == null) return;
        setIntent(intent);
        load(intent);
    }

    private void goBack() {
        if (customView != null) { exitFullscreen(); return; }
        if (web.canGoBack()) web.goBack(); else close();
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        topBar.setVisibility(View.VISIBLE);
        immersive(false);
    }

    /** Leaves the site open (it shows as running), and goes back to AL Studio. */
    private void goHome() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    /** Closes the site completely. */
    private void close() {
        Hosted.setSite(this, null, null);
        web.loadUrl("about:blank");
        finishAndRemoveTask();
        goHome();
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        getWindow().getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { goBack(); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Menu button on a TV remote jumps to the AL Studio bar.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            topBar.getChildAt(1).requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
