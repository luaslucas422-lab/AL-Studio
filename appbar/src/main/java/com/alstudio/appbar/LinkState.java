package com.alstudio.appbar;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;

/** What AL Studio last told the add-on: which apps it opened, and whether the bar is switched on. */
final class LinkState {
    static final String LAUNCHER = "com.alstudio.launcher";
    static final String PERMISSION = "com.alstudio.permission.LINK";

    private LinkState() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("link", Context.MODE_PRIVATE);
    }

    static void save(Context c, String appsJson, boolean enabled) {
        p(c).edit().putString("apps", appsJson == null ? "[]" : appsJson).putBoolean("enabled", enabled).apply();
    }

    static boolean enabled(Context c) { return p(c).getBoolean("enabled", false); }

    static boolean contains(Context c, String pkg) {
        try {
            JSONArray a = new JSONArray(p(c).getString("apps", "[]"));
            for (int i = 0; i < a.length(); i++) if (pkg.equals(a.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Close button: tell AL Studio to drop the app from its Running row. */
    static void close(Context c, String pkg) {
        try {
            JSONArray a = new JSONArray(p(c).getString("apps", "[]")), out = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!pkg.equals(a.optString(i))) out.put(a.optString(i));
            p(c).edit().putString("apps", out.toString()).apply();
        } catch (Exception ignored) {}
        Intent i = new Intent(LAUNCHER + ".CLOSE_HOSTED").setPackage(LAUNCHER).putExtra("pkg", pkg);
        c.sendBroadcast(i, PERMISSION);
    }
}
