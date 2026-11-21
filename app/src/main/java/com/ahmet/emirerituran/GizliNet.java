package com.ahmet.emirerituran;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gizli Hat eşler arası ağ katmanı. Sunucu yoktur: her cihaz kendi Tor onion hizmetini yayınlar,
 * karşı tarafa doğrudan onion adresi üzerinden (yalnızca Tor SOCKS) bağlanılır.
 * Mesajlar iki taraf aynı anda çevrimiçiyken iletilir; o zamana dek gönderenin cihazında şifreli kuyrukta bekler.
 */
final class GizliNet {
    interface Ui { void event(String type, JSONObject data); }

    static final int T_MSG = 1, T_ACK = 2, T_FILE = 3, T_CHUNK = 4, T_FEND = 5, T_ACCEPT = 6, T_TTL = 7, T_CALL = 8, T_AUDIO = 9, T_PING = 10, T_PONG = 11;
    static final long MAX_FILE = 50L * 1024 * 1024;

    private static GizliNet inst;
    final Context ctx;
    volatile Ui ui;
    private LocalServerSocket server;
    /** Bağlanan soket nesnesi: GC toplarsa temizleyicisi ortak FD'yi kapatır → güçlü referans şart. */
    private LocalSocket bindSock;
    private File sockFile;
    private final Map<Long, List<Session>> sessions = new HashMap<>();
    private final Set<Long> openChats = Collections.synchronizedSet(new HashSet<Long>());
    private final Set<Long> connecting = Collections.synchronizedSet(new HashSet<Long>());
    private final Map<Long, Long> nextTry = new HashMap<>(), backoff = new HashMap<>();
    private final Map<Long, String> lastErr = new HashMap<>();
    private volatile boolean running = false;
    private Thread sched;
    volatile int epoch = 1;
    volatile long lastRotate = System.currentTimeMillis(), visibleChat = -1;
    private final ExecutorService bulk = Executors.newSingleThreadExecutor();
    final GizliCall call;

    static synchronized GizliNet get(Context c) { if (inst == null) inst = new GizliNet(c.getApplicationContext()); return inst; }
    private GizliNet(Context c) { ctx = c; call = new GizliCall(this); GizliTor.get(c).setListener(() -> { emit("tor", tor().status()); GizliService.update(ctx); }); }
    GizliStore st() { return GizliStore.get(ctx); }
    GizliTor tor() { return GizliTor.get(ctx); }

    void emit(String type, JSONObject d) { Ui u = ui; if (u != null) try { u.event(type, d == null ? new JSONObject() : d); } catch (Exception ignored) {} }
    static JSONObject j(Object... kv) { JSONObject o = new JSONObject(); try { for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); } catch (Exception ignored) {} return o; }

    // ---------- yaşam döngüsü ----------
    synchronized void start() {
        running = true;
        tor().start();
        if (sched == null || !sched.isAlive()) { sched = new Thread(this::loop, "gz-sched"); sched.start(); }
    }
    synchronized void stop() {
        running = false;
        call.hangup("offline");
        closeAll();
        closeServer(); if (sockFile != null) sockFile.delete();
        tor().stop();
        emit("tor", tor().status());
    }
    boolean running() { return running; }

    /** Tor hazır olunca: unix soketini aç, kimlik anahtarıyla onion hizmetini yayınla. */
    void onTorReady() {
        new Thread(() -> {
            try {
                GizliStore s = st(); if (!s.hasIdentity()) return;
                openServer();
                tor().publish(s.seed(), "unix:" + sockFile.getAbsolutePath());
                emit("tor", tor().status());
            } catch (Exception e) { tor().error = "Onion yayınlanamadı: " + e.getMessage(); emit("tor", tor().status()); }
        }, "gz-publish").start();
    }

    private synchronized void openServer() throws IOException {
        if (server != null) return;
        File d = new File(ctx.getNoBackupFilesDir(), "gizli"); d.mkdirs();
        sockFile = new File(d, "hat.sock"); sockFile.delete();
        LocalSocket ls = new LocalSocket(LocalSocket.SOCKET_STREAM);
        ls.bind(new LocalSocketAddress(sockFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
        bindSock = ls;
        server = new LocalServerSocket(ls.getFileDescriptor());
        final LocalServerSocket srv = server;
        new Thread(() -> {
            int fails = 0;
            while (running && srv == server) {
                try { final LocalSocket c = srv.accept(); fails = 0; new Thread(() -> incoming(c), "gz-in").start(); }
                catch (Exception e) {
                    if (srv != server || !running) break;
                    dlog("accept fail " + e.getClass().getSimpleName());
                    if (++fails >= 5) { // soket bozulduysa yeniden kur (Tor aynı yolu kullanmaya devam eder)
                        try { reopenServer(srv); } catch (Exception ignored) {}
                        break;
                    }
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
        }, "gz-accept").start();
    }
    private synchronized void reopenServer(LocalServerSocket old) throws IOException {
        if (server != old) return;
        closeServer(); openServer(); dlog("server reopened");
    }
    private synchronized void closeServer() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (bindSock != null) bindSock.close(); } catch (Exception ignored) {}
        server = null; bindSock = null;
    }

    /** Yalnızca test derlemelerinde (-PgzTestShots) logcat'e yazar; sürümde hiçbir şey yazılmaz. İçerik/kimlik yazılmaz. */
    static void dlog(String m) { if (!BuildConfig.GZ_SECURE) android.util.Log.d("GZDBG", m); }

    // ---------- oturum ----------
    final class Session {
        final GizliStore.Contact k; final boolean outgoing; final DataInputStream in; final OutputStream out; final Closeable sock;
        final GizliCrypto.Aead tx, rx; volatile boolean alive = true; final long started = System.currentTimeMillis(); volatile long lastRx = started, lastTx = started;
        final Map<String, Partial> partial = new HashMap<>();
        Session(GizliStore.Contact k, boolean outgoing, InputStream in, OutputStream out, Closeable sock, byte[] txk, byte[] rxk) {
            this.k = k; this.outgoing = outgoing; this.in = new DataInputStream(in); this.out = out; this.sock = sock; tx = new GizliCrypto.Aead(txk); rx = new GizliCrypto.Aead(rxk);
        }
        void send(int type, byte[] payload) throws IOException {
            if (!alive) throw new IOException("oturum kapalı");
            byte[] pt = new byte[payload.length + 1]; pt[0] = (byte) type; System.arraycopy(payload, 0, pt, 1, payload.length);
            synchronized (out) {
                byte[] ct = tx.seal(pt); int n = ct.length;
                out.write(new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n}); out.write(ct); out.flush();
            }
            lastTx = System.currentTimeMillis();
        }
        void sendJ(int type, JSONObject o) throws IOException { send(type, o.toString().getBytes(StandardCharsets.UTF_8)); }
        void close() {
            if (!alive) return; alive = false;
            try { sock.close(); } catch (Exception ignored) {}
            for (Partial p : partial.values()) p.abort(); partial.clear();
            removeSession(this);
        }
        void readLoop() {
            try {
                while (alive) {
                    int n = in.readInt(); if (n < 17 || n > 300000) throw new IOException("çerçeve boyu");
                    byte[] ct = new byte[n]; in.readFully(ct);
                    byte[] pt = rx.open(ct); lastRx = System.currentTimeMillis();
                    byte[] pl = new byte[pt.length - 1]; System.arraycopy(pt, 1, pl, 0, pl.length);
                    try { dispatch(this, pt[0] & 255, pl); } catch (Exception e) { /* tek çerçeve hatası oturumu düşürmez */ }
                }
            } catch (Exception ignored) {
            } finally { close(); }
        }
    }

    static final class Partial {
        String mid, name, mime, thumb; long size, got; int ttl; long ts; File file; OutputStream out; MessageDigest md;
        void abort() { try { if (out != null) out.close(); } catch (Exception ignored) {} if (file != null) GizliStore.secureDelete(file); }
    }

    private void addSession(Session s) {
        List<Session> old = new ArrayList<>();
        synchronized (sessions) {
            List<Session> l = sessions.get(s.k.id); if (l == null) { l = new ArrayList<>(); sessions.put(s.k.id, l); }
            for (Session x : l) if (x.outgoing == s.outgoing) old.add(x);
            l.add(s);
        }
        for (Session x : old) x.close();
        dlog("session+ out=" + s.outgoing + " replaced=" + old.size());
        synchronized (backoff) { backoff.remove(s.k.id); nextTry.remove(s.k.id); lastErr.remove(s.k.id); }
        emit("presence", j("cid", s.k.id, "online", true));
    }
    private void removeSession(Session s) {
        boolean any; dlog("session- out=" + s.outgoing + " age=" + (System.currentTimeMillis() - s.started) / 1000 + "s rxAgo=" + (System.currentTimeMillis() - s.lastRx) / 1000 + "s");
        synchronized (sessions) { List<Session> l = sessions.get(s.k.id); if (l != null) l.remove(s); any = l != null && !l.isEmpty(); }
        if (!any) { emit("presence", j("cid", s.k.id, "online", false)); call.peerLost(s.k.id); }
    }
    Session session(long cid) {
        synchronized (sessions) {
            List<Session> l = sessions.get(cid); if (l == null) return null;
            // En son veri alınan (canlılığı kanıtlanmış) oturumu seç; eşitlikte giden oturum
            Session best = null;
            for (Session s : l) if (s.alive && (best == null || s.lastRx > best.lastRx + 2000 || (Math.abs(s.lastRx - best.lastRx) <= 2000 && s.outgoing && !best.outgoing))) best = s;
            return best;
        }
    }
    boolean online(long cid) { return session(cid) != null; }
    List<Session> sessions(long cid) {
        synchronized (sessions) { List<Session> l = sessions.get(cid); List<Session> out = new ArrayList<>(); if (l != null) for (Session s : l) if (s.alive) out.add(s); return out; }
    }
    /** Arama sinyalini canlı tüm oturumlardan gönderir (yarı açık oturumda kaybolmasın). Gönderilen oturum sayısı. */
    int sendAll(long cid, int type, JSONObject o) {
        int n = 0;
        for (Session s : sessions(cid)) { try { s.sendJ(type, o); n++; } catch (Exception e) { s.close(); } }
        dlog("sendAll type=" + type + " op=" + o.optString("op") + " n=" + n);
        return n;
    }
    private void closeAll() {
        List<Session> all = new ArrayList<>(); synchronized (sessions) { for (List<Session> l : sessions.values()) all.addAll(l); }
        for (Session s : all) s.close();
    }

    // ---------- el sıkışma ----------
    private static final byte[] MAGIC = {'G', 'Z', '1', 1};
    private static void writeH(OutputStream o, byte[] b) throws IOException { o.write(new byte[]{(byte) (b.length >>> 8), (byte) b.length}); o.write(b); o.flush(); }
    private static byte[] readH(DataInputStream in, int expect) throws IOException { int n = in.readUnsignedShort(); if (n != expect) throw new IOException("el sıkışma"); byte[] b = new byte[n]; in.readFully(b); return b; }
    private static byte[] sl(byte[] b, int off, int len) { byte[] o = new byte[len]; System.arraycopy(b, off, o, 0, len); return o; }

    /** Bizim başlattığımız bağlantı (yalnızca Tor SOCKS → karşı tarafın .onion adresi). */
    private void connect(GizliStore.Contact k) throws Exception {
        GizliTor t = tor(); if (!t.ready || t.socks <= 0) throw new IOException("Tor hazır değil");
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", t.socks), 10000);
            s.setSoTimeout(150000); s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream o = s.getOutputStream();
            socks5(in, o, k.onion() + ".onion", 80, "gz" + k.id + "e" + epoch);
            byte[] seed = st().seed(); byte[] me = GizliCrypto.edPublic(seed);
            byte[][] eph = GizliCrypto.xKeyPair(); byte[] nA = GizliCrypto.rand(16);
            byte[] m1 = GizliCrypto.cat(MAGIC, me, eph[1], nA); writeH(o, m1);
            byte[] m2 = readH(in, 144);
            byte[] edB = sl(m2, 0, 32), ephB = sl(m2, 32, 32), nB = sl(m2, 64, 16), sigB = sl(m2, 80, 64);
            if (!GizliCrypto.eq(edB, k.pub)) throw new SecurityException("kimlik uyuşmuyor");
            if (!GizliCrypto.verify(edB, GizliCrypto.cat("GZ1|R".getBytes(StandardCharsets.US_ASCII), m1, sl(m2, 0, 80)), sigB)) throw new SecurityException("imza geçersiz");
            writeH(o, GizliCrypto.sign(seed, GizliCrypto.cat("GZ1|I".getBytes(StandardCharsets.US_ASCII), m1, m2)));
            byte[] keys = GizliCrypto.hkdf(GizliCrypto.xAgree(eph[0], ephB), GizliCrypto.cat(nA, nB), GizliCrypto.cat("gizli-hat-v1".getBytes(StandardCharsets.US_ASCII), me, edB), 64);
            GizliCrypto.wipe(eph[0]); GizliCrypto.wipe(seed);
            s.setSoTimeout(0);
            final Session ss = new Session(k, true, in, o, s, sl(keys, 0, 32), sl(keys, 32, 32)); GizliCrypto.wipe(keys);
            addSession(ss);
            new Thread(ss::readLoop, "gz-rx").start();
            onSession(ss);
        } catch (Exception e) { try { s.close(); } catch (Exception ignored) {} throw e; }
    }

    private void incoming(LocalSocket c) {
        try {
            c.setSoTimeout(60000);
            DataInputStream in = new DataInputStream(c.getInputStream()); OutputStream o = c.getOutputStream();
            byte[] m1 = readH(in, 84);
            for (int i = 0; i < 4; i++) if (m1[i] != MAGIC[i]) throw new IOException("sürüm");
            byte[] edA = sl(m1, 4, 32), ephA = sl(m1, 36, 32), nA = sl(m1, 68, 16);
            byte[] seed = st().seed(); byte[] me = GizliCrypto.edPublic(seed);
            if (GizliCrypto.eq(edA, me)) throw new IOException("kendine bağlantı");
            GizliStore.Contact k = st().byPub(edA);
            if (k != null && "blocked".equals(k.state)) throw new IOException("engelli");
            byte[][] eph = GizliCrypto.xKeyPair(); byte[] nB = GizliCrypto.rand(16);
            byte[] body = GizliCrypto.cat(me, eph[1], nB);
            byte[] sigB = GizliCrypto.sign(seed, GizliCrypto.cat("GZ1|R".getBytes(StandardCharsets.US_ASCII), m1, body));
            byte[] m2 = GizliCrypto.cat(body, sigB); writeH(o, m2);
            byte[] sigA = readH(in, 64);
            if (!GizliCrypto.verify(edA, GizliCrypto.cat("GZ1|I".getBytes(StandardCharsets.US_ASCII), m1, m2), sigA)) throw new SecurityException("imza geçersiz");
            byte[] keys = GizliCrypto.hkdf(GizliCrypto.xAgree(eph[0], ephA), GizliCrypto.cat(nA, nB), GizliCrypto.cat("gizli-hat-v1".getBytes(StandardCharsets.US_ASCII), edA, me), 64);
            GizliCrypto.wipe(eph[0]); GizliCrypto.wipe(seed);
            if (k == null) {
                int pending = 0; for (GizliStore.Contact x : st().contacts()) if ("in".equals(x.state)) pending++;
                if (pending >= 30) throw new IOException("çok fazla istek");
                k = st().addContact(edA, null, "in");
                sysMsg(k, "Bağlantı isteği geldi. Kimliği doğrulayıp kabul edebilir ya da reddedebilirsiniz.");
                notifyUser("Gizli Hat: yeni bağlantı isteği", false);
                emit("contacts", null);
            } else if ("out".equals(k.state)) {
                // Biz eklemiştik, o da bize bağlandı → karşılıklı
                k.state = "ok"; k.pendAccept = true; st().updContact(k); sysMsg(k, "Bağlantı kuruldu. Güvenlik numarasını karşı tarafla ayrı bir kanaldan doğrulayın."); emit("contacts", null);
            }
            c.setSoTimeout(0);
            final Session ss = new Session(k, false, in, o, c, sl(keys, 32, 32), sl(keys, 0, 32)); GizliCrypto.wipe(keys);
            addSession(ss);
            new Thread(ss::readLoop, "gz-rx").start();
            onSession(ss);
        } catch (Exception e) { try { c.close(); } catch (Exception ignored) {} }
    }

    private static void socks5(DataInputStream in, OutputStream o, String host, int port, String user) throws IOException {
        o.write(new byte[]{5, 1, 2}); o.flush();
        byte[] r = new byte[2]; in.readFully(r);
        if (r[0] != 5) throw new IOException("SOCKS");
        if (r[1] == 2) {
            byte[] u = user.getBytes(StandardCharsets.US_ASCII);
            byte[] a = new byte[3 + u.length + 1]; a[0] = 1; a[1] = (byte) u.length; System.arraycopy(u, 0, a, 2, u.length); a[2 + u.length] = 1; a[3 + u.length] = 'x';
            o.write(a); o.flush(); in.readFully(r); if (r[1] != 0) throw new IOException("SOCKS kimlik");
        } else if (r[1] != 0) throw new IOException("SOCKS yöntemi");
        byte[] h = host.getBytes(StandardCharsets.US_ASCII);
        byte[] req = new byte[7 + h.length]; req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3; req[4] = (byte) h.length;
        System.arraycopy(h, 0, req, 5, h.length); req[5 + h.length] = (byte) (port >> 8); req[6 + h.length] = (byte) port;
        o.write(req); o.flush();
        byte[] rep = new byte[4]; in.readFully(rep);
        if (rep[1] != 0) throw new SocksError(rep[1] & 255);
        int skip = rep[3] == 1 ? 6 : rep[3] == 4 ? 18 : (in.readUnsignedByte() + 2);
        in.readFully(new byte[skip]);
    }
    static final class SocksError extends IOException {
        final int code; SocksError(int c) { super(text(c)); code = c; }
        static String text(int c) {
            switch (c) {
                case 0xF0: return "Karşı tarafın onion adresi Tor ağında bulunamadı — kişi şu an çevrimdışı olabilir.";
                case 0xF2: return "Karşı tarafın tanıtım noktalarına ulaşılamadı — kişi çevrimdışı olabilir.";
                case 0xF3: return "Buluşma noktası kurulamadı (Tor ağı yoğun olabilir).";
                case 0xF7: return "Tanıtım zaman aşımı — karşı taraf yanıt vermedi.";
                case 0xF6: return "Onion adresi hatalı.";
                case 0xF1: return "Onion tanımı geçersiz.";
                case 4: case 6: return "Tor üzerinden ulaşılamadı (zaman aşımı).";
                default: return "Tor bağlantı hatası (kod " + c + ").";
            }
        }
    }

    // ---------- oturum açılınca kuyruğu boşalt ----------
    private void onSession(Session s) {
        GizliStore.Contact k = s.k;
        try {
            if (openChats.contains(k.id) && visibleChat == k.id) st().markRead(k.id, readReceipts());
            flush(s);
        } catch (Exception e) { s.close(); }
    }

    private void flush(Session s) throws IOException {
        long cid = s.k.id;
        synchronized (s) {
            GizliStore.Contact k = st().contact(cid);
            if (k == null || "blocked".equals(k.state)) { s.close(); return; }
            if (k.pendAccept) { s.sendJ(T_ACCEPT, new JSONObject()); k.pendAccept = false; st().updContact(k); }
            if (k.pendTtl) { s.sendJ(T_TTL, j("ttl", k.ttl)); k.pendTtl = false; st().updContact(k); }
        }
        List<String> racks = st().pendingReadAcks(cid);
        if (!racks.isEmpty()) { s.sendJ(T_ACK, j("mids", new JSONArray(racks), "st", 3)); st().clearReadAcks(cid, racks); }
        for (GizliStore.Msg m : st().pendingOut(cid)) {
            if ("t".equals(m.kind)) {
                s.sendJ(T_MSG, j("mid", m.mid, "ts", m.ts, "text", m.body.optString("text"), "ttl", m.ttl));
                st().outStatus(cid, m.mid, 1);
            } else if ("f".equals(m.kind)) queueFile(s, m);
        }
        emit("msgs", j("cid", cid));
    }

    private final Set<String> sendingFiles = Collections.synchronizedSet(new HashSet<String>());
    private void queueFile(final Session s, final GizliStore.Msg m) {
        final String key = s.k.id + ":" + m.mid;
        if (!sendingFiles.add(key)) return;
        bulk.execute(() -> {
            try (InputStream in = st().encIn(new File(st().filesDir, m.body.optString("file")))) {
                if (!s.alive) return;
                JSONObject h = j("mid", m.mid, "ts", m.ts, "name", m.body.optString("name"), "mime", m.body.optString("mime"), "size", m.body.optLong("size"), "ttl", m.ttl);
                if (m.body.has("thumb")) h.put("thumb", m.body.optString("thumb"));
                s.sendJ(T_FILE, h);
                byte[] mid = m.mid.getBytes(StandardCharsets.US_ASCII); byte[] buf = new byte[16384]; int n; long sent = 0, size = m.body.optLong("size"); long lastEmit = 0;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                    byte[] p = new byte[1 + mid.length + n]; p[0] = (byte) mid.length; System.arraycopy(mid, 0, p, 1, mid.length); System.arraycopy(buf, 0, p, 1 + mid.length, n);
                    s.send(T_CHUNK, p); sent += n;
                    long now = System.currentTimeMillis();
                    if (now - lastEmit > 700) { lastEmit = now; emit("progress", j("cid", s.k.id, "mid", m.mid, "done", sent, "size", size)); }
                }
                s.sendJ(T_FEND, j("mid", m.mid, "sha", GizliCrypto.hex(md.digest())));
                st().outStatus(s.k.id, m.mid, 1);
                emit("msgs", j("cid", s.k.id));
            } catch (Exception e) {
                emit("error", j("cid", s.k.id, "text", "Dosya gönderimi yarıda kaldı; bağlantı yenilenince tekrar denenecek."));
            } finally { sendingFiles.remove(key); }
        });
    }

    // ---------- gelen çerçeveler ----------
    private void dispatch(Session s, int type, byte[] p) throws Exception {
        // Oturumdaki nesne bayatlayabilir (kabul, ad, süre değişiklikleri) → her çerçevede güncel kaydı oku
        GizliStore.Contact k = st().contact(s.k.id);
        if (k == null || "blocked".equals(k.state)) { s.close(); return; }
        switch (type) {
            case T_MSG: {
                JSONObject o = new JSONObject(new String(p, StandardCharsets.UTF_8));
                String mid = o.getString("mid"); String text = o.optString("text"); if (text.length() > 20000) text = text.substring(0, 20000);
                long id = st().insertMsg(mid, k.id, 0, System.currentTimeMillis(), 0, Math.max(0, o.optInt("ttl")), "t", j("text", text, "sent", o.optLong("ts")));
                s.sendJ(T_ACK, j("mids", new JSONArray().put(mid), "st", 2));
                if (id > 0) received(k);
                break;
            }
            case T_ACK: {
                JSONObject o = new JSONObject(new String(p, StandardCharsets.UTF_8)); JSONArray a = o.optJSONArray("mids"); int stt = o.optInt("st", 2);
                if (a != null) for (int i = 0; i < a.length(); i++) st().outStatus(k.id, a.getString(i), Math.min(3, Math.max(2, stt)));
                emit("msgs", j("cid", k.id));
                break;
            }
            case T_FILE: {
                JSONObject o = new JSONObject(new String(p, StandardCharsets.UTF_8));
                Partial f = new Partial(); f.mid = o.getString("mid"); f.name = safeName(o.optString("name", "dosya")); f.mime = o.optString("mime", "application/octet-stream");
                f.size = o.optLong("size"); f.ttl = Math.max(0, o.optInt("ttl")); f.ts = o.optLong("ts"); f.thumb = o.optString("thumb", null);
                if (f.size <= 0 || f.size > MAX_FILE || !f.mid.matches("[0-9a-f]{8,40}")) break;
                f.file = new File(st().filesDir, "in-" + k.id + "-" + f.mid + ".bin"); f.out = st().encOut(f.file); f.md = MessageDigest.getInstance("SHA-256");
                Partial old = s.partial.put(f.mid, f); if (old != null) old.abort();
                break;
            }
            case T_CHUNK: {
                int ml = p[0] & 255; String mid = new String(p, 1, ml, StandardCharsets.US_ASCII);
                Partial f = s.partial.get(mid); if (f == null) break;
                int n = p.length - 1 - ml; f.got += n;
                if (f.got > f.size) { f.abort(); s.partial.remove(mid); break; }
                f.out.write(p, 1 + ml, n); f.md.update(p, 1 + ml, n);
                break;
            }
            case T_FEND: {
                JSONObject o = new JSONObject(new String(p, StandardCharsets.UTF_8)); String mid = o.getString("mid");
                Partial f = s.partial.remove(mid); if (f == null) break;
                f.out.close(); f.out = null;
                if (f.got != f.size || !GizliCrypto.hex(f.md.digest()).equals(o.optString("sha"))) { f.abort(); break; }
                JSONObject body = j("name", f.name, "mime", f.mime, "size", f.size, "file", f.file.getName(), "sent", f.ts);
                if (f.thumb != null && f.thumb.length() < 60000) body.put("thumb", f.thumb);
                long id = st().insertMsg(mid, k.id, 0, System.currentTimeMillis(), 0, f.ttl, "f", body);
                if (id <= 0) GizliStore.secureDelete(f.file);
                s.sendJ(T_ACK, j("mids", new JSONArray().put(mid), "st", 2));
                if (id > 0) received(k);
                break;
            }
            case T_ACCEPT: {
                if (!"ok".equals(k.state) && !"blocked".equals(k.state)) {
                    k.state = "ok"; st().updContact(k); sysMsg(k, "Karşı taraf bağlantı isteğinizi kabul etti. Güvenlik numarasını ayrı bir kanaldan doğrulayın.");
                    emit("contacts", null);
                }
                break;
            }
            case T_TTL: {
                int ttl = Math.max(0, new JSONObject(new String(p, StandardCharsets.UTF_8)).optInt("ttl"));
                if (ttl != k.ttl) { k.ttl = ttl; st().updContact(k); sysMsg(k, "Karşı taraf kaybolan mesaj süresini değiştirdi: " + ttlText(ttl)); emit("contacts", null); }
                break;
            }
            case T_PING: s.sendJ(T_PONG, new JSONObject(new String(p, StandardCharsets.UTF_8))); break;
            case T_PONG: {
                long t0 = new JSONObject(new String(p, StandardCharsets.UTF_8)).optLong("t"); long rtt = System.currentTimeMillis() - t0;
                if (rtt >= 0 && rtt < 120000) { call.onRtt(k.id, rtt); emit("rtt", j("cid", k.id, "rtt", rtt)); }
                break;
            }
            case T_CALL: dlog("rx call op=" + new JSONObject(new String(p, StandardCharsets.UTF_8)).optString("op") + " out=" + s.outgoing); call.onSignal(k, new JSONObject(new String(p, StandardCharsets.UTF_8))); break;
            case T_AUDIO: call.onAudio(k.id, p); break;
            default: break;
        }
    }

    private void received(GizliStore.Contact k) {
        if (visibleChat == k.id) { st().markRead(k.id, readReceipts()); sendReadAcks(k.id); }
        else notifyUser("Gizli Hat: yeni mesaj", false);
        emit("msgs", j("cid", k.id)); emit("contacts", null);
    }

    static String safeName(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim(); if (s.isEmpty()) s = "dosya";
        return s.length() > 80 ? s.substring(s.length() - 80) : s;
    }
    static String ttlText(int s) {
        if (s <= 0) return "kapalı"; if (s < 60) return s + " sn"; if (s < 3600) return (s / 60) + " dk"; if (s < 86400) return (s / 3600) + " saat"; if (s < 604800) return (s / 86400) + " gün"; return (s / 604800) + " hafta";
    }
    void sysMsg(GizliStore.Contact k, String text) { st().insertMsg("sys" + GizliCrypto.hex(GizliCrypto.rand(6)), k.id, 0, System.currentTimeMillis(), 3, 0, "sys", j("text", text)); emit("msgs", j("cid", k.id)); }

    boolean readReceipts() { return GizliStore.prefs(ctx).getBoolean("readReceipts", true); }

    // ---------- kullanıcı işlemleri ----------
    long sendText(long cid, String text) {
        GizliStore.Contact k = st().contact(cid); if (k == null || "blocked".equals(k.state) || text == null || text.trim().isEmpty()) return -1;
        String mid = GizliCrypto.hex(GizliCrypto.rand(10));
        long id = st().insertMsg(mid, cid, 1, System.currentTimeMillis(), 0, k.ttl, "t", j("text", text));
        kick(cid);
        return id;
    }
    long sendFile(long cid, String name, String mime, File encFile, long size, String thumb) {
        GizliStore.Contact k = st().contact(cid); if (k == null) return -1;
        String mid = GizliCrypto.hex(GizliCrypto.rand(10));
        JSONObject b = j("name", name, "mime", mime, "size", size, "file", encFile.getName()); if (thumb != null) try { b.put("thumb", thumb); } catch (Exception ignored) {}
        long id = st().insertMsg(mid, cid, 1, System.currentTimeMillis(), 0, k.ttl, "f", b);
        kick(cid); return id;
    }
    /** Hemen göndermeyi dene; oturum yoksa bağlantıyı başlat. */
    void kick(final long cid) {
        final Session s = session(cid);
        if (s != null) { new Thread(() -> { try { flush(s); } catch (Exception e) { s.close(); } }, "gz-flush").start(); }
        else { synchronized (backoff) { nextTry.put(cid, 0L); } wake(); }
        emit("msgs", j("cid", cid));
    }
    void sendReadAcks(final long cid) {
        if (!readReceipts()) return;
        final Session s = session(cid); if (s == null) return;
        new Thread(() -> {
            try { List<String> r = st().pendingReadAcks(cid); if (!r.isEmpty()) { s.sendJ(T_ACK, j("mids", new JSONArray(r), "st", 3)); st().clearReadAcks(cid, r); } }
            catch (Exception e) { s.close(); }
        }, "gz-rack").start();
    }
    void setTtl(long cid, int ttl) {
        GizliStore.Contact k = st().contact(cid); if (k == null) return;
        k.ttl = ttl; k.pendTtl = true; st().updContact(k); sysMsg(k, "Kaybolan mesaj süresi: " + ttlText(ttl)); kick(cid);
    }
    void accept(long cid) {
        GizliStore.Contact k = st().contact(cid); if (k == null) return;
        k.state = "ok"; k.pendAccept = true; st().updContact(k); sysMsg(k, "Bağlantı isteğini kabul ettiniz."); kick(cid); emit("contacts", null);
    }
    void block(long cid) {
        GizliStore.Contact k = st().contact(cid); if (k == null) return;
        k.state = "blocked"; st().updContact(k); st().clearChat(cid);
        Session s; while ((s = session(cid)) != null) s.close();
        emit("contacts", null);
    }
    void remove(long cid) { Session s; while ((s = session(cid)) != null) s.close(); st().deleteContact(cid); emit("contacts", null); }

    void chatOpen(long cid, boolean visible) {
        if (visible) { openChats.add(cid); visibleChat = cid; st().markRead(cid, readReceipts()); sendReadAcks(cid); synchronized (backoff) { nextTry.put(cid, 0L); backoff.remove(cid); } wake(); }
        else { if (visibleChat == cid) visibleChat = -1; openChats.remove(cid); }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE); if (nm != null) nm.cancel(GizliService.NOTE_MSG);
    }
    String lastError(long cid) { synchronized (backoff) { return lastErr.get(cid); } }
    long nextTryIn(long cid) { synchronized (backoff) { Long t = nextTry.get(cid); return t == null ? 0 : Math.max(0, t - System.currentTimeMillis()); } }
    boolean isConnecting(long cid) { return connecting.contains(cid); }

    // ---------- devre değiştirme ----------
    int rotateMin() { return GizliStore.prefs(ctx).getInt("rotateMin", 10); }
    void rotate(final boolean manual) {
        new Thread(() -> {
            epoch++;
            List<Session> all = new ArrayList<>(); synchronized (sessions) { for (List<Session> l : sessions.values()) all.addAll(l); }
            long callCid = call.activeCid();
            for (Session s : all) if (manual || s.k.id != callCid) s.close();
            int n = tor().rotate();
            lastRotate = System.currentTimeMillis();
            synchronized (backoff) { for (Long c : new ArrayList<>(openChats)) { nextTry.put(c, 0L); backoff.remove(c); } }
            wake();
            emit("rotated", j("closed", n, "manual", manual, "at", lastRotate));
        }, "gz-rotate").start();
    }

    // ---------- zamanlayıcı ----------
    private final Object wakeLock = new Object();
    void wake() { synchronized (wakeLock) { wakeLock.notifyAll(); } }

    private void loop() {
        long lastExpire = 0, lastPing = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastExpire > 5000) { lastExpire = now; if (st().expire() > 0) { emit("msgs", j("cid", -1)); emit("contacts", null); } }
                GizliTor t = tor();
                if (t.ready && t.onionId != null) {
                    int rm = rotateMin();
                    if (rm > 0 && now - lastRotate > rm * 60000L && call.activeCid() < 0) rotate(false);
                    for (GizliStore.Contact k : st().contacts()) {
                        if ("blocked".equals(k.state)) continue;
                        boolean want = openChats.contains(k.id) || k.pendAccept || k.pendTtl || call.wants(k.id)
                                || (!"in".equals(k.state) && st().hasPending(k.id));
                        boolean active = openChats.contains(k.id) || call.activeCid() == k.id;
                        for (Session x : sessions(k.id)) {
                            // Etkin sohbet/aramada 15 sn'de bir ping gider → 60 sn yanıtsız oturum ölüdür; aksi halde 100 sn
                            long lim = active ? 60000 : 100000;
                            if (now - x.lastRx > lim && now - x.started > lim) { dlog("dead session closed"); x.close(); }
                        }
                        Session s = session(k.id);
                        if (s != null) {
                            boolean idle = !want && call.activeCid() != k.id && now - Math.max(s.lastRx, s.lastTx) > 180000;
                            if (idle) s.close();
                            continue;
                        }
                        if (!want || connecting.contains(k.id)) continue;
                        Long nt; synchronized (backoff) { nt = nextTry.get(k.id); }
                        if (nt != null && now < nt) continue;
                        final GizliStore.Contact kk = k; connecting.add(k.id); emit("presence", j("cid", k.id, "online", false, "connecting", true));
                        new Thread(() -> {
                            try { dlog("connect start"); connect(kk); dlog("connect ok"); }
                            catch (Exception e) { dlog("connect fail " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                synchronized (backoff) {
                                    long b = backoff.containsKey(kk.id) ? Math.min(300000, backoff.get(kk.id) * 2) : 15000;
                                    if (openChats.contains(kk.id) || call.wants(kk.id)) b = Math.min(b, 30000); // açık sohbette daha sık dene
                                    backoff.put(kk.id, b); nextTry.put(kk.id, System.currentTimeMillis() + b);
                                    lastErr.put(kk.id, e instanceof SocksError ? e.getMessage() : (e instanceof SecurityException ? "Güvenlik hatası: " + e.getMessage() : "Bağlanılamadı: karşı taraf çevrimdışı olabilir."));
                                }
                                emit("presence", j("cid", kk.id, "online", false, "connecting", false, "error", lastError(kk.id), "retry", nextTryIn(kk.id)));
                            } finally { connecting.remove(kk.id); }
                        }, "gz-out").start();
                    }
                    if (now - lastPing > 15000) {
                        lastPing = now;
                        Set<Long> pc = new HashSet<>(openChats); if (call.activeCid() >= 0) pc.add(call.activeCid());
                        for (Long c : pc) for (Session s : sessions(c)) try { s.sendJ(T_PING, j("t", now)); } catch (Exception e) { s.close(); }
                    }
                }
                synchronized (wakeLock) { wakeLock.wait(3000); }
            } catch (InterruptedException e) { break; } catch (Exception ignored) {}
        }
    }

    void ping(long cid) { Session s = session(cid); if (s != null) try { s.sendJ(T_PING, j("t", System.currentTimeMillis())); } catch (Exception e) { s.close(); } }

    // ---------- bildirim (içerik/gönderen gösterilmez) ----------
    void notifyUser(String title, boolean call) {
        try {
            if (ui != null && !call) return;
            GizliService.channels(ctx);
            Intent i = new Intent(ctx, GizliActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, call ? 92 : 91, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, call ? GizliService.CH_CALL : GizliService.CH_MSG)
                    .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText("Görmek için Gizli Hat'ı açın.")
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET).setAutoCancel(true).setContentIntent(pi)
                    .setPriority(call ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_DEFAULT);
            if (call) { b.setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setFullScreenIntent(pi, true); }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED))
                nm.notify(call ? GizliService.NOTE_CALL : GizliService.NOTE_MSG, b.build());
        } catch (Exception ignored) {}
    }
    void cancelCallNote() { NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE); if (nm != null) nm.cancel(GizliService.NOTE_CALL); }

    static void reset() { synchronized (GizliNet.class) { if (inst != null) { inst.stop(); inst = null; } } }
}
