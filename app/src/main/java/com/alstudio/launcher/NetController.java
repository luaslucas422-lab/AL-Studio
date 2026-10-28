package com.alstudio.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the ad blocker and the WireGuard VPN in step. Android allows one VPN at a time,
 * so when the VPN is on, ad blocking is done by pointing the VPN's DNS at AdGuard instead.
 */
public class NetController {

    private static NetController inst;

    static synchronized NetController get(Context c) {
        if (inst == null) inst = new NetController(c.getApplicationContext());
        return inst;
    }

    private static final String ADBLOCK_DNS = "DNS = 94.140.14.14, 94.140.15.15";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private GoBackend backend;
    private volatile Runnable listener;

    private volatile String vpnState = "off";   // off | connecting | on | error
    private volatile String vpnId, vpnName, vpnConf, error;
    private volatile boolean tunnelUp = false;

    private final Tunnel tunnel = new Tunnel() {
        @Override public String getName() { return "alstudio"; }
        @Override public void onStateChange(Tunnel.State s) {
            if (s == Tunnel.State.DOWN && tunnelUp) {
                tunnelUp = false;
                if ("on".equals(vpnState)) { vpnState = "off"; vpnId = null; vpnName = null; vpnConf = null; }
                notifyChanged();
            }
        }
    };

    private NetController(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("alstudio_net", Context.MODE_PRIVATE);
    }

    void setListener(Runnable r) { listener = r; }

    void notifyChanged() { Runnable r = listener; if (r != null) r.run(); }

    boolean adblockWanted() { return prefs.getBoolean("adblock", false); }

    String statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("adblock", adblockWanted());
            o.put("adblockActive", AdBlockService.running || ("on".equals(vpnState) && adblockWanted()));
            o.put("vpn", vpnState);
            o.put("id", vpnId == null ? JSONObject.NULL : vpnId);
            o.put("name", vpnName == null ? JSONObject.NULL : vpnName);
            o.put("error", error == null ? JSONObject.NULL : error);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    void setAdblock(boolean on) {
        prefs.edit().putBoolean("adblock", on).apply();
        ex.execute(this::apply);
    }

    void connect(String id, String name, String conf) {
        vpnId = id; vpnName = name; vpnConf = conf; error = null;
        vpnState = "connecting";
        notifyChanged();
        ex.execute(this::apply);
    }

    void disconnect() {
        vpnConf = null;
        ex.execute(this::apply);
    }

    private GoBackend backend() {
        if (backend == null) backend = new GoBackend(ctx);
        return backend;
    }

    private void apply() {
        String conf = vpnConf;
        try {
            if (conf != null) {
                stopAdblock();
                String text = adblockWanted() ? withAdblockDns(conf) : conf;
                Config cfg = Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
                if (tunnelUp) {
                    try { backend().setState(tunnel, Tunnel.State.DOWN, null); } catch (Exception ignored) {}
                    tunnelUp = false;
                }
                backend().setState(tunnel, Tunnel.State.UP, cfg);
                tunnelUp = true;
                vpnState = "on";
                error = null;
            } else {
                if (tunnelUp) {
                    try { backend().setState(tunnel, Tunnel.State.DOWN, null); } catch (Exception ignored) {}
                    tunnelUp = false;
                }
                vpnState = "off"; vpnId = null; vpnName = null;
                if (adblockWanted()) startAdblock(); else stopAdblock();
            }
        } catch (Exception e) {
            vpnState = "error";
            error = friendly(e);
            vpnConf = null; vpnId = null; vpnName = null;
            tunnelUp = false;
            if (adblockWanted()) startAdblock();
        }
        notifyChanged();
    }

    private void startAdblock() {
        if (AdBlockService.running) return;
        try { ctx.startService(new Intent(ctx, AdBlockService.class)); } catch (Exception ignored) {}
    }

    private void stopAdblock() {
        AdBlockService s = AdBlockService.instance;
        if (s != null) {
            s.stopNow();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
    }

    static String withAdblockDns(String conf) {
        Pattern dns = Pattern.compile("(?im)^\\s*DNS\\s*=.*$");
        Matcher m = dns.matcher(conf);
        if (m.find()) return m.replaceAll(ADBLOCK_DNS);
        return conf.replaceFirst("(?i)\\[Interface\\]", "[Interface]\n" + ADBLOCK_DNS);
    }

    private static String friendly(Exception e) {
        if (e instanceof BadConfigException) return "That VPN file isn't a valid WireGuard file";
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase().contains("authoriz")) return "Android didn't allow the VPN. Try again and tap OK";
        return "Couldn't connect to that location";
    }
}
