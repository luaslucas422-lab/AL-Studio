package com.alstudio.launcher;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ad blocker: a tiny local "VPN" that only captures DNS lookups and relays them to
 * AdGuard's free ad-blocking DNS. Ad and tracker domains come back empty, so they never load.
 * No other traffic goes through it.
 */
public class AdBlockService extends VpnService {

    static volatile boolean running = false;
    static volatile AdBlockService instance = null;

    private static final String TUN_ADDR = "10.111.222.2";
    private static final String FAKE_DNS = "10.111.222.1";
    private static final String[] UPSTREAM = {"94.140.14.14", "94.140.15.15"};

    private ParcelFileDescriptor tun;
    private FileOutputStream out;
    private ExecutorService pool;
    private Thread loop;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        if (tun == null) start();
        return START_STICKY;
    }

    private void start() {
        try {
            Builder b = new Builder();
            b.setSession("AL Studio ad blocker");
            b.addAddress(TUN_ADDR, 32);
            b.addDnsServer(FAKE_DNS);
            b.addRoute(FAKE_DNS, 32);
            b.setMtu(1500);
            if (Build.VERSION.SDK_INT >= 29) b.setBlocking(true);
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
            tun = b.establish();
            if (tun == null) { stopNow(); return; }
            out = new FileOutputStream(tun.getFileDescriptor());
            pool = Executors.newFixedThreadPool(4);
            running = true;
            loop = new Thread(this::readLoop, "al-adblock");
            loop.start();
        } catch (Exception e) {
            stopNow();
        }
        NetController.get(this).notifyChanged();
    }

    private void readLoop() {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        byte[] buf = new byte[32767];
        while (running) {
            int n;
            try {
                n = in.read(buf);
            } catch (Exception e) {
                if (!running) break;
                n = 0;
            }
            if (n <= 0) {
                try { Thread.sleep(8); } catch (InterruptedException e) { break; }
                continue;
            }
            handle(Arrays.copyOf(buf, n));
        }
    }

    private void handle(final byte[] p) {
        if (p.length < 28 || ((p[0] >> 4) & 0xF) != 4) return;     // IPv4 only
        final int ihl = (p[0] & 0x0F) * 4;
        if ((p[9] & 0xFF) != 17 || p.length < ihl + 8) return;      // UDP only
        int dstPort = u16(p, ihl + 2);
        if (dstPort != 53) return;
        int udpLen = Math.min(u16(p, ihl + 4), p.length - ihl);
        if (udpLen <= 8) return;
        final byte[] query = Arrays.copyOfRange(p, ihl + 8, ihl + udpLen);
        try {
            pool.execute(() -> {
                byte[] resp = ask(query);
                if (resp != null) reply(p, ihl, resp);
            });
        } catch (Exception ignored) {}
    }

    private byte[] ask(byte[] q) {
        for (String up : UPSTREAM) {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s);
                s.setSoTimeout(2500);
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName(up), 53));
                byte[] r = new byte[4096];
                DatagramPacket dp = new DatagramPacket(r, r.length);
                s.receive(dp);
                return Arrays.copyOf(r, dp.getLength());
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void reply(byte[] req, int ihl, byte[] dns) {
        int len = 28 + dns.length;
        byte[] o = new byte[len];
        o[0] = 0x45;
        o[2] = (byte) (len >> 8); o[3] = (byte) len;
        o[6] = 0x40;                       // don't fragment
        o[8] = 64;                         // TTL
        o[9] = 17;                         // UDP
        System.arraycopy(req, 16, o, 12, 4);   // from: the address they asked
        System.arraycopy(req, 12, o, 16, 4);   // to: whoever asked
        int sum = checksum(o, 0, 20);
        o[10] = (byte) (sum >> 8); o[11] = (byte) sum;
        o[20] = 0; o[21] = 53;                                   // source port 53
        o[22] = req[ihl]; o[23] = req[ihl + 1];                  // back to their port
        int ul = 8 + dns.length;
        o[24] = (byte) (ul >> 8); o[25] = (byte) ul;             // UDP checksum left at 0 (allowed on IPv4)
        System.arraycopy(dns, 0, o, 28, dns.length);
        FileOutputStream os = out;
        if (os == null) return;
        synchronized (this) {
            try { os.write(o); } catch (Exception ignored) {}
        }
    }

    private static int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    private static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len; i += 2) sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    /** Stops the blocker and the service. Safe to call more than once. */
    void stopNow() {
        running = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        out = null;
        if (pool != null) pool.shutdownNow();
        if (loop != null) loop.interrupt();
        instance = null;
        stopSelf();
        NetController.get(this).notifyChanged();
    }

    @Override public void onRevoke() { stopNow(); }

    @Override public void onDestroy() { if (running) stopNow(); super.onDestroy(); }
}
