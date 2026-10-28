package com.alstudio.appbar;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The AL Studio app bar (optional add-on). When an app you opened from AL Studio is on screen, a small
 * AL Studio bar floats on top of it (Back, AL Studio home, the app's name, Close),
 * so the app feels like it's running inside AL Studio. Everywhere else, it stays hidden.
 *
 * It only listens for "the app on screen changed" events and never reads window content.
 */
public class AppBarService extends AccessibilityService {

    static volatile AppBarService instance;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout bar;
    private View full;              // everything except the logo button
    private ImageView appIcon;
    private TextView appName;
    private boolean attached = false, expanded = true;
    private String current, lastPkg;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable collapse = () -> setExpanded(false);

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        build();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;              // our own bar
        if (pkg.equals(LinkState.LAUNCHER)) { hide(); return; } // AL Studio has its own controls
        if (isTransient(pkg)) return;
        lastPkg = pkg;
        if (LinkState.enabled(this) && LinkState.contains(this, pkg)) show(pkg);
        else hide();
    }

    /** Keyboards and system pop-ups come and go on top of apps; don't react to them. */
    private boolean isTransient(String pkg) {
        String p = pkg.toLowerCase();
        return p.equals("com.android.systemui") || p.equals("android") || p.contains("inputmethod")
                || p.contains("keyboard") || p.contains("honeyboard") || p.contains("swiftkey")
                || p.contains("permissioncontroller");
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        hide();
        instance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hide();
        instance = null;
        return super.onUnbind(intent);
    }

    // ---------------- The bar ----------------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private ImageView iconButton(int res, String label, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setContentDescription(label);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x22FFFFFF);
        b.setBackground(bg);
        b.setOnClickListener(v -> { click.onClick(v); poke(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(42), dp(42));
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void build() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(5), dp(6), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB10121C);
        bg.setCornerRadius(dp(30));
        bg.setStroke(dp(1), 0x33FFFFFF);
        bar.setBackground(bg);
        bar.setElevation(dp(10));

        // AL Studio button: always visible. Tap to expand the bar, or go home when expanded.
        ImageView logo = iconButton(R.drawable.ic_bar_logo, "AL Studio", v -> { if (!expanded) setExpanded(true); else goHome(); });
        GradientDrawable logoBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF5B8FFF, 0xFF2A2F8A});
        logoBg.setShape(GradientDrawable.OVAL);
        logo.setBackground(logoBg);
        bar.addView(logo);

        LinearLayout rest = new LinearLayout(this);
        rest.setOrientation(LinearLayout.HORIZONTAL);
        rest.setGravity(Gravity.CENTER_VERTICAL);
        rest.addView(iconButton(R.drawable.ic_bar_back, "Back", v -> performGlobalAction(GLOBAL_ACTION_BACK)));

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), 0, dp(12), 0);
        appIcon = new ImageView(this);
        chip.addView(appIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, 0, 0);
        appName = new TextView(this);
        appName.setTextColor(0xFFFFFFFF);
        appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        appName.setSingleLine(true);
        appName.setEllipsize(TextUtils.TruncateAt.END);
        appName.setMaxWidth(dp(170));
        appName.getPaint().setFakeBoldText(true);
        TextView sub = new TextView(this);
        sub.setText("Running in AL Studio");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        texts.addView(appName);
        texts.addView(sub);
        chip.addView(texts);
        chip.setOnClickListener(v -> setExpanded(false));
        rest.addView(chip);

        rest.addView(iconButton(R.drawable.ic_bar_home, "AL Studio home", v -> goHome()));
        rest.addView(iconButton(R.drawable.ic_bar_close, "Close", v -> {
            if (current != null) LinkState.close(this, current);
            goHome();
        }));
        full = rest;
        bar.addView(rest);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(10);
    }

    /** Called when AL Studio sends new settings. */
    void refresh() {
        h.post(() -> {
            if (lastPkg != null && LinkState.enabled(this) && LinkState.contains(this, lastPkg)) show(lastPkg);
            else hide();
        });
    }

    private void setExpanded(boolean on) {
        expanded = on;
        if (full != null) full.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) poke();
    }

    /** Any tap keeps the bar open a little longer. */
    private void poke() {
        h.removeCallbacks(collapse);
        h.postDelayed(collapse, 4500);
    }

    private void show(String pkg) {
        if (bar == null) return;
        boolean changed = !pkg.equals(current);
        current = pkg;
        if (changed) {
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                appIcon.setImageDrawable(pm.getApplicationIcon(ai));
                appName.setText(pm.getApplicationLabel(ai));
            } catch (Exception ex) {
                appIcon.setImageDrawable(null);
                appName.setText(pkg);
            }
        }
        if (!attached) {
            try { wm.addView(bar, lp); attached = true; } catch (Exception ignored) { return; }
            setExpanded(true);
        } else if (changed) {
            setExpanded(true);
        }
    }

    private void hide() {
        h.removeCallbacks(collapse);
        if (attached) {
            try { wm.removeView(bar); } catch (Exception ignored) {}
            attached = false;
        }
        current = null;
    }

    private void goHome() {
        hide();
        Intent i = new Intent().setComponent(new ComponentName(LinkState.LAUNCHER, LinkState.LAUNCHER + ".MainActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception e) { performGlobalAction(GLOBAL_ACTION_HOME); }
    }
}
