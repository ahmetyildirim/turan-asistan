package com.ahmet.emirerituran;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Ekler: uygulamaya özel depolama, metin çıkarma (OCR/PDF/DOCX/TXT) ve isteğe bağlı yapay zekâ analizi. */
public final class Files {
    static final String AUTH = "com.ahmet.emirerituran.files";
    private Files() {}

    static File dir(Context c) { File d = new File(c.getFilesDir(), "att"); d.mkdirs(); return d; }
    static String safeId(String id) { return id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", ""); }
    static File file(Context c, String id) { return new File(dir(c), safeId(id)); }
    static String newId() { return "a" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 46656), 36); }

    static String guessMime(String name, String given) {
        if (given != null && !given.isEmpty() && !"application/octet-stream".equals(given)) return given;
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : (given != null && !given.isEmpty() ? given : "application/octet-stream");
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Bir içerik adresini (galeri, dosya, paylaşım) uygulama deposuna kopyalar, bilgi döndürür. */
    static JSONObject importUri(Context c, Uri u) throws Exception {
        ContentResolver cr = c.getContentResolver();
        String name = null;
        if ("content".equals(u.getScheme())) {
            try (Cursor cur = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) name = cur.getString(0);
            } catch (Exception ignored) {}
        }
        if (name == null) name = u.getLastPathSegment() != null ? u.getLastPathSegment() : "dosya";
        String id = newId();
        File f = file(c, id);
        try (InputStream in = cr.openInputStream(u); OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new IOException("Dosya açılamadı");
            copy(in, out);
        }
        return meta(id, name, guessMime(name, cr.getType(u)), f.length());
    }

    static JSONObject importFile(Context c, File src, String name, String mime) throws Exception {
        String id = newId();
        File f = file(c, id);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(f)) { copy(in, out); }
        src.delete();
        return meta(id, name, mime, f.length());
    }

    static JSONObject importText(Context c, String text, String name) throws Exception {
        String id = newId();
        File f = file(c, id);
        try (OutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        return meta(id, name, "text/plain", f.length());
    }

    static JSONObject meta(String id, String name, String mime, long size) throws Exception {
        JSONObject m = new JSONObject();
        m.put("id", id); m.put("name", name); m.put("mime", mime); m.put("size", size); m.put("added", Store.isoNow());
        return m;
    }

    /** Dışarı paylaşmak/açmak için gerçek dosya adıyla geçici kopya ve içerik adresi. */
    static Uri shareUri(Context c, String id, String name) throws IOException {
        File d = new File(c.getCacheDir(), "share/" + safeId(id)); d.mkdirs();
        String clean = (name == null || name.isEmpty()) ? "dosya" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File out = new File(d, clean);
        try (InputStream in = new FileInputStream(file(c, id)); OutputStream o = new FileOutputStream(out)) { copy(in, o); }
        return FileProvider.getUriForFile(c, AUTH, out);
    }

    // ---------- Metin çıkarma ----------
    static boolean isImage(String mime) { return mime != null && mime.startsWith("image/"); }

    /** {text, method} döndürür. Arka plan iş parçacığında çağrılmalıdır. */
    static JSONObject extract(Context c, String id, String mime, String name) throws Exception {
        File f = file(c, id);
        if (!f.exists()) throw new IOException("Dosya bulunamadı");
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        String text; String method;
        if (isImage(mime)) {
            text = ocr(loadBitmap(f, 2600)); method = "Görüntüden metin tanıma (OCR, cihaz üzerinde)";
        } else if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) {
            JSONObject r = pdf(c, f); text = r.getString("text"); method = r.getString("method");
        } else if (lower.endsWith(".docx") || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) {
            text = docx(f); method = "Word belgesi (DOCX)";
        } else if ((mime != null && (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("csv")))
                || lower.matches(".*\\.(txt|md|csv|json|xml|log)$")) {
            text = readText(f); method = "Metin dosyası";
        } else {
            throw new IOException("Bu dosya türünden metin çıkarılamıyor (" + mime + "). Desteklenenler: fotoğraf, PDF, DOCX, TXT.");
        }
        JSONObject o = new JSONObject();
        o.put("text", text == null ? "" : text.trim()); o.put("method", method);
        return o;
    }

    static Bitmap loadBitmap(File f, int max) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1; while (Math.max(o.outWidth, o.outHeight) / s > max) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o2);
        if (b == null) throw new IOException("Görüntü okunamadı");
        int rot = 0;
        try {
            int or = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {}
        if (rot != 0) { Matrix m = new Matrix(); m.postRotate(rot); b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); }
        return b;
    }

    static String ocr(Bitmap b) throws Exception {
        TextRecognizer r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            Text t = Tasks.await(r.process(InputImage.fromBitmap(b, 0)));
            StringBuilder sb = new StringBuilder();
            for (Text.TextBlock bl : t.getTextBlocks()) { sb.append(bl.getText()).append("\n\n"); }
            return sb.toString().trim();
        } finally { r.close(); }
    }

    static JSONObject pdf(Context c, File f) throws Exception {
        PDFBoxResourceLoader.init(c.getApplicationContext());
        String text = ""; int pages = 0;
        try (PDDocument doc = PDDocument.load(f)) {
            pages = doc.getNumberOfPages();
            PDFTextStripper st = new PDFTextStripper();
            st.setEndPage(Math.min(pages, 50));
            text = st.getText(doc);
        } catch (Exception e) { text = ""; }
        JSONObject o = new JSONObject();
        if (text.trim().length() >= 20 * Math.max(1, Math.min(pages, 3))) {
            o.put("text", text); o.put("method", "PDF metin katmanı (" + pages + " sayfa)");
            return o;
        }
        // Taranmış PDF: sayfaları görüntüye çevirip OCR uygula (ilk 5 sayfa)
        StringBuilder sb = new StringBuilder();
        int done = 0;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pr = new PdfRenderer(pfd)) {
            int n = Math.min(pr.getPageCount(), 5);
            for (int i = 0; i < n; i++) {
                try (PdfRenderer.Page p = pr.openPage(i)) {
                    float scale = 1700f / p.getWidth();
                    Bitmap bm = Bitmap.createBitmap(1700, Math.max(1, (int) (p.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                    new Canvas(bm).drawColor(Color.WHITE);
                    p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    sb.append(ocr(bm)).append("\n\n");
                    bm.recycle(); done++;
                }
            }
        }
        o.put("text", sb.toString()); o.put("method", "Taranmış PDF, sayfa görüntüsünden OCR (" + done + " sayfa)");
        return o;
    }

    static String docx(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            ZipEntry e = z.getEntry("word/document.xml");
            if (e == null) throw new IOException("DOCX içeriği bulunamadı");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = z.getInputStream(e)) { copy(in, bo); }
            String x = bo.toString("UTF-8");
            x = x.replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", "\t").replaceAll("<w:br/>", "\n").replaceAll("<[^>]+>", "");
            return x.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
        }
    }

    static String readText(File f) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) { copy(in, bo); }
        byte[] b = bo.toByteArray();
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.indexOf('\uFFFD') >= 0) s = new String(b, Charset.forName("windows-1254"));
        return s.length() > 200000 ? s.substring(0, 200000) : s;
    }

    // ---------- İsteğe bağlı yapay zekâ (OpenAI uyumlu sohbet API'si) ----------
    static final String AI_PROMPT = "Sen askeri düzende çalışan bir kurmay asistanısın. Kullanıcının verdiği belge, görsel veya metinden "
            + "Türkçe kısa bir özet (en fazla 3 cümle) ve takip edilmesi gereken iş maddelerini çıkar. Her madde kısa ve takip edilebilir "
            + "bir iş ifadesi olsun (ör. 'Teklifin Ayşe Hanım'a gönderilmesi – 20 Kasım'). Tarihleri, kişi ve kurum adlarını koru. "
            + "Yalnızca şu biçimde geçerli JSON döndür: {\"ozet\": \"...\", \"maddeler\": [\"...\", \"...\"]}";

    /** Basit HTTPS GET (Araştır ekranı için). */
    static JSONObject httpGet(String url) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("Yalnızca https adresleri");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000); con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", "TuranAsistan/1.2 (Android; kisisel asistan)");
        con.setRequestProperty("Accept", "application/json");
        int code = con.getResponseCode();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream()) { if (in != null) copy(in, bo); }
        JSONObject o = new JSONObject();
        o.put("ok", code < 400); o.put("status", code); o.put("body", bo.toString("UTF-8"));
        return o;
    }

    /** Araştır ekranı: kaynak özetleriyle sohbet tarzı cevap (anahtar varsa). */
    static String aiChat(Context c, String question, String context) throws Exception {
        return aiChatSys(c, "Sen Turan Asistan'sın; kullanıcıya 'Komutanım' diye hitap eden, askeri nezaketle konuşan bir emir erisin. "
                + "Soruya kısa, doğru ve Türkçe cevap ver (en fazla 8 cümle). Verilen kaynak özetlerinden yararlan; emin olmadığın yerde bunu belirt. "
                + "Cevabı 'Arz ederim.' ile bitir.",
                "Soru: " + question + (context == null || context.isEmpty() ? "" : "\n\nKaynak özetleri:\n" + context));
    }

    /** Genel sohbet çağrısı (sistem + kullanıcı mesajı). */
    static String aiChatSys(Context c, String system, String user) throws Exception {
        android.content.SharedPreferences p = Store.prefs(c);
        String url = p.getString("ai_url", "").trim(), model = p.getString("ai_model", "").trim(), key = p.getString("ai_key", "").trim();
        if (key.isEmpty()) throw new IOException("Yapay zekâ anahtarı girilmemiş");
        if (url.isEmpty()) url = "https://api.openai.com/v1";
        if (model.isEmpty()) model = "gpt-4o-mini";
        String endpoint = url.endsWith("/chat/completions") ? url : url.replaceAll("/+$", "") + "/chat/completions";
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        JSONObject body = new JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.3);
        HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
        con.setConnectTimeout(20000); con.setReadTimeout(90000);
        con.setRequestMethod("POST"); con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = con.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = con.getResponseCode();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream()) { if (in != null) copy(in, bo); }
        String resp = bo.toString("UTF-8");
        if (code >= 400) throw new IOException("Servis hatası " + code + ": " + (resp.length() > 300 ? resp.substring(0, 300) : resp));
        return new JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    static JSONObject ai(Context c, String text, String attId, String mime) throws Exception {
        android.content.SharedPreferences p = Store.prefs(c);
        String url = p.getString("ai_url", "").trim(), model = p.getString("ai_model", "").trim(), key = p.getString("ai_key", "").trim();
        if (key.isEmpty()) throw new IOException("Yapay zekâ anahtarı girilmemiş");
        if (url.isEmpty()) url = "https://api.openai.com/v1";
        if (model.isEmpty()) model = "gpt-4o-mini";
        String endpoint = url.endsWith("/chat/completions") ? url : url.replaceAll("/+$", "") + "/chat/completions";
        if (text != null && text.length() > 12000) text = text.substring(0, 12000);

        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", AI_PROMPT));
        JSONObject user = new JSONObject().put("role", "user");
        String intro = "Aşağıdaki içeriği analiz et." + (text != null && !text.isEmpty() ? "\n\nÇıkarılan metin:\n" + text : "");
        if (attId != null && !attId.isEmpty() && isImage(mime)) {
            Bitmap b = loadBitmap(file(c, attId), 1600);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 82, bo);
            String data = "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", intro));
            parts.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", data)));
            user.put("content", parts);
        } else {
            user.put("content", intro);
        }
        msgs.put(user);
        JSONObject body = new JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.2);

        HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
        con.setConnectTimeout(20000); con.setReadTimeout(90000);
        con.setRequestMethod("POST"); con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = con.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = con.getResponseCode();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream()) { if (in != null) copy(in, bo); }
        String resp = bo.toString("UTF-8");
        if (code >= 400) throw new IOException("Servis hatası " + code + ": " + (resp.length() > 300 ? resp.substring(0, 300) : resp));
        String content = new JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        int a = content.indexOf('{'), z = content.lastIndexOf('}');
        if (a < 0 || z <= a) throw new IOException("Yanıt anlaşılamadı");
        JSONObject j = new JSONObject(content.substring(a, z + 1));
        JSONObject out = new JSONObject();
        out.put("summary", j.optString("ozet", j.optString("summary", "")));
        JSONArray items = j.optJSONArray("maddeler"); if (items == null) items = j.optJSONArray("items");
        out.put("items", items == null ? new JSONArray() : items);
        return out;
    }
}
