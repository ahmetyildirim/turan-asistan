package com.ahmet.emirerituran;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Google Drive üzerinden uygulama içi güncelleme.
 * Drive'daki turan-guncelleme.json dosyası okunur; daha yüksek versionCode varsa onay istenir,
 * APK indirilir, paket adı + sürüm + imza sertifikası doğrulanır ve sistem yükleyicisi açılır.
 */
public class Updater {
    static final String MANIFEST_URL = BuildConfig.UPDATE_MANIFEST_URL;
    static final long DAY = 24L * 3600 * 1000;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile boolean busy = false;
    private static File pendingApk = null;

    static int currentCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    static String currentName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    static JSONObject info(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("versionName", currentName(c)).put("versionCode", currentCode(c))
             .put("lastCheck", Store.prefs(c).getLong("updLastCheck", 0))
             .put("lastResult", Store.prefs(c).getInt("updResultFor", -1) == currentCode(c) ? Store.prefs(c).getString("updLastResult", "") : "");
        } catch (Exception ignored) {}
        return o;
    }

    /** Açılışta sessiz kontrol: günde en fazla bir kez; yeni sürüm yoksa hiçbir şey göstermez. */
    /** Kurulmuş (veya eski) güncelleme dosyalarını sil. */
    static void cleanup(Context c) {
        File[] fs = new File(c.getCacheDir(), "update").listFiles();
        if (fs == null) return;
        int cur = currentCode(c);
        for (File f : fs) {
            try { int vc = Integer.parseInt(f.getName().replaceAll("\\D", "")); if (vc <= cur) f.delete(); } catch (Exception e) { f.delete(); }
        }
    }

    static void autoCheck(MainActivity a) {
        if (!Store.prefs(a).getBoolean("onboarded", false)) return; // ilk kurulum diyaloglarıyla çakışmasın
        long last = Store.prefs(a).getLong("updLastCheck", 0);
        if (System.currentTimeMillis() - last < DAY) return;
        check(a, false);
    }

    static void check(final MainActivity a, final boolean manual) {
        if (busy) { if (manual) toast(a, "Güncelleme işlemi zaten sürüyor komutanım."); return; }
        busy = true;
        if (manual) a.js("window.__turanUpdate && window.__turanUpdate({state:'checking'})");
        new Thread(() -> {
            JSONObject m = null; String err = null;
            try { m = fetchManifest(); } catch (Exception e) { err = e.getMessage(); }
            final JSONObject man = m; final String error = err;
            Store.prefs(a).edit().putLong("updLastCheck", System.currentTimeMillis()).putInt("updResultFor", currentCode(a)).apply();
            UI.post(() -> {
                busy = false;
                if (a.isFinishing()) return;
                if (man == null) {
                    Store.prefs(a).edit().putString("updLastResult", "Kontrol edilemedi").apply();
                    a.js("window.__turanUpdate && window.__turanUpdate({state:'error', error:" + JSONObject.quote(String.valueOf(error)) + "})");
                    if (manual) new AlertDialog.Builder(a).setTitle("Turan Asistan – Güncelleme")
                            .setMessage("Komutanım, güncelleme bilgisine ulaşılamadı.\n\n" + error + "\n\nİnternet bağlantınızı kontrol edip tekrar deneyin. Arz ederim.")
                            .setPositiveButton("Tamam", null).show();
                    return;
                }
                int vc = man.optInt("versionCode", 0), cur = currentCode(a);
                String vn = man.optString("versionName", String.valueOf(vc));
                if (vc <= cur || man.optString("apkUrl", "").isEmpty()) {
                    Store.prefs(a).edit().putString("updLastResult", "Güncel (" + currentName(a) + ")").apply();
                    a.js("window.__turanUpdate && window.__turanUpdate({state:'none'})");
                    if (manual) toast(a, "Komutanım, uygulama güncel (sürüm " + currentName(a) + "). Arz ederim.");
                    return;
                }
                Store.prefs(a).edit().putString("updLastResult", "Yeni sürüm var: " + vn).apply();
                a.js("window.__turanUpdate && window.__turanUpdate({state:'available', versionName:" + JSONObject.quote(vn) + "})");
                if (!manual && Store.prefs(a).getInt("updSkipCode", 0) == vc
                        && System.currentTimeMillis() - Store.prefs(a).getLong("updSkipAt", 0) < 3 * DAY) return; // "Daha sonra" dendiyse 3 gün sessiz
                askUpdate(a, man);
            });
        }).start();
    }

    static JSONObject fetchManifest() throws Exception {
        if (MANIFEST_URL == null || MANIFEST_URL.isEmpty()) throw new IOException("Güncelleme adresi tanımlı değil");
        HttpURLConnection con = open(MANIFEST_URL);
        int code = con.getResponseCode();
        String type = String.valueOf(con.getContentType());
        byte[] body = readAll(con.getInputStream(), 256 * 1024);
        con.disconnect();
        String s = new String(body, "UTF-8").trim();
        if (code >= 400) throw new IOException("Sunucu hatası " + code);
        if (type.contains("text/html") || s.startsWith("<"))
            throw new IOException("Drive dosyası herkese açık değil (giriş sayfası döndü). Drive'da “Turan Asistan Güncelleme” klasörünün paylaşımı “Bağlantıya sahip olan herkes” olmalı.");
        return new JSONObject(s);
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(20000); con.setReadTimeout(60000);
        con.setRequestProperty("User-Agent", "TuranAsistan-Updater");
        con.setRequestProperty("Cache-Control", "no-cache");
        return con;
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > max) throw new IOException("Yanıt çok büyük"); }
        in.close();
        return bo.toByteArray();
    }

    static void askUpdate(final MainActivity a, final JSONObject man) {
        int vc = man.optInt("versionCode");
        String vn = man.optString("versionName", String.valueOf(vc));
        long size = man.optLong("size", 0);
        String notes = man.optString("notes", "").trim();
        String msg = "Emir eri Turan, tekmil veriyorum. Komutanım, uygulamanın yeni sürümü hazır.\n\n"
                + "Mevcut sürüm: " + currentName(a) + "\nYeni sürüm: " + vn
                + (size > 0 ? String.format(java.util.Locale.US, " (%.1f MB)", size / 1048576.0) : "")
                + (notes.isEmpty() ? "" : "\n\nYenilikler:\n" + notes)
                + "\n\nGüncelleme listenizi ve kayıtlarınızı silmez. Onay verirseniz indirip kurulum ekranını açacağım. Arz ederim.";
        new AlertDialog.Builder(a).setTitle("Turan Asistan – Yeni sürüm " + vn).setMessage(msg)
                .setPositiveButton("Güncelle", (d, w) -> download(a, man))
                .setNegativeButton("Daha sonra", (d, w) -> Store.prefs(a).edit().putInt("updSkipCode", vc).putLong("updSkipAt", System.currentTimeMillis()).apply())
                .setCancelable(true).show();
    }

    static void download(final MainActivity a, final JSONObject man) {
        final int vc = man.optInt("versionCode");
        final long expected = man.optLong("size", 0);
        final String sha = man.optString("sha256", "").trim().toLowerCase();
        final String url = man.optString("apkUrl");
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        final TextView tv = new TextView(a);
        tv.setText("İndiriliyor… %0");
        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(tv); box.addView(bar);
        final boolean[] cancelled = {false};
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Turan Asistan – Güncelleme indiriliyor").setView(box)
                .setNegativeButton("Vazgeç", (d, w) -> cancelled[0] = true).setCancelable(false).show();
        busy = true;
        new Thread(() -> {
            File dir = new File(a.getCacheDir(), "update");
            File out = new File(dir, "turan-asistan-" + vc + ".apk");
            String err = null;
            try {
                dir.mkdirs();
                for (File f : dir.listFiles()) f.delete();
                HttpURLConnection con = open(url);
                int code = con.getResponseCode();
                if (code >= 400) throw new IOException("Sunucu hatası " + code);
                String type = String.valueOf(con.getContentType());
                if (type.contains("text/html")) throw new IOException("Drive APK yerine bir web sayfası döndürdü (dosya herkese açık değil ya da bağlantı hatalı).");
                long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : expected;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long done = 0; int lastPct = -1;
                try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024]; int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled[0]) throw new IOException("İptal edildi");
                        os.write(buf, 0, n); md.update(buf, 0, n); done += n;
                        final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        final long d2 = done;
                        if (pct != lastPct) {
                            lastPct = pct;
                            UI.post(() -> { bar.setProgress(pct); tv.setText(String.format(java.util.Locale.US, "İndiriliyor… %%%d  (%.1f MB)", pct, d2 / 1048576.0)); });
                        }
                    }
                }
                con.disconnect();
                if (expected > 0 && done != expected) throw new IOException("Dosya boyutu tutmuyor (" + done + " / " + expected + ")");
                if (!sha.isEmpty() && !sha.equals(hex(md.digest()))) throw new IOException("Dosya doğrulaması (SHA-256) başarısız");
                verifyApk(a, out, vc);
            } catch (Exception e) {
                err = e.getMessage();
                out.delete();
            }
            final String error = err;
            UI.post(() -> {
                busy = false;
                try { dlg.dismiss(); } catch (Exception ignored) {}
                if (cancelled[0]) { toast(a, "Güncelleme iptal edildi."); return; }
                if (error != null) {
                    new AlertDialog.Builder(a).setTitle("Turan Asistan – Güncelleme").setMessage("Komutanım, güncelleme indirilemedi:\n\n" + error + "\n\nDaha sonra tekrar deneyebilirsiniz. Arz ederim.")
                            .setPositiveButton("Tamam", null).show();
                    return;
                }
                install(a, out);
            });
        }).start();
    }

    static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }

    /** İndirilen APK aynı paket, beklenen sürüm ve AYNI imza sertifikası mı? (Veriler korunur, sahte dosya kurulmaz.) */
    @SuppressWarnings("deprecation")
    static void verifyApk(Context c, File f, int vc) throws Exception {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo apk = pm.getPackageArchiveInfo(f.getAbsolutePath(), flags);
        if (apk == null) throw new IOException("İndirilen dosya geçerli bir APK değil");
        if (!c.getPackageName().equals(apk.packageName)) throw new IOException("APK başka bir uygulamaya ait");
        int code = Build.VERSION.SDK_INT >= 28 ? (int) apk.getLongVersionCode() : apk.versionCode;
        if (code < vc || code <= currentCode(c)) throw new IOException("APK sürümü beklenenden eski (" + code + ")");
        PackageInfo me = pm.getPackageInfo(c.getPackageName(), flags);
        if (!Arrays.equals(certs(me), certs(apk))) throw new IOException("APK imzası bu uygulamanınkiyle aynı değil; güvenlik için kurulmadı");
    }

    @SuppressWarnings("deprecation")
    static byte[] certs(PackageInfo pi) {
        Signature[] s;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) s = pi.signingInfo.getApkContentsSigners();
        else s = pi.signatures;
        if (s == null || s.length == 0) return new byte[0];
        return s[0].toByteArray();
    }

    static void install(final MainActivity a, final File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            new AlertDialog.Builder(a).setTitle("Turan Asistan – Kurulum izni")
                    .setMessage("Komutanım, güncellemeyi kurabilmem için Android'in “Bilinmeyen uygulamaları yükle” iznini Turan Asistan'a bir kez vermeniz gerekiyor.\n\n"
                            + "“Ayarları aç”a basın, açılan ekranda “Bu kaynaktan izin ver” anahtarını açın ve geri dönün; kurulum kendiliğinden devam edecek. Arz ederim.")
                    .setPositiveButton("Ayarları aç", (d, w) -> {
                        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); }
                        catch (Exception e) { a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
                    })
                    .setNegativeButton("Vazgeç", (d, w) -> pendingApk = null).show();
            return;
        }
        pendingApk = null;
        Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(i); toast(a, "Kurulum ekranında “Güncelle”ye basın komutanım."); }
        catch (Exception e) { toast(a, "Kurulum ekranı açılamadı: " + e.getMessage()); }
    }

    /** İzin ekranından dönüldüğünde bekleyen kurulum varsa sürdür. */
    static void onResume(MainActivity a) {
        if (pendingApk != null && pendingApk.exists() && (Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls())) install(a, pendingApk);
    }

    static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }
}
