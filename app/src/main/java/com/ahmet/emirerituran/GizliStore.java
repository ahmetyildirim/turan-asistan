package com.ahmet.emirerituran;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gizli Hat yerel deposu. Yedeklenmeyen klasörde (no_backup/gizli) tutulur.
 * Hassas alanlar (kimlik anahtarı, kişi kimlikleri, takma adlar, mesaj içerikleri, dosyalar) AES-256-GCM ile şifrelidir.
 * Veri anahtarı (DEK) Android Keystore'daki donanım destekli anahtarla sarılır; panik silmede bu anahtar yok edilir
 * (kopyalanmış veri bile çözülemez hale gelir).
 */
final class GizliStore extends SQLiteOpenHelper {
    private static final String KEK = "gizli_kek";
    private static GizliStore inst;
    final Context ctx;
    final File dir, filesDir;
    private byte[] dek;
    private final Map<Long, Contact> contacts = new LinkedHashMap<>();
    private boolean loaded = false;

    static synchronized GizliStore get(Context c) {
        if (inst == null) inst = new GizliStore(c.getApplicationContext());
        return inst;
    }
    static synchronized boolean exists(Context c) { return new File(new File(c.getNoBackupFilesDir(), "gizli"), "dek.bin").exists(); }

    private GizliStore(Context c) {
        super(c, new File(ensure(new File(c.getNoBackupFilesDir(), "gizli")), "gizli.db").getAbsolutePath(), null, 1);
        ctx = c; dir = new File(c.getNoBackupFilesDir(), "gizli"); filesDir = ensure(new File(dir, "files"));
        dek = loadDek();
    }
    private static File ensure(File f) { if (!f.exists()) f.mkdirs(); return f; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("gizli", Context.MODE_PRIVATE); }

    @Override public void onConfigure(SQLiteDatabase db) { db.enableWriteAheadLogging(); try (Cursor c = db.rawQuery("PRAGMA secure_delete=ON", null)) { c.moveToFirst(); } }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v BLOB)");
        db.execSQL("CREATE TABLE contact(id INTEGER PRIMARY KEY AUTOINCREMENT, hpub TEXT UNIQUE, pub BLOB, alias BLOB, state TEXT, ttl INTEGER DEFAULT 0," +
                " verified INTEGER DEFAULT 0, added INTEGER, unread INTEGER DEFAULT 0, last INTEGER DEFAULT 0, pend_accept INTEGER DEFAULT 0, pend_ttl INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE msg(id INTEGER PRIMARY KEY AUTOINCREMENT, mid TEXT, cid INTEGER, dir INTEGER, ts INTEGER, st INTEGER, ttl INTEGER DEFAULT 0," +
                " exp INTEGER DEFAULT 0, kind TEXT, body BLOB, rack INTEGER DEFAULT 0, UNIQUE(cid, mid, dir))");
        db.execSQL("CREATE INDEX msg_cid ON msg(cid, id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    // ---------- anahtarlar ----------
    private SecretKey kek(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(KEK)) {
            if (!create) return null;
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEK, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(KEK, null);
    }
    private byte[] loadDek() {
        File f = new File(dir, "dek.bin");
        try {
            if (f.exists()) {
                byte[] all = readAll(new FileInputStream(f));
                SecretKey k = kek(false); if (k == null) throw new IOException("anahtar yok");
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, all, 0, 12));
                return c.doFinal(all, 12, all.length - 12);
            }
            byte[] d = GizliCrypto.rand(32);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, kek(true));
            byte[] ct = c.doFinal(d);
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(c.getIV()); o.write(ct); }
            return d;
        } catch (Exception e) { throw new IllegalStateException("Gizli Hat anahtar deposu açılamadı: " + e.getMessage(), e); }
    }

    byte[] enc(byte[] pt) {
        if (pt == null) return null;
        try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); byte[] iv = GizliCrypto.rand(12);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, iv));
            return GizliCrypto.cat(iv, c.doFinal(pt)); } catch (Exception e) { throw new RuntimeException(e); }
    }
    byte[] dec(byte[] ct) {
        if (ct == null) return null;
        try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, ct, 0, 12));
            return c.doFinal(ct, 12, ct.length - 12); } catch (Exception e) { return null; }
    }
    byte[] encS(String s) { return s == null ? null : enc(s.getBytes(StandardCharsets.UTF_8)); }
    String decS(byte[] b) { byte[] p = dec(b); return p == null ? null : new String(p, StandardCharsets.UTF_8); }
    private String hidx(byte[] pub) {
        try { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(dek, "HmacSHA256")); return GizliCrypto.hex(m.doFinal(pub)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------- meta ----------
    synchronized byte[] meta(String k) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?", new String[]{k})) { return c.moveToFirst() ? dec(c.getBlob(0)) : null; }
    }
    synchronized void meta(String k, byte[] v) {
        ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", enc(v));
        getWritableDatabase().insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    boolean hasIdentity() { return meta("seed") != null; }
    byte[] seed() { return meta("seed"); }
    byte[] myPub() { byte[] s = seed(); return s == null ? null : GizliCrypto.edPublic(s); }
    synchronized void createIdentity() { if (!hasIdentity()) meta("seed", GizliCrypto.newSeed()); }

    // ---------- PIN ----------
    boolean pinSet() { return meta("pin") != null; }
    private static byte[] pbkdf(String pin, byte[] salt) {
        PKCS5S2ParametersGenerator g = new PKCS5S2ParametersGenerator(new SHA256Digest());
        g.init(pin.getBytes(StandardCharsets.UTF_8), salt, 60000);
        return ((KeyParameter) g.generateDerivedParameters(256)).getKey();
    }
    void setPin(String pin) { byte[] salt = GizliCrypto.rand(16); meta("pin", GizliCrypto.cat(salt, pbkdf(pin, salt))); prefs(ctx).edit().putInt("fails", 0).apply(); }
    boolean checkPin(String pin) {
        byte[] v = meta("pin"); if (v == null) return true;
        byte[] salt = java.util.Arrays.copyOfRange(v, 0, 16), h = java.util.Arrays.copyOfRange(v, 16, v.length);
        boolean ok = GizliCrypto.eq(h, pbkdf(pin, salt));
        SharedPreferences p = prefs(ctx); p.edit().putInt("fails", ok ? 0 : p.getInt("fails", 0) + 1).apply();
        return ok;
    }
    int fails() { return prefs(ctx).getInt("fails", 0); }

    // ---------- kişiler ----------
    static final class Contact {
        long id; byte[] pub; String alias, state; int ttl, unread; boolean verified; long added, last; boolean pendAccept, pendTtl;
        String onion() { return GizliCrypto.onionOf(pub); }
        String idStr() { return GizliCrypto.idOf(pub); }
        String shortId() { return GizliCrypto.shortId(pub); }
        String name() { return alias != null && !alias.isEmpty() ? alias : shortId(); }
        boolean ok() { return "ok".equals(state); }
    }

    private synchronized void ensureLoaded() {
        if (loaded) return; contacts.clear();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,pub,alias,state,ttl,verified,added,unread,last,pend_accept,pend_ttl FROM contact ORDER BY id", null)) {
            while (c.moveToNext()) {
                Contact k = new Contact(); k.id = c.getLong(0); k.pub = dec(c.getBlob(1)); if (k.pub == null) continue;
                k.alias = decS(c.getBlob(2)); k.state = c.getString(3); k.ttl = c.getInt(4); k.verified = c.getInt(5) == 1;
                k.added = c.getLong(6); k.unread = c.getInt(7); k.last = c.getLong(8); k.pendAccept = c.getInt(9) == 1; k.pendTtl = c.getInt(10) == 1;
                contacts.put(k.id, k);
            }
        }
        loaded = true;
    }
    synchronized List<Contact> contacts() { ensureLoaded(); return new ArrayList<>(contacts.values()); }
    synchronized Contact contact(long id) { ensureLoaded(); return contacts.get(id); }
    synchronized Contact byPub(byte[] pub) { ensureLoaded(); for (Contact k : contacts.values()) if (GizliCrypto.eq(k.pub, pub)) return k; return null; }

    synchronized Contact addContact(byte[] pub, String alias, String state) {
        Contact k = byPub(pub); if (k != null) return k;
        ContentValues cv = new ContentValues(); cv.put("hpub", hidx(pub)); cv.put("pub", enc(pub)); cv.put("alias", encS(alias)); cv.put("state", state);
        cv.put("added", System.currentTimeMillis()); cv.put("last", System.currentTimeMillis());
        long id = getWritableDatabase().insert("contact", null, cv);
        loaded = false; return contact(id);
    }
    synchronized void updContact(Contact k) {
        ContentValues cv = new ContentValues(); cv.put("alias", encS(k.alias)); cv.put("state", k.state); cv.put("ttl", k.ttl); cv.put("verified", k.verified ? 1 : 0);
        cv.put("unread", k.unread); cv.put("last", k.last); cv.put("pend_accept", k.pendAccept ? 1 : 0); cv.put("pend_ttl", k.pendTtl ? 1 : 0);
        getWritableDatabase().update("contact", cv, "id=?", new String[]{String.valueOf(k.id)});
    }
    synchronized void deleteContact(long id) {
        clearChat(id);
        getWritableDatabase().delete("contact", "id=?", new String[]{String.valueOf(id)}); loaded = false;
    }
    synchronized void clearChat(long cid) {
        for (Msg m : messages(cid, 0, 100000)) deleteFileOf(m);
        getWritableDatabase().delete("msg", "cid=?", new String[]{String.valueOf(cid)});
    }

    // ---------- mesajlar ----------
    static final class Msg {
        long id, cid, ts, exp; String mid, kind; int dir, st, ttl; JSONObject body; boolean rack;
    }
    /** Durum: 0 kuyrukta, 1 gönderildi (Tor'a yazıldı), 2 iletildi, 3 okundu; gelen için 0 okunmadı, 3 okundu. */
    synchronized long insertMsg(String mid, long cid, int dirOut, long ts, int st, int ttl, String kind, JSONObject body) {
        ContentValues cv = new ContentValues(); cv.put("mid", mid); cv.put("cid", cid); cv.put("dir", dirOut); cv.put("ts", ts); cv.put("st", st);
        cv.put("ttl", ttl); cv.put("kind", kind); cv.put("body", encS(body.toString()));
        long id = getWritableDatabase().insertWithOnConflict("msg", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        if (id > 0) { Contact k = contact(cid); if (k != null) { k.last = System.currentTimeMillis(); if (dirOut == 0 && !"sys".equals(kind)) k.unread++; updContact(k); } }
        return id;
    }
    private Msg row(Cursor c) {
        Msg m = new Msg(); m.id = c.getLong(0); m.mid = c.getString(1); m.cid = c.getLong(2); m.dir = c.getInt(3); m.ts = c.getLong(4); m.st = c.getInt(5);
        m.ttl = c.getInt(6); m.exp = c.getLong(7); m.kind = c.getString(8);
        try { m.body = new JSONObject(decS(c.getBlob(9))); } catch (Exception e) { m.body = new JSONObject(); }
        m.rack = c.getInt(10) == 1; return m;
    }
    private static final String COLS = "id,mid,cid,dir,ts,st,ttl,exp,kind,body,rack";
    synchronized List<Msg> messages(long cid, long beforeId, int limit) {
        List<Msg> out = new ArrayList<>();
        String q = "SELECT " + COLS + " FROM msg WHERE cid=?" + (beforeId > 0 ? " AND id<" + beforeId : "") + " ORDER BY id DESC LIMIT " + limit;
        try (Cursor c = getReadableDatabase().rawQuery(q, new String[]{String.valueOf(cid)})) { while (c.moveToNext()) out.add(0, row(c)); }
        return out;
    }
    synchronized Msg msg(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM msg WHERE id=?", new String[]{String.valueOf(id)})) { return c.moveToFirst() ? row(c) : null; }
    }
    synchronized Msg lastMsg(long cid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM msg WHERE cid=? AND kind!='sys' ORDER BY id DESC LIMIT 1", new String[]{String.valueOf(cid)})) { return c.moveToFirst() ? row(c) : null; }
    }
    /** Karşıya henüz iletilmemiş (onay gelmemiş) giden mesajlar. */
    synchronized List<Msg> pendingOut(long cid) {
        List<Msg> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM msg WHERE cid=? AND dir=1 AND st<2 AND kind!='sys' ORDER BY id", new String[]{String.valueOf(cid)})) { while (c.moveToNext()) out.add(row(c)); }
        return out;
    }
    synchronized List<String> pendingReadAcks(long cid) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT mid FROM msg WHERE cid=? AND dir=0 AND rack=1", new String[]{String.valueOf(cid)})) { while (c.moveToNext()) out.add(c.getString(0)); }
        return out;
    }
    synchronized boolean hasPending(long cid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM msg WHERE cid=? AND ((dir=1 AND st<2 AND kind!='sys') OR (dir=0 AND rack=1)) LIMIT 1", new String[]{String.valueOf(cid)})) { return c.moveToFirst(); }
    }
    synchronized void clearReadAcks(long cid, List<String> mids) {
        for (String m : mids) getWritableDatabase().execSQL("UPDATE msg SET rack=0 WHERE cid=? AND mid=? AND dir=0", new Object[]{cid, m});
    }
    /** Giden mesaj durumunu yükseltir (geri düşürmez). Kaybolan mesaj sayacı gönderimde başlar. */
    synchronized void outStatus(long cid, String mid, int st) {
        long now = System.currentTimeMillis();
        getWritableDatabase().execSQL("UPDATE msg SET st=? WHERE cid=? AND mid=? AND dir=1 AND st<?", new Object[]{st, cid, mid, st});
        getWritableDatabase().execSQL("UPDATE msg SET exp=?+ttl*1000 WHERE cid=? AND mid=? AND dir=1 AND ttl>0 AND exp=0", new Object[]{now, cid, mid});
    }
    /** Sohbet açıkken gelenleri okundu yapar; okundu bilgisi gönderilecekse rack=1. */
    synchronized int markRead(long cid, boolean receipts) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        int n;
        try (Cursor c = db.rawQuery("SELECT count(*) FROM msg WHERE cid=? AND dir=0 AND st<3 AND kind!='sys'", new String[]{String.valueOf(cid)})) { c.moveToFirst(); n = c.getInt(0); }
        if (n > 0) {
            db.execSQL("UPDATE msg SET rack=? WHERE cid=? AND dir=0 AND st<3 AND kind!='sys'", new Object[]{receipts ? 1 : 0, cid});
            db.execSQL("UPDATE msg SET exp=?+ttl*1000 WHERE cid=? AND dir=0 AND st<3 AND ttl>0 AND exp=0", new Object[]{now, cid});
            db.execSQL("UPDATE msg SET st=3 WHERE cid=? AND dir=0 AND st<3", new Object[]{cid});
        }
        Contact k = contact(cid); if (k != null && k.unread != 0) { k.unread = 0; updContact(k); }
        return n;
    }
    synchronized void deleteMsg(long id) { Msg m = msg(id); if (m != null) deleteFileOf(m); getWritableDatabase().delete("msg", "id=?", new String[]{String.valueOf(id)}); }
    /** Süresi dolan kaybolan mesajları siler. */
    synchronized int expire() {
        long now = System.currentTimeMillis(); List<Long> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM msg WHERE exp>0 AND exp<=?", new String[]{String.valueOf(now)})) { while (c.moveToNext()) ids.add(c.getLong(0)); }
        for (long id : ids) deleteMsg(id);
        return ids.size();
    }
    private void deleteFileOf(Msg m) {
        String f = m.body.optString("file", ""); if (!f.isEmpty()) secureDelete(new File(filesDir, f));
    }
    static void secureDelete(File f) {
        if (f == null || !f.exists()) return;
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) {
            long n = r.length(); byte[] z = new byte[8192]; for (long p = 0; p < n; p += z.length) r.write(z, 0, (int) Math.min(z.length, n - p));
        } catch (Exception ignored) {}
        f.delete();
    }

    // ---------- şifreli dosyalar (64 KB'lık GCM parçaları; son parça işaretli) ----------
    OutputStream encOut(File f) throws IOException {
        final OutputStream o = new FileOutputStream(f);
        final byte[] base = GizliCrypto.rand(12);
        o.write("GZF1".getBytes(StandardCharsets.US_ASCII)); o.write(base);
        return new OutputStream() {
            final byte[] buf = new byte[65536]; int n = 0, idx = 0;
            void chunk(boolean last) throws IOException {
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); byte[] iv = base.clone();
                    iv[8] ^= (byte) (idx >>> 24); iv[9] ^= (byte) (idx >>> 16); iv[10] ^= (byte) (idx >>> 8); iv[11] ^= (byte) idx; idx++;
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, iv));
                    c.updateAAD(new byte[]{(byte) (last ? 1 : 0)});
                    byte[] ct = c.doFinal(buf, 0, n);
                    o.write(new byte[]{(byte) (ct.length >>> 24), (byte) (ct.length >>> 16), (byte) (ct.length >>> 8), (byte) ct.length}); o.write(ct); n = 0;
                } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e); }
            }
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                while (len > 0) { if (n == buf.length) chunk(false); int k = Math.min(len, buf.length - n); System.arraycopy(b, off, buf, n, k); n += k; off += k; len -= k; }
            }
            boolean closed = false;
            @Override public void close() throws IOException { if (closed) return; closed = true; chunk(true); o.close(); }
        };
    }
    InputStream encIn(File f) throws IOException {
        final InputStream in = new java.io.BufferedInputStream(new FileInputStream(f));
        byte[] hdr = new byte[16]; readFully(in, hdr);
        if (!"GZF1".equals(new String(hdr, 0, 4, StandardCharsets.US_ASCII))) throw new IOException("bozuk dosya");
        final byte[] base = java.util.Arrays.copyOfRange(hdr, 4, 16);
        return new InputStream() {
            byte[] cur = new byte[0]; int pos = 0, idx = 0; boolean done = false;
            boolean next() throws IOException {
                if (done) return false;
                byte[] l = new byte[4]; readFully(in, l); int len = ((l[0] & 255) << 24) | ((l[1] & 255) << 16) | ((l[2] & 255) << 8) | (l[3] & 255);
                if (len < 16 || len > 65536 + 16) throw new IOException("bozuk parça");
                byte[] ct = new byte[len]; readFully(in, ct);
                byte[] iv = base.clone(); iv[8] ^= (byte) (idx >>> 24); iv[9] ^= (byte) (idx >>> 16); iv[10] ^= (byte) (idx >>> 8); iv[11] ^= (byte) idx; idx++;
                for (int last = 0; last <= 1; last++) {
                    try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, iv));
                        c.updateAAD(new byte[]{(byte) last}); cur = c.doFinal(ct); pos = 0; if (last == 1) done = true; return true; }
                    catch (Exception ignored) {}
                }
                throw new IOException("dosya doğrulanamadı");
            }
            @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : (b[0] & 255); }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                while (pos >= cur.length) { if (!next()) return -1; }
                int k = Math.min(len, cur.length - pos); System.arraycopy(cur, pos, b, off, k); pos += k; return k;
            }
            @Override public void close() throws IOException { in.close(); }
        };
    }
    static void readFully(InputStream in, byte[] b) throws IOException { int o = 0; while (o < b.length) { int n = in.read(b, o, b.length - o); if (n < 0) throw new IOException("eksik veri"); o += n; } }
    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); return o.toByteArray(); }
    }

    // ---------- panik silme ----------
    /** Kimlik, kişiler, mesajlar, dosyalar ve anahtar deposu kalıcı olarak silinir. */
    static synchronized void wipeAll(Context c) {
        Context a = c.getApplicationContext();
        if (inst != null) { try { inst.close(); } catch (Exception ignored) {} GizliCrypto.wipe(inst.dek); inst = null; }
        File d = new File(a.getNoBackupFilesDir(), "gizli");
        deleteTree(d);
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(KEK); } catch (Exception ignored) {}
        prefs(a).edit().clear().commit();
        deleteTree(new File(a.getCacheDir(), "gizli"));
    }
    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] ch = f.listFiles(); if (ch != null) for (File x : ch) deleteTree(x);
        if (f.isFile()) secureDelete(f); else f.delete();
    }
}
