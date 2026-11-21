package com.ahmet.emirerituran;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Base64;

import net.freehaven.tor.control.TorControlConnection;

import org.json.JSONArray;
import org.json.JSONObject;
import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Uygulama içine gömülü Tor (Guardian Project tor-android). Gizli Hat trafiğinin tamamı yalnızca bu Tor üzerinden akar;
 * Tor hazır değilse bağlantı kurulmaz (açık internete geri dönüş yok).
 */
final class GizliTor {
    interface Listener { void onTor(); }
    private static GizliTor inst;
    private final Context ctx;
    private volatile TorService svc;
    private volatile TorControlConnection ctl;
    private boolean bound = false, wantOnline = false;
    volatile int socks = -1, progress = 0;
    volatile String phase = "Kapalı", onionId = null, error = null;
    volatile boolean ready = false, published = false, eventsSet = false;
    private Listener listener;
    private Thread poller;
    private final Map<String, JSONObject> relayCache = new HashMap<>();

    static synchronized GizliTor get(Context c) { if (inst == null) inst = new GizliTor(c.getApplicationContext()); return inst; }
    private GizliTor(Context c) { ctx = c; }
    void setListener(Listener l) { listener = l; }
    private void fire() { Listener l = listener; if (l != null) try { l.onTor(); } catch (Exception ignored) {} }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { svc = ((TorService.LocalBinder) b).getService(); startPoller(); }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; ctl = null; ready = false; published = false; phase = "Tor durdu"; fire(); }
    };

    /** Tor'u başlatır (süreç ömrü boyunca bağlı kalır; çevrimdışıyken ağ kapatılır). */
    synchronized void start() {
        wantOnline = true; error = null;
        if (!bound) {
            writeTorrc();
            phase = "Tor başlatılıyor"; progress = 0; fire();
            bound = ctx.bindService(new Intent(ctx, TorService.class), conn, Context.BIND_AUTO_CREATE);
            if (!bound) { error = "Tor hizmeti başlatılamadı"; fire(); }
        } else {
            new Thread(() -> { try { conf("DisableNetwork", "0"); } catch (Exception e) { error = e.getMessage(); } startPoller(); }, "gz-tor-on").start();
        }
    }

    /** Ağı kapatır ve onion hizmetini kaldırır (Tor kütüphanesi bellekte kalır). */
    synchronized void stop() {
        wantOnline = false; ready = false; published = false; phase = "Çevrimdışı"; progress = 0;
        final String oid = onionId; onionId = null;
        new Thread(() -> {
            try { TorControlConnection c = ctl; if (c != null) { if (oid != null) synchronized (GizliTor.this) { c.delOnion(oid); } conf("DisableNetwork", "1"); } } catch (Exception ignored) {}
        }, "gz-tor-off").start();
        fire();
    }

    private void writeTorrc() {
        try {
            File f = TorService.getTorrc(ctx); f.getParentFile().mkdirs();
            String rc = "ClientOnly 1\nSafeLogging 1\nLog warn stdout\nDisableNetwork 0\n"
                    + "CircuitBuildTimeout 60\nLearnCircuitBuildTimeout 0\nKeepalivePeriod 120\n";
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(rc.getBytes("UTF-8")); }
        } catch (Exception e) { error = "torrc yazılamadı"; }
    }

    private synchronized void startPoller() {
        if (poller != null && poller.isAlive()) return;
        poller = new Thread(() -> {
            int lastP = -1;
            while (wantOnline) {
                try {
                    TorService s = svc;
                    if (s != null && ctl == null) ctl = s.getTorControlConnection();
                    TorControlConnection c = ctl;
                    if (c != null) {
                        if (!eventsSet) { try { synchronized (GizliTor.this) { c.addRawEventListener(this::onEvent); c.setEvents(Arrays.asList("STATUS_CLIENT", "HS_DESC")); } eventsSet = true; } catch (Exception ignored) {} }
                        String bp = info("status/bootstrap-phase");
                        int p = intArg(bp, "PROGRESS"); String sum = strArg(bp, "SUMMARY");
                        if (p >= 0 && !published) { progress = p; phase = p >= 100 ? (ready ? "Tor bağlı · onion yayınlanıyor" : "Tor bağlı") : "Tor'a bağlanıyor %" + p + (sum != null ? " · " + trSummary(sum) : ""); }
                        if (p >= 100 && !ready) {
                            socks = s != null ? s.getSocksPort() : -1;
                            if (socks <= 0) { String l = info("net/listeners/socks"); if (l != null) { String[] t = l.replace("\"", "").split(":"); try { socks = Integer.parseInt(t[t.length - 1].trim()); } catch (Exception ignored) {} } }
                            ready = socks > 0;
                            if (ready) { phase = "Tor bağlı · onion yayınlanıyor"; GizliNet.get(ctx).onTorReady(); }
                        }
                        if (p != lastP) { lastP = p; fire(); }
                        if (ready && published) { fire(); break; }
                    }
                    Thread.sleep(ready ? 2000 : 800);
                } catch (InterruptedException e) { break; } catch (Exception e) { try { Thread.sleep(1500); } catch (InterruptedException ie) { break; } }
            }
        }, "gz-tor-poll");
        poller.start();
    }

    private void onEvent(String keyword, String data) {
        if ("HS_DESC".equals(keyword) && data != null && data.startsWith("UPLOADED ") && onionId != null && data.contains(onionId)) {
            if (!published) { published = true; phase = "Çevrimiçi · onion yayında"; fire(); }
        }
    }

    private static String trSummary(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("directory") || l.contains("consensus")) return "ağ haritası alınıyor";
        if (l.contains("descriptor")) return "röle bilgileri alınıyor";
        if (l.contains("circuit")) return "devre kuruluyor";
        if (l.contains("handshak") || l.contains("connect")) return "röleye bağlanıyor";
        if (l.contains("done")) return "hazır";
        return "hazırlanıyor";
    }

    /** Kimlik anahtarıyla onion hizmetini yayınlar. Hedef: uygulamanın özel klasöründeki unix soketi (yerel TCP portu açılmaz). */
    String publish(byte[] seed, String target) throws Exception {
        TorControlConnection c = ctl; if (c == null) throw new IllegalStateException("Tor hazır değil");
        String key = "ED25519-V3:" + Base64.encodeToString(GizliCrypto.torExpandedKey(seed), Base64.NO_WRAP);
        Map<Integer, String> ports = new HashMap<>(); ports.put(80, target);
        Map<String, String> r;
        synchronized (this) {
            if (onionId != null) { try { c.delOnion(onionId); } catch (Exception ignored) {} }
            r = c.addOnion(key, ports, Collections.singletonList("DiscardPK"));
        }
        onionId = r.get("onionAddress"); if (onionId == null) onionId = r.get("ServiceID");
        if (onionId == null) for (String v : r.values()) if (v != null && v.length() == 56) onionId = v;
        published = false; phase = "Tor bağlı · onion yayınlanıyor"; fire();
        startPoller();
        return onionId;
    }

    private void conf(String k, String v) throws Exception { TorControlConnection c = ctl; if (c != null) synchronized (this) { c.setConf(k, v); } }
    String info(String k) { try { TorControlConnection c = ctl; if (c == null) return null; synchronized (this) { return c.getInfo(k); } } catch (Exception e) { return null; } }

    private static int intArg(String s, String k) { String v = strArg(s, k); try { return v == null ? -1 : Integer.parseInt(v); } catch (Exception e) { return -1; } }
    private static String strArg(String s, String k) {
        if (s == null) return null; int i = s.indexOf(k + "="); if (i < 0) return null; i += k.length() + 1;
        if (i < s.length() && s.charAt(i) == '"') { int j = s.indexOf('"', i + 1); return j < 0 ? s.substring(i + 1) : s.substring(i + 1, j); }
        int j = s.indexOf(' ', i); return j < 0 ? s.substring(i) : s.substring(i, j);
    }

    // ---------- devreler ----------
    static final class Circ { String id, status, purpose, hsState, rend; long created; List<String[]> path = new ArrayList<>(); }

    List<Circ> circuits() {
        List<Circ> out = new ArrayList<>(); String cs = info("circuit-status"); if (cs == null) return out;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (String line : cs.split("\n")) {
            String[] t = line.trim().split(" "); if (t.length < 3) continue;
            Circ c = new Circ(); c.id = t[0]; c.status = t[1];
            if (t[2].startsWith("$")) for (String hop : t[2].split(",")) { String[] h = hop.substring(1).split("[~=]"); c.path.add(new String[]{h[0], h.length > 1 ? h[1] : "?"}); }
            c.purpose = strArg(line, "PURPOSE"); c.hsState = strArg(line, "HS_STATE"); c.rend = strArg(line, "REND_QUERY");
            String tc = strArg(line, "TIME_CREATED"); try { if (tc != null) c.created = f.parse(tc.substring(0, 19)).getTime(); } catch (Exception ignored) {}
            out.add(c);
        }
        return out;
    }

    /** Bir kişiyle kullanılan devre: önce bizim kurduğumuz (istemci) buluşma devresi, yoksa hizmet tarafı. */
    JSONObject circuitFor(String peerOnion, boolean incomingOnly) {
        JSONObject o = new JSONObject();
        try {
            Circ best = null; String kind = null;
            for (Circ c : circuits()) {
                if (!"BUILT".equals(c.status) || c.path.isEmpty()) continue;
                if ("HS_CLIENT_REND".equals(c.purpose) && peerOnion.equals(c.rend) && (best == null || "service".equals(kind) || c.created > best.created)) { best = c; kind = "client"; }
            }
            if (best == null) for (Circ c : circuits()) {
                if (!"BUILT".equals(c.status) || c.path.isEmpty()) continue;
                if ("HS_SERVICE_REND".equals(c.purpose) && (best == null || c.created > best.created)) { best = c; kind = "service"; }
            }
            if (best == null) { o.put("ok", false); return o; }
            o.put("ok", true); o.put("kind", kind); o.put("id", best.id); o.put("created", best.created);
            JSONArray hops = new JSONArray();
            for (int i = 0; i < best.path.size(); i++) {
                JSONObject r = new JSONObject(relay(best.path.get(i)[0], best.path.get(i)[1]).toString());
                String role = i == 0 ? "Giriş (guard)" : (i == best.path.size() - 1 ? ("client".equals(kind) ? "Buluşma noktası" : "Buluşma noktası (karşı tarafın seçtiği)") : "Orta röle");
                r.put("role", role); hops.put(r);
            }
            o.put("hops", hops);
        } catch (Exception e) { try { o.put("ok", false); } catch (Exception ignored) {} }
        return o;
    }

    JSONObject relay(String fp, String nick) {
        synchronized (relayCache) { JSONObject c = relayCache.get(fp); if (c != null) return c; }
        JSONObject r = new JSONObject();
        try {
            r.put("fp", fp); r.put("nick", nick); String ip = "?";
            String ns = info("ns/id/" + fp);
            if (ns != null) for (String line : ns.split("\n")) if (line.startsWith("r ")) {
                String[] t = line.split(" "); if (t.length > 2 && "?".equals(nick)) r.put("nick", t[1]);
                for (String x : t) if (x.matches("\\d{1,3}(\\.\\d{1,3}){3}")) { ip = x; break; }
            }
            r.put("ip", ip);
            String cc = "??".equals(ip) || "?".equals(ip) ? "??" : GizliGeo.country(ctx, ip);
            r.put("cc", cc);
            r.put("country", "??".equals(cc) ? "Bilinmiyor" : new Locale("", cc).getDisplayCountry(new Locale("tr", "TR")));
            if (!"?".equals(ip)) synchronized (relayCache) { relayCache.put(fp, r); }
        } catch (Exception ignored) {}
        return r;
    }

    /** NEWNYM + mevcut buluşma devrelerini kapat: yeni bağlantılar yeni devrelerden kurulur. */
    int rotate() {
        int n = 0;
        try {
            TorControlConnection c = ctl; if (c == null) return 0;
            for (Circ ci : circuits()) {
                if ("HS_CLIENT_REND".equals(ci.purpose) || "HS_SERVICE_REND".equals(ci.purpose)) {
                    try { synchronized (this) { c.closeCircuit(ci.id, false); } n++; } catch (Exception ignored) {}
                }
            }
            synchronized (this) { c.signal("NEWNYM"); }
        } catch (Exception ignored) {}
        return n;
    }

    JSONObject status() {
        JSONObject o = new JSONObject();
        try { o.put("phase", phase); o.put("progress", progress); o.put("ready", ready); o.put("published", published); o.put("error", error); o.put("version", TorService.VERSION_NAME); }
        catch (Exception ignored) {}
        return o;
    }
}
