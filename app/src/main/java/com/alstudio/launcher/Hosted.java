package com.alstudio.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Remembers which apps are "running in AL Studio": the ones you opened from AL Studio.
 * The app bar only ever appears over these, so nothing else on the device is affected.
 * The website open in AL Studio's own browser is kept here too.
 */
final class Hosted {

    private static final String PREFS = "alstudio_hosted";
    private static final int MAX = 8;

    private Hosted() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray apps(Context c) {
        try { return new JSONArray(p(c).getString("apps", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** Most recent first. */
    static synchronized void add(Context c, String pkg) {
        JSONArray old = apps(c), out = new JSONArray();
        out.put(pkg);
        for (int i = 0; i < old.length() && out.length() < MAX; i++) {
            String o = old.optString(i);
            if (!pkg.equals(o) && !o.isEmpty()) out.put(o);
        }
        p(c).edit().putString("apps", out.toString()).apply();
    }

    static synchronized void remove(Context c, String pkg) {
        JSONArray old = apps(c), out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            String o = old.optString(i);
            if (!pkg.equals(o)) out.put(o);
        }
        p(c).edit().putString("apps", out.toString()).apply();
    }

    static synchronized boolean contains(Context c, String pkg) {
        JSONArray a = apps(c);
        for (int i = 0; i < a.length(); i++) if (pkg.equals(a.optString(i))) return true;
        return false;
    }

    static synchronized void setSite(Context c, String url, String title) {
        if (url == null) { p(c).edit().remove("site").apply(); return; }
        try {
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("title", title == null ? "" : title);
            p(c).edit().putString("site", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** {"apps":[pkg,...], "site":{url,title} or null} */
    static synchronized String json(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("apps", apps(c));
            String site = p(c).getString("site", null);
            o.put("site", site == null ? JSONObject.NULL : new JSONObject(site));
            return o.toString();
        } catch (Exception e) {
            return "{\"apps\":[],\"site\":null}";
        }
    }

    // Settings the app bar reads.
    static boolean barEnabled(Context c) { return p(c).getBoolean("bar", true); }
    static void setBar(Context c, boolean on) { p(c).edit().putBoolean("bar", on).apply(); }
    static boolean windowed(Context c) { return p(c).getBoolean("windowed", false); }
    static void setWindowed(Context c, boolean on) { p(c).edit().putBoolean("windowed", on).apply(); }
}
