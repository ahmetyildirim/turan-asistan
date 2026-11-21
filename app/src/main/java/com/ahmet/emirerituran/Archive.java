package com.ahmet.emirerituran;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arşiv Tarama: araştırma botları (koleksiyonlar), PDF belgeleri, sayfa düzeyinde metin parçaları (SQLite FTS4),
 * BM25 sıralı arama, soru-cevap (yapay zekâ ile ya da alıntı tabanlı) ve özet/analiz.
 */
public class Archive extends SQLiteOpenHelper {
    private static Archive inst;
    static synchronized Archive get(Context c) { if (inst == null) inst = new Archive(c.getApplicationContext()); return inst; }
    final Context ctx;
    private Archive(Context c) { super(c, "archive.db", null, 1); ctx = c; }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE coll(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, created INTEGER)");
        db.execSQL("CREATE TABLE doc(id INTEGER PRIMARY KEY AUTOINCREMENT, coll INTEGER, name TEXT, file TEXT, size INTEGER, pages INTEGER DEFAULT 0, done INTEGER DEFAULT 0, "
                + "status TEXT, error TEXT, ocr INTEGER DEFAULT 0, chars INTEGER DEFAULT 0, created INTEGER)");
        db.execSQL("CREATE TABLE meta(id INTEGER PRIMARY KEY AUTOINCREMENT, coll INTEGER, doc INTEGER, page INTEGER, body TEXT)");
        db.execSQL("CREATE INDEX meta_doc ON meta(doc)");
        db.execSQL("CREATE VIRTUAL TABLE chunk USING fts4(norm)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    static File dir(Context c) { File d = new File(c.getFilesDir(), "archive"); d.mkdirs(); return d; }

    // ---------- Türkçe normalleştirme ----------
    static final Pattern NONWORD = Pattern.compile("[^a-z0-9]+");
    static final Locale TR = new Locale("tr", "TR");
    /** Küçük harf (İ/I/ı doğru), aksan/şapka duyarsız, yalnız a-z0-9 ve boşluk. */
    static String fold(String s) {
        if (s == null) return "";
        s = s.replace('İ', 'i').replace('I', 'ı').toLowerCase(TR);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case 'ı': b.append('i'); break; case 'ş': b.append('s'); break; case 'ğ': b.append('g'); break;
                case 'ü': b.append('u'); break; case 'ö': b.append('o'); break; case 'ç': b.append('c'); break;
                default: b.append(ch);
            }
        }
        s = Normalizer.normalize(b, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return NONWORD.matcher(s).replaceAll(" ").trim();
    }

    static final Set<String> STOP = new HashSet<>(Arrays.asList(("ve veya ile bir bu su o da de ki mi mu icin gibi kadar daha cok en ne neden nasil nedir "
            + "hangi kac kim kimdir midir mudur olan olarak ise ama fakat ancak her hem ya yani bunu buna bunun sunu ona onun onu olan oldu olur "
            + "olmak olmasi sonra once diye uzere ayrica ilgili gore karsi tum butun bazi bize biz siz sizin benim beni bana var yok degil "
            + "the and of to in for on is are was be by with as at from that this it an or not").split(" ")));

    static List<String> terms(String q) {
        List<String> out = new ArrayList<>();
        for (String t : fold(q).split(" ")) if (t.length() >= 2 && !STOP.contains(t) && !out.contains(t)) out.add(t);
        return out;
    }
    /** Türkçe ekleri için kaba kök: uzun kelimelerde sondan kırp. */
    static String stem(String t) {
        if (t.length() <= 4) return t;
        return t.substring(0, Math.max(4, (int) Math.ceil(t.length() * 0.65)));
    }

    // ---------- Koleksiyonlar ----------
    long createColl(String name) {
        ContentValues v = new ContentValues(); v.put("name", name.trim().isEmpty() ? "Araştırma botu" : name.trim()); v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("coll", null, v);
    }
    void renameColl(long id, String name) { ContentValues v = new ContentValues(); v.put("name", name.trim()); getWritableDatabase().update("coll", v, "id=?", a(id)); }
    void renameDoc(long id, String name) { ContentValues v = new ContentValues(); v.put("name", name.trim()); getWritableDatabase().update("doc", v, "id=?", a(id)); }
    static String[] a(long id) { return new String[]{String.valueOf(id)}; }

    void deleteDoc(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String file = null;
        try (Cursor c = db.rawQuery("SELECT file FROM doc WHERE id=?", a(id))) { if (c.moveToFirst()) file = c.getString(0); }
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM chunk WHERE rowid IN (SELECT id FROM meta WHERE doc=?)", new Object[]{id});
            db.delete("meta", "doc=?", a(id));
            db.delete("doc", "id=?", a(id));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (file != null) new File(dir(ctx), file).delete();
    }

    void deleteColl(long id) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM doc WHERE coll=?", a(id))) { while (c.moveToNext()) ids.add(c.getLong(0)); }
        for (long d : ids) deleteDoc(d);
        getWritableDatabase().delete("coll", "id=?", a(id));
    }

    JSONObject list() throws Exception {
        JSONObject o = new JSONObject(); JSONArray cs = new JSONArray(), ds = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT c.id, c.name, COUNT(d.id), IFNULL(SUM(d.pages),0), IFNULL(SUM(d.done),0), IFNULL(SUM(d.status IN ('queued','running')),0) FROM coll c LEFT JOIN doc d ON d.coll=c.id GROUP BY c.id ORDER BY c.created", null)) {
            while (c.moveToNext()) cs.put(new JSONObject().put("id", c.getLong(0)).put("name", c.getString(1)).put("docs", c.getInt(2)).put("pages", c.getInt(3)).put("done", c.getInt(4)).put("busy", c.getInt(5)));
        }
        try (Cursor c = db.rawQuery("SELECT id, coll, name, size, pages, done, status, error, ocr, chars FROM doc ORDER BY created", null)) {
            while (c.moveToNext()) ds.put(new JSONObject().put("id", c.getLong(0)).put("coll", c.getLong(1)).put("name", c.getString(2)).put("size", c.getLong(3))
                    .put("pages", c.getInt(4)).put("done", c.getInt(5)).put("status", c.getString(6)).put("error", c.getString(7) == null ? "" : c.getString(7))
                    .put("ocr", c.getInt(8)).put("chars", c.getLong(9)));
        }
        return o.put("colls", cs).put("docs", ds);
    }

    // ---------- Belge ekleme ----------
    long addFromUri(long coll, Uri u) throws Exception {
        String name = "belge.pdf"; long size = 0;
        try (Cursor c = ctx.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && c.getString(n) != null) name = c.getString(n);
                if (s >= 0) size = c.getLong(s);
            }
        } catch (Exception ignored) {}
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) { return addStream(coll, in, name); }
    }

    long addFile(long coll, File f, String name) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) { return addStream(coll, in, name); }
    }

    private long addStream(long coll, InputStream in, String name) throws Exception {
        String file = "d" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1e6) + ".pdf";
        File out = new File(dir(ctx), file);
        byte[] head = new byte[5]; long total = 0;
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024]; int n; boolean first = true;
            while ((n = in.read(buf)) > 0) {
                if (first) { System.arraycopy(buf, 0, head, 0, Math.min(5, n)); first = false; }
                os.write(buf, 0, n); total += n;
            }
        }
        if (!new String(head, "ISO-8859-1").startsWith("%PDF")) { out.delete(); throw new Exception(name + ": PDF dosyası değil"); }
        ContentValues v = new ContentValues();
        v.put("coll", coll); v.put("name", name); v.put("file", file); v.put("size", total); v.put("status", "queued"); v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("doc", null, v);
    }

    void retry(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM chunk WHERE rowid IN (SELECT id FROM meta WHERE doc=?)", new Object[]{id});
        db.delete("meta", "doc=?", a(id));
        ContentValues v = new ContentValues(); v.put("status", "queued"); v.put("done", 0); v.put("error", ""); v.put("chars", 0); v.put("ocr", 0);
        db.update("doc", v, "id=?", a(id));
    }

    // ---------- Parça kaydı ----------
    void addPage(long coll, long doc, int page, String text) {
        if (text == null) return;
        text = text.replace('\u0000', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String part : split(text, 1500, 200)) {
                ContentValues m = new ContentValues(); m.put("coll", coll); m.put("doc", doc); m.put("page", page); m.put("body", part);
                long id = db.insert("meta", null, m);
                db.execSQL("INSERT INTO chunk(rowid, norm) VALUES(?, ?)", new Object[]{id, fold(part)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    static List<String> split(String t, int size, int overlap) {
        List<String> out = new ArrayList<>();
        if (t.length() <= size + 300) { out.add(t); return out; }
        int i = 0;
        while (i < t.length()) {
            int end = Math.min(t.length(), i + size);
            if (end < t.length()) { int sp = t.lastIndexOf(' ', end); if (sp > i + size / 2) end = sp; }
            out.add(t.substring(i, end).trim());
            if (end >= t.length()) break;
            i = Math.max(end - overlap, i + 1);
            int sp = t.indexOf(' ', i); if (sp > 0 && sp < end) i = sp + 1;
        }
        return out;
    }

    void setDoc(long id, ContentValues v) { getWritableDatabase().update("doc", v, "id=?", a(id)); }

    // ---------- Arama (FTS4 + BM25) ----------
    static class Hit { long doc; int page; String body, docName; double score; int matched; }

    List<Hit> search(long coll, String q, int limit) {
        List<String> ts = terms(q);
        List<Hit> out = new ArrayList<>();
        if (ts.isEmpty()) return out;
        StringBuilder m = new StringBuilder();
        List<String> stems = new ArrayList<>();
        for (String t : ts) { String s = stem(t); if (stems.contains(s)) continue; stems.add(s); if (m.length() > 0) m.append(" OR "); m.append(s).append('*'); }
        String sql = "SELECT meta.doc, meta.page, meta.body, doc.name, matchinfo(chunk, 'pcnalx') FROM chunk JOIN meta ON meta.id = chunk.rowid "
                + "JOIN doc ON doc.id = meta.doc WHERE chunk MATCH ?" + (coll > 0 ? " AND meta.coll = " + coll : "") + " LIMIT 4000";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{m.toString()})) {
            while (c.moveToNext()) {
                Hit h = new Hit(); h.doc = c.getLong(0); h.page = c.getInt(1); h.body = c.getString(2); h.docName = c.getString(3);
                double[] r = bm25(c.getBlob(4)); h.score = r[0]; h.matched = (int) r[1];
                h.score *= (0.5 + (double) h.matched / stems.size()); // tüm terimleri içeren parçalar öne
                out.add(h);
            }
        }
        Collections.sort(out, (x, y) -> Double.compare(y.score, x.score));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** matchinfo 'pcnalx' → BM25 (k1=1.2, b=0.75); [puan, eşleşen terim sayısı] */
    static double[] bm25(byte[] blob) {
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder());
        int[] mi = new int[blob.length / 4];
        for (int i = 0; i < mi.length; i++) mi[i] = bb.getInt();
        int p = mi[0], cols = mi[1]; double n = mi[2];
        int aOff = 3, lOff = aOff + cols, xOff = lOff + cols;
        double score = 0; int matched = 0;
        for (int i = 0; i < p; i++) {
            for (int col = 0; col < cols; col++) {
                int base = xOff + 3 * (col + i * cols);
                double tf = mi[base], docsWith = mi[base + 2];
                if (tf <= 0) continue;
                matched++;
                double idf = Math.log(1 + (n - docsWith + 0.5) / (docsWith + 0.5));
                double avg = mi[aOff + col] <= 0 ? 1 : mi[aOff + col], len = mi[lOff + col];
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * len / avg));
            }
        }
        return new double[]{score, matched};
    }

    // ---------- Soru-cevap ----------
    JSONObject ask(long coll, String q) throws Exception {
        List<Hit> hits = search(coll, q, 8);
        JSONObject o = new JSONObject(); JSONArray src = new JSONArray();
        List<String> qt = terms(q);
        for (Hit h : hits) src.put(new JSONObject().put("doc", h.doc).put("name", h.docName).put("page", h.page).put("snippet", snippet(h.body, qt, 320)));
        o.put("sources", src);
        if (hits.isEmpty()) {
            return o.put("answer", "Komutanım, bu botun belgelerinde sorunuzla eşleşen bir bölüm bulamadım. Farklı kelimelerle sormayı deneyebilirsiniz. Arz ederim.").put("mode", "none");
        }
        if (hasAi()) {
            StringBuilder ctxb = new StringBuilder();
            int k = 1;
            for (Hit h : hits) { ctxb.append("[").append(k++).append("] ").append(h.docName).append(", s. ").append(h.page).append(":\n").append(trim(h.body, 1400)).append("\n\n"); }
            try {
                String ans = Files.aiChatSys(ctx,
                        "Sen Turan Asistan'sın; kullanıcıya 'Komutanım' diye hitap eden askeri nezaketli bir emir erisin. Yalnızca verilen arşiv parçalarına "
                        + "dayanarak soruyu Türkçe cevapla. Her bilginin sonunda kaynağı köşeli parantezle belirt: [dosya adı, s. sayfa]. "
                        + "Parçalarda cevap yoksa bunu açıkça söyle, uydurma. En fazla 10 cümle. Sonunda 'Arz ederim.' de.",
                        "Soru: " + q + "\n\nArşiv parçaları:\n" + ctxb);
                return o.put("answer", ans).put("mode", "ai");
            } catch (Exception e) { o.put("aiError", e.getMessage()); }
        }
        // Alıntı tabanlı cevap: en iyi cümleler
        List<String[]> best = bestSentences(hits, qt, 4);
        int cover = 0; for (Hit h : hits) cover = Math.max(cover, hits(h.body, qt));
        boolean weak = qt.size() >= 2 && cover * 2 < qt.size();
        StringBuilder sb = new StringBuilder(weak ? "Komutanım, sorunuzla tam örtüşen bir bölüm bulamadım; en yakın bölümler şunlar:\n" : "Komutanım, arşivde sorunuzla ilgili şu bölümleri buldum:\n");
        for (String[] s : best) sb.append("\n• “").append(s[0]).append("” — ").append(s[1]).append(", s. ").append(s[2]);
        sb.append("\n\nArz ederim.");
        return o.put("answer", sb.toString()).put("mode", "extract").put("weak", weak);
    }

    boolean hasAi() { return !Store.prefs(ctx).getString("ai_key", "").trim().isEmpty(); }

    static String trim(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }

    static final Pattern SPLIT = Pattern.compile("(?<=[.!?…])\\s+(?=[\\p{Lu}0-9\"“'(])|\\n\\s*\\n|\\n(?=\\s*[-•*\\d]+[.)]?\\s)");

    static List<String> sentences(String body) {
        List<String> out = new ArrayList<>();
        for (String s : SPLIT.split(body)) {
            s = s.replaceAll("\\s+", " ").trim();
            if (s.length() < 12) continue;
            while (s.length() > 400) { int cut = s.lastIndexOf(' ', 380); if (cut < 100) cut = 380; out.add(s.substring(0, cut) + "…"); s = s.substring(cut).trim(); }
            out.add(s);
        }
        return out;
    }

    /** Konum koruyan sadeleştirme (karakter sayısı aynı kalır). */
    static String foldKeep(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case 'İ': case 'I': case 'ı': case 'î': case 'Î': ch = 'i'; break;
                case 'Ş': case 'ş': ch = 's'; break; case 'Ğ': case 'ğ': ch = 'g'; break;
                case 'Ü': case 'ü': case 'û': case 'Û': ch = 'u'; break; case 'Ö': case 'ö': ch = 'o'; break;
                case 'Ç': case 'ç': ch = 'c'; break; case 'Â': case 'â': ch = 'a'; break;
                default: ch = Character.toLowerCase(ch);
            }
            b.append((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') ? ch : ' ');
        }
        return b.toString();
    }

    /** Uzun cümlede, soru terimlerinin en yoğun geçtiği yere odaklanan ~len karakterlik pencere. */
    static String focus(String s, List<String> qt, int len) {
        if (s.length() <= len) return s;
        String f = " " + foldKeep(s);
        List<int[]> occ = new ArrayList<>(); // {konum, terim no}
        for (int k = 0; k < qt.size(); k++) {
            String st = " " + stem(qt.get(k));
            for (int i = f.indexOf(st); i >= 0; i = f.indexOf(st, i + 1)) occ.add(new int[]{i, k});
        }
        if (occ.isEmpty()) return s.substring(0, len) + "…";
        int bestPos = occ.get(0)[0], bestCnt = 0;
        for (int[] o : occ) {
            Set<Integer> ks = new HashSet<>();
            for (int[] p2 : occ) if (p2[0] >= o[0] && p2[0] <= o[0] + len * 2 / 3) ks.add(p2[1]);
            if (ks.size() > bestCnt) { bestCnt = ks.size(); bestPos = o[0]; }
        }
        int start = Math.max(0, bestPos - len / 5);
        if (start > 0) { int sp = s.indexOf(' ', start); if (sp > 0 && sp < bestPos) start = sp + 1; }
        int end = Math.min(s.length(), start + len);
        if (end < s.length()) { int sp = s.lastIndexOf(' ', end); if (sp > start + len / 2) end = sp; }
        return (start > 0 ? "…" : "") + s.substring(start, end).trim() + (end < s.length() ? "…" : "");
    }

    static int hits(String sentence, List<String> qt) {
        String f = " " + fold(sentence);
        int hit = 0;
        for (String t : qt) if (f.contains(" " + stem(t))) hit++;
        return hit;
    }

    static List<String[]> bestSentences(List<Hit> hits, List<String> qt, int n) {
        List<Object[]> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int rank = 0, bestHit = 0;
        for (Hit h : hits) {
            for (String s : sentences(h.body)) {
                if (!seen.add(fold(s))) continue;
                int hit = hits(s, qt);
                if (hit == 0) continue;
                bestHit = Math.max(bestHit, hit);
                double sc = hit * 10 - rank * 0.5 - Math.abs(s.length() - 160) / 80.0;
                all.add(new Object[]{sc, new String[]{focus(s, qt, 280), h.docName, String.valueOf(h.page)}, hit});
            }
            rank++;
        }
        Collections.sort(all, (x, y) -> Double.compare((double) y[0], (double) x[0]));
        List<String[]> out = new ArrayList<>();
        int min = Math.max(1, (bestHit + 1) / 2);
        for (Object[] x : all) { if ((int) x[2] < min) continue; out.add((String[]) x[1]); if (out.size() >= n) break; }
        if (out.isEmpty()) for (Hit h : hits) { out.add(new String[]{trim(h.body, 300), h.docName, String.valueOf(h.page)}); if (out.size() >= Math.min(n, 3)) break; }
        return out;
    }

    /** Parçanın soruyla en çok örtüşen cümlesini (bağlamıyla) döndürür. */
    static String snippet(String body, List<String> qt, int len) {
        List<String> ss = sentences(body);
        int best = -1, bh = 0;
        for (int i = 0; i < ss.size(); i++) { int h = hits(ss.get(i), qt); if (h > bh) { bh = h; best = i; } }
        if (best < 0) return trim(body.replace('\n', ' '), len);
        if (ss.get(best).length() > len) return focus(ss.get(best), qt, len);
        StringBuilder sb = new StringBuilder(ss.get(best));
        int i = best + 1;
        while (sb.length() < len && i < ss.size()) sb.append(' ').append(ss.get(i++));
        String r = sb.toString();
        return (best > 0 ? "…" : "") + (r.length() > len ? r.substring(0, len) + "…" : r);
    }

    // ---------- Özet / analiz ----------
    static final Pattern DATE = Pattern.compile("\\b(\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}|\\d{1,2} (?:Ocak|Şubat|Mart|Nisan|Mayıs|Haziran|Temmuz|Ağustos|Eylül|Ekim|Kasım|Aralık) \\d{4})\\b");

    JSONObject summary(long coll, long doc) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        String title; int docs = 0, pages = 0, ocr = 0; long chars = 0;
        Map<Long, String> names = new LinkedHashMap<>();
        if (doc > 0) {
            try (Cursor c = db.rawQuery("SELECT name, pages, ocr, chars FROM doc WHERE id=?", a(doc))) {
                if (!c.moveToFirst()) throw new Exception("Belge bulunamadı");
                title = c.getString(0); pages = c.getInt(1); ocr = c.getInt(2); chars = c.getLong(3); docs = 1; names.put(doc, title);
            }
        } else {
            try (Cursor c = db.rawQuery("SELECT name FROM coll WHERE id=?", a(coll))) { title = c.moveToFirst() ? c.getString(0) : "Bot"; }
            try (Cursor c = db.rawQuery("SELECT id, name, pages, ocr, chars FROM doc WHERE coll=? AND status='ok' ORDER BY created", a(coll))) {
                while (c.moveToNext()) { names.put(c.getLong(0), c.getString(1)); docs++; pages += c.getInt(2); ocr += c.getInt(3); chars += c.getLong(4); }
            }
        }
        if (names.isEmpty()) throw new Exception("Henüz işlenmiş belge yok");
        // Kelime sıklığı ve örnek metin (akış halinde)
        Map<String, int[]> freq = new HashMap<>();
        Map<String, Map<String, Integer>> surface = new HashMap<>();
        Map<String, Integer> dates = new LinkedHashMap<>();
        Map<Long, StringBuilder> sample = new LinkedHashMap<>();
        int budgetPerDoc = Math.max(1500, 14000 / Math.max(1, names.size()));
        String where = doc > 0 ? "doc=" + doc : "coll=" + coll;
        Map<Long, Integer> totalRows = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT doc, COUNT(*) FROM meta WHERE " + where + " GROUP BY doc", null)) { while (c.moveToNext()) totalRows.put(c.getLong(0), c.getInt(1)); }
        Map<Long, Integer> rowIdx = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT doc, page, body FROM meta WHERE " + where + " ORDER BY doc, id", null)) {
            while (c.moveToNext()) {
                long d = c.getLong(0); int pg = c.getInt(1); String body = c.getString(2);
                if (!names.containsKey(d)) continue;
                for (String w : body.split("[^\\p{L}\\p{N}]+")) {
                    if (w.length() < 4) continue;
                    String f = fold(w); if (f.length() < 4 || STOP.contains(f) || f.matches("\\d+")) continue;
                    String key = stem(f);
                    int[] cnt = freq.get(key); if (cnt == null) freq.put(key, cnt = new int[1]); cnt[0]++;
                    Map<String, Integer> sf = surface.get(key); if (sf == null) surface.put(key, sf = new HashMap<>());
                    String lw = w.toLowerCase(TR); Integer k = sf.get(lw); sf.put(lw, k == null ? 1 : k + 1);
                }
                Matcher dm = DATE.matcher(body);
                while (dm.find() && dates.size() < 40) { Integer k = dates.get(dm.group(1)); dates.put(dm.group(1), k == null ? 1 : k + 1); }
                // Örnek: belgenin başından, ortasından ve sonundan eşit aralıklı parçalar
                int idx = rowIdx.containsKey(d) ? rowIdx.get(d) : 0; rowIdx.put(d, idx + 1);
                int tot = totalRows.get(d); int want = Math.max(1, budgetPerDoc / 700); int step = Math.max(1, tot / want);
                StringBuilder sb = sample.get(d); if (sb == null) sample.put(d, sb = new StringBuilder());
                if (idx % step == 0 && sb.length() < budgetPerDoc) sb.append("[").append(names.get(d)).append(", s. ").append(pg).append("] ").append(trim(body, 700)).append("\n");
            }
        }
        List<Map.Entry<String, int[]>> top = new ArrayList<>(freq.entrySet());
        Collections.sort(top, (x, y) -> y.getValue()[0] - x.getValue()[0]);
        List<String> kw = new ArrayList<>();
        for (Map.Entry<String, int[]> e : top) {
            Map<String, Integer> sf = surface.get(e.getKey()); String best = e.getKey(); int bc = -1;
            for (Map.Entry<String, Integer> s : sf.entrySet()) if (s.getValue() > bc) { bc = s.getValue(); best = s.getKey(); }
            kw.add(best + " (" + e.getValue()[0] + ")");
            if (kw.size() >= 15) break;
        }
        StringBuilder stat = new StringBuilder();
        stat.append(doc > 0 ? "Belge: " : "Araştırma botu: ").append(title).append("\n");
        if (doc == 0) stat.append(docs).append(" belge · ");
        stat.append(pages).append(" sayfa · ").append(String.format(Locale.US, "%,d", chars).replace(',', '.')).append(" karakter");
        if (ocr > 0) stat.append(" · ").append(ocr).append(" sayfa OCR ile okundu");
        JSONObject o = new JSONObject().put("title", title).put("stats", stat.toString()).put("keywords", new JSONArray(kw));
        List<String> dl = new ArrayList<>(dates.keySet()); o.put("dates", new JSONArray(dl.size() > 15 ? dl.subList(0, 15) : dl));
        StringBuilder all = new StringBuilder(); for (StringBuilder sb : sample.values()) all.append(sb);
        if (hasAi()) {
            try {
                String ans = Files.aiChatSys(ctx,
                        "Sen Turan Asistan'sın; askeri nezaketle 'Komutanım' diye hitap eden bir kurmay asistanısın. Verilen arşiv örneklerinden Türkçe bir analiz yaz: "
                        + "1) Genel özet (3-5 cümle), 2) Ana konular ve önemli bilgiler (madde madde), 3) Tarihler, kişiler, kurumlar, 4) Dikkat edilmesi/takip edilmesi gerekenler. "
                        + "Önemli bilgilerin yanında kaynağı [dosya, s. sayfa] biçiminde belirt. Uydurma bilgi ekleme. Sonunda 'Arz ederim.' de.",
                        (doc > 0 ? "Belge: " : "Araştırma botu: ") + title + " (" + stat.toString().replace('\n', ' ') + ")\n\nÖrnek parçalar:\n" + all);
                return o.put("text", ans).put("mode", "ai");
            } catch (Exception e) { o.put("aiError", e.getMessage()); }
        }
        // Yapay zekâsız analiz: anahtar kelimelere göre en temsilî cümleler
        List<String> qt = new ArrayList<>();
        for (int i = 0; i < Math.min(8, top.size()); i++) qt.add(top.get(i).getKey());
        List<Hit> pseudo = new ArrayList<>();
        int rows = 0; for (int r : totalRows.values()) rows += r;
        int every = Math.max(1, rows / 600);
        try (Cursor c = db.rawQuery("SELECT meta.doc, meta.page, meta.body FROM meta WHERE " + where + " AND (meta.id % " + every + ") = 0 ORDER BY id LIMIT 700", null)) {
            while (c.moveToNext()) { Hit h = new Hit(); h.doc = c.getLong(0); h.page = c.getInt(1); h.body = c.getString(2); h.docName = names.get(h.doc); if (h.docName != null) pseudo.add(h); }
        }
        StringBuilder sb = new StringBuilder("Emir eri Turan, tekmil veriyorum. Komutanım, ").append(doc > 0 ? "belgenin" : "botun").append(" analizini arz ediyorum:\n\n").append(stat).append("\n\n");
        sb.append("Öne çıkan kavramlar: ").append(android.text.TextUtils.join(", ", kw.subList(0, Math.min(12, kw.size())))).append("\n");
        if (!dl.isEmpty()) sb.append("Geçen tarihler: ").append(android.text.TextUtils.join(", ", dl.subList(0, Math.min(10, dl.size())))).append("\n");
        sb.append("\nTemsilî bölümler:");
        for (String[] s : bestSentences(pseudo, qt, 6)) sb.append("\n• “").append(s[0]).append("” — ").append(s[1]).append(", s. ").append(s[2]);
        if (doc == 0 && names.size() > 1) {
            sb.append("\n\nBelgeler:");
            for (String n : names.values()) sb.append("\n• ").append(n);
        }
        sb.append("\n\n(Daha ayrıntılı özet için Ayarlar'dan yapay zekâ anahtarı girebilirsiniz.) Arz ederim.");
        return o.put("text", sb.toString()).put("mode", "extract");
    }

    /** Sıradaki kuyruktaki belge (yoksa -1). */
    long nextQueued() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM doc WHERE status IN ('running','queued') ORDER BY status='running' DESC, created LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    ContentValues docRow(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT coll, name, file, done, ocr, chars FROM doc WHERE id=?", a(id))) {
            if (!c.moveToFirst()) return null;
            ContentValues v = new ContentValues();
            v.put("coll", c.getLong(0)); v.put("name", c.getString(1)); v.put("file", c.getString(2)); v.put("done", c.getInt(3)); v.put("ocr", c.getInt(4)); v.put("chars", c.getLong(5));
            return v;
        }
    }

    /** Yarım kalmış belgede son sayfadan devam edebilmek için o sayfanın kayıtlarını temizle. */
    void dropPagesFrom(long doc, int page) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM chunk WHERE rowid IN (SELECT id FROM meta WHERE doc=? AND page>=?)", new Object[]{doc, page});
        db.execSQL("DELETE FROM meta WHERE doc=? AND page>=?", new Object[]{doc, page});
    }
}
