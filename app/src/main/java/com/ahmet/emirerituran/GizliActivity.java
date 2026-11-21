package com.ahmet.emirerituran;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.biometrics.BiometricPrompt;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Gizli Hat ekranı. FLAG_SECURE: ekran görüntüsü / ekran kaydı / son uygulamalar önizlemesi engellenir.
 * Arayüz assets/www/gizli.html; yerel köprü "GizliNative".
 */
public class GizliActivity extends Activity {
    private static final int REQ_FILE = 61, REQ_QR_CAM = 62, REQ_QR_PICK = 63, REQ_MIC = 64, REQ_NOTIF = 65;
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile boolean unlocked = false;
    private static volatile long pausedAt = 0;
    private long fileCid = -1; private String afterMic = null;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (BuildConfig.GZ_SECURE) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        }
        web = new WebView(this) {
            // Gizli klavye: klavye uygulaması yazılanları öğrenmesin/kişiselleştirmesin (API 26+)
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo out) {
                android.view.inputmethod.InputConnection ic = super.onCreateInputConnection(out);
                if (out != null && Build.VERSION.SDK_INT >= 26) out.imeOptions |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
                return ic;
            }
        };
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(false); s.setAllowFileAccess(true);
        s.setSaveFormData(false); s.setCacheMode(WebSettings.LOAD_NO_CACHE); s.setBlockNetworkLoads(true);
        web.addJavascriptInterface(new Bridge(), "GizliNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
        });
        web.loadUrl("file:///android_asset/www/gizli.html");
        cleanOpenDir();
    }

    @Override protected void onResume() {
        super.onResume();
        GizliStore st = GizliStore.exists(this) ? GizliStore.get(this) : null;
        int lockSec = GizliStore.prefs(this).getInt("lockSec", 30);
        if (st != null && st.pinSet() && unlocked && pausedAt > 0 && System.currentTimeMillis() - pausedAt > lockSec * 1000L) unlocked = false;
        GizliNet.get(this).ui = (type, data) -> ui.post(() -> js("__gz", type, data.toString()));
        // Süreç sistem tarafından kapatıldıysa ve kullanıcı çevrimiçi bırakmışsa yeniden bağlan
        if (st != null && GizliStore.prefs(this).getBoolean("online", false) && !GizliService.isRunning()) {
            try { GizliService.start(this); } catch (Exception ignored) {}
        }
        js("__gzResume");
    }
    @Override protected void onPause() {
        super.onPause(); pausedAt = System.currentTimeMillis();
        GizliNet n = GizliNet.get(this); n.ui = null;
        if (n.visibleChat >= 0) n.chatOpen(n.visibleChat, false);
        js("__gzPause");
    }
    @Override protected void onDestroy() { if (web != null) { web.clearCache(true); web.destroy(); } super.onDestroy(); }
    @Override public void onBackPressed() { web.evaluateJavascript("window.__gzBack&&__gzBack()", v -> { if (!"true".equals(v)) finish(); }); }

    private void js(String fn, String... args) {
        StringBuilder sb = new StringBuilder("window.").append(fn).append("&&").append(fn).append("(");
        for (int i = 0; i < args.length; i++) { if (i > 0) sb.append(','); sb.append(i == 1 && args[i].startsWith("{") ? args[i] : JSONObject.quote(args[i])); }
        sb.append(")");
        if (web != null) web.evaluateJavascript(sb.toString(), null);
    }
    private void toast(String t) { ui.post(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show()); }

    private GizliStore st() { return GizliStore.get(this); }
    private GizliNet net() { return GizliNet.get(this); }
    private boolean allowed() { return unlocked || !GizliStore.exists(this) || !st().pinSet(); }

    // ---------- köprü ----------
    final class Bridge {
        @JavascriptInterface public String state() {
            JSONObject o = new JSONObject();
            try {
                boolean ex = GizliStore.exists(GizliActivity.this);
                boolean id = ex && st().hasIdentity();
                o.put("hasIdentity", id); o.put("pinSet", ex && st().pinSet()); o.put("locked", !allowed());
                o.put("bio", Build.VERSION.SDK_INT >= 28 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT));
                o.put("online", GizliService.isRunning() || net().running());
                o.put("tor", GizliTor.get(GizliActivity.this).status());
                if (id && allowed()) { byte[] p = st().myPub(); o.put("id", GizliCrypto.idOf(p)); o.put("short", GizliCrypto.shortId(p)); o.put("fp", GizliCrypto.fingerprint(p)); }
                o.put("opus", GizliCall.hasOpus());
                o.put("settings", settingsJ());
                o.put("call", net().call.status());
            } catch (Exception e) { try { o.put("error", e.getMessage()); } catch (Exception ignored) {} }
            return o.toString();
        }
        @JavascriptInterface public String createIdentity() { st().createIdentity(); return state(); }
        @JavascriptInterface public boolean setPin(String pin) {
            if (pin == null || !pin.matches("\\d{4,10}")) return false;
            if (st().pinSet() && !unlocked) return false;
            st().setPin(pin); unlocked = true; return true;
        }
        @JavascriptInterface public String unlock(String pin) {
            boolean ok = st().checkPin(pin == null ? "" : pin);
            if (ok) { unlocked = true; return "{\"ok\":true}"; }
            int f = st().fails();
            if (GizliStore.prefs(GizliActivity.this).getBoolean("wipeOnFail", false) && f >= 10) { ui.post(() -> panicNow()); return "{\"ok\":false,\"wiped\":true}"; }
            return "{\"ok\":false,\"fails\":" + f + "}";
        }
        @JavascriptInterface public void bioUnlock() { ui.post(GizliActivity.this::bio); }
        @JavascriptInterface public void lockNow() { unlocked = false; }
        @JavascriptInterface public void online(boolean on) {
            if (!allowed()) return;
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    ui.post(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
                GizliService.start(GizliActivity.this);
            } else GizliService.stop(GizliActivity.this);
        }
        @JavascriptInterface public String contacts() {
            JSONArray a = new JSONArray(); if (!allowed()) return "[]";
            for (GizliStore.Contact k : st().contacts()) {
                if ("blocked".equals(k.state)) continue;
                GizliStore.Msg m = st().lastMsg(k.id);
                String prev = m == null ? "" : ("f".equals(m.kind) ? "📎 " + m.body.optString("name") : m.body.optString("text"));
                if (prev.length() > 60) prev = prev.substring(0, 60) + "…";
                a.put(GizliNet.j("cid", k.id, "name", k.name(), "alias", k.alias == null ? "" : k.alias, "short", k.shortId(), "state", k.state, "online", net().online(k.id),
                        "unread", k.unread, "last", k.last, "prev", prev, "out", m != null && m.dir == 1, "verified", k.verified, "ttl", k.ttl));
            }
            return a.toString();
        }
        @JavascriptInterface public String addContact(String text, String alias) {
            if (!allowed()) return err("Kilitli");
            byte[] pub = GizliCrypto.parseId(text);
            if (pub == null) return err("Geçersiz kimlik. 0x ile başlayan 70 karakterlik kimliğin tamamını yapıştırın (sağlama hanesi tutmuyor ya da eksik).");
            if (GizliCrypto.eq(pub, st().myPub())) return err("Bu sizin kendi kimliğiniz.");
            GizliStore.Contact ex = st().byPub(pub);
            if (ex != null) {
                if ("blocked".equals(ex.state) || "in".equals(ex.state)) { net().accept(ex.id); return GizliNet.j("ok", true, "cid", ex.id, "note", "Engellenen/bekleyen kişi kabul edildi.").toString(); }
                return GizliNet.j("ok", true, "cid", ex.id, "note", "Bu kişi zaten listenizde.").toString();
            }
            GizliStore.Contact k = st().addContact(pub, alias == null ? null : alias.trim(), "out");
            net().sysMsg(k, "Kişi eklendi. Karşı taraf sizi kabul edene ya da o da sizi ekleyene kadar \"onay bekleniyor\" görünür. İkiniz de aynı anda çevrimiçi olmalısınız.");
            k.pendAccept = false; net().kick(k.id); net().emit("contacts", null);
            return GizliNet.j("ok", true, "cid", k.id).toString();
        }
        @JavascriptInterface public String contactAction(long cid, String action, String arg) {
            if (!allowed()) return err("Kilitli");
            GizliStore.Contact k = st().contact(cid); if (k == null) return err("Kişi bulunamadı");
            switch (action) {
                case "accept": net().accept(cid); break;
                case "block": net().block(cid); break;
                case "remove": net().remove(cid); break;
                case "clear": st().clearChat(cid); net().emit("msgs", GizliNet.j("cid", cid)); break;
                case "rename": k.alias = arg == null ? null : arg.trim(); st().updContact(k); net().emit("contacts", null); break;
                case "verify": k.verified = "1".equals(arg); st().updContact(k); net().sysMsg(k, k.verified ? "✔ Güvenlik numarası doğrulandı olarak işaretlendi." : "Doğrulama işareti kaldırıldı."); break;
                case "ttl": try { net().setTtl(cid, Integer.parseInt(arg)); } catch (Exception e) { return err("Süre geçersiz"); } break;
                case "delmsg": try { st().deleteMsg(Long.parseLong(arg)); net().emit("msgs", GizliNet.j("cid", cid)); } catch (Exception ignored) {} break;
                default: return err("Bilinmeyen işlem");
            }
            return "{\"ok\":true}";
        }
        @JavascriptInterface public String contact(long cid) {
            if (!allowed()) return "{}";
            GizliStore.Contact k = st().contact(cid); if (k == null) return "{}";
            GizliNet n = net();
            return GizliNet.j("cid", k.id, "name", k.name(), "alias", k.alias == null ? "" : k.alias, "id", k.idStr(), "short", k.shortId(), "state", k.state,
                    "online", n.online(k.id), "connecting", n.isConnecting(k.id), "error", n.lastError(k.id), "retry", n.nextTryIn(k.id), "verified", k.verified, "ttl", k.ttl,
                    "safety", GizliCrypto.safetyNumber(st().myPub(), k.pub), "fp", GizliCrypto.fingerprint(k.pub)).toString();
        }
        @JavascriptInterface public void openChat(long cid, boolean visible) { if (allowed()) net().chatOpen(cid, visible); }
        @JavascriptInterface public String messages(long cid, long before) {
            JSONArray a = new JSONArray(); if (!allowed()) return "[]";
            for (GizliStore.Msg m : st().messages(cid, before, 80)) {
                JSONObject o = GizliNet.j("id", m.id, "dir", m.dir, "ts", m.ts, "st", m.st, "kind", m.kind, "ttl", m.ttl, "exp", m.exp);
                try {
                    if ("f".equals(m.kind)) { o.put("name", m.body.optString("name")); o.put("mime", m.body.optString("mime")); o.put("size", m.body.optLong("size")); if (m.body.has("thumb")) o.put("thumb", m.body.optString("thumb")); }
                    else o.put("text", m.body.optString("text"));
                } catch (Exception ignored) {}
                a.put(o);
            }
            return a.toString();
        }
        @JavascriptInterface public long send(long cid, String text) { return allowed() ? net().sendText(cid, text) : -1; }
        @JavascriptInterface public void pickFile(long cid) {
            if (!allowed()) return; fileCid = cid;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            ui.post(() -> startActivityForResult(i, REQ_FILE));
        }
        @JavascriptInterface public String openFile(long id) {
            if (!allowed()) return err("Kilitli");
            GizliStore.Msg m = st().msg(id); if (m == null || !"f".equals(m.kind)) return err("Dosya yok");
            try {
                File src = new File(st().filesDir, m.body.optString("file"));
                String mime = m.body.optString("mime");
                if (mime.startsWith("image/")) {
                    Bitmap bm; try (InputStream in = st().encIn(src)) { BitmapFactory.Options op = new BitmapFactory.Options(); bm = BitmapFactory.decodeStream(in, null, op); }
                    if (bm != null) {
                        Bitmap sc = scale(bm, 1600); ByteArrayOutputStream bo = new ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 85, bo);
                        return GizliNet.j("ok", true, "img", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)).toString();
                    }
                }
                File d = new File(getCacheDir(), "gizli/open"); d.mkdirs();
                File out = new File(d, GizliNet.safeName(m.body.optString("name", "dosya")));
                try (InputStream in = st().encIn(src); OutputStream o = new FileOutputStream(out)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
                Uri u = FileProvider.getUriForFile(GizliActivity.this, "com.ahmet.emirerituran.files", out);
                Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime.isEmpty() ? "*/*" : mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ui.post(() -> { try { startActivity(Intent.createChooser(v, "Dosyayı aç")); } catch (Exception e) { toast("Bu dosyayı açacak uygulama yok."); } });
                ui.postDelayed(GizliActivity.this::cleanOpenDir, 10 * 60 * 1000L);
                return "{\"ok\":true,\"external\":true}";
            } catch (Exception e) { return err("Dosya açılamadı: " + e.getMessage()); }
        }
        @JavascriptInterface public String circuit(long cid) {
            if (!allowed()) return "{}";
            GizliStore.Contact k = st().contact(cid); if (k == null) return "{}";
            JSONObject o = GizliTor.get(GizliActivity.this).circuitFor(k.onion(), false);
            try { o.put("lastRotate", net().lastRotate); o.put("rotateMin", net().rotateMin()); o.put("online", net().online(cid)); } catch (Exception ignored) {}
            return o.toString();
        }
        @JavascriptInterface public void rotate() { if (allowed()) net().rotate(true); }
        @JavascriptInterface public String call(long cid) {
            if (!allowed()) return err("Kilitli");
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { afterMic = "call:" + cid; ui.post(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC)); return "{\"ok\":false,\"perm\":true}"; }
            String e = net().call.dial(cid); return e == null ? "{\"ok\":true}" : err(e);
        }
        @JavascriptInterface public String answer() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { afterMic = "answer"; ui.post(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC)); return "{\"ok\":false,\"perm\":true}"; }
            String e = net().call.answer(); return e == null ? "{\"ok\":true}" : err(e);
        }
        @JavascriptInterface public void reject() { net().call.reject(); }
        @JavascriptInterface public void hangup() { net().call.hangup("user"); }
        @JavascriptInterface public void mute(boolean m) { net().call.setMuted(m); }
        @JavascriptInterface public void speaker(boolean s) { net().call.setSpeaker(s); }
        @JavascriptInterface public String callStatus() { return net().call.status().toString(); }
        @JavascriptInterface public String callStats() { return net().call.stats().toString(); }
        @JavascriptInterface public void copyId() {
            if (!allowed()) return;
            final String id = GizliCrypto.idOf(st().myPub());
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("Gizli Hat kimliği", id);
                if (Build.VERSION.SDK_INT >= 24) { PersistableBundle ex = new PersistableBundle(); ex.putBoolean("android.content.extra.IS_SENSITIVE", true); cd.getDescription().setExtras(ex); }
                cm.setPrimaryClip(cd);
                ui.postDelayed(() -> clearClip(id), 60000);
            });
        }
        @JavascriptInterface public void copyText(String t) {
            ui.post(() -> { ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("Gizli Hat", t)); ui.postDelayed(() -> clearClip(t), 60000); });
        }
        @JavascriptInterface public String clipboard() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            try { ClipData c = cm.getPrimaryClip(); if (c != null && c.getItemCount() > 0) { CharSequence t = c.getItemAt(0).coerceToText(GizliActivity.this); return t == null ? "" : t.toString(); } } catch (Exception ignored) {}
            return "";
        }
        @JavascriptInterface public void shareId() {
            if (!allowed()) return;
            String id = GizliCrypto.idOf(st().myPub());
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Gizli Hat kimliğim (Turan Asistan):\n" + id);
            ui.post(() -> startActivity(Intent.createChooser(s, "Kimliği paylaş")));
        }
        @JavascriptInterface public String qr() {
            if (!allowed()) return "";
            try {
                String id = GizliCrypto.idOf(st().myPub());
                Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class); h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M); h.put(EncodeHintType.MARGIN, 2);
                BitMatrix bm = new QRCodeWriter().encode(id, BarcodeFormat.QR_CODE, 0, 0, h);
                int w = bm.getWidth(), sc = Math.max(1, 600 / w); Bitmap b = Bitmap.createBitmap(w * sc, w * sc, Bitmap.Config.RGB_565);
                for (int y = 0; y < w * sc; y++) for (int x = 0; x < w * sc; x++) b.setPixel(x, y, bm.get(x / sc, y / sc) ? 0xFF000000 : 0xFFFFFFFF);
                ByteArrayOutputStream o = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, o);
                return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public void scanQr(boolean camera) {
            ui.post(() -> {
                try {
                    if (camera) {
                        File d = new File(getCacheDir(), "gizli"); d.mkdirs(); File f = new File(d, "qr.jpg"); f.delete();
                        Uri u = FileProvider.getUriForFile(GizliActivity.this, "com.ahmet.emirerituran.files", f);
                        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, u).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        startActivityForResult(i, REQ_QR_CAM);
                    } else startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_QR_PICK);
                } catch (Exception e) { toast("Kamera açılamadı: " + e.getMessage()); }
            });
        }
        @JavascriptInterface public String settings() { return settingsJ().toString(); }
        @JavascriptInterface public void setSetting(String k, String v) {
            if (!allowed()) return;
            android.content.SharedPreferences.Editor e = GizliStore.prefs(GizliActivity.this).edit();
            switch (k) {
                case "rotateMin": case "lockSec": try { e.putInt(k, Integer.parseInt(v)); } catch (Exception ignored) {} break;
                case "readReceipts": case "bootOnline": case "wipeOnFail": case "bio": e.putBoolean(k, "1".equals(v) || "true".equals(v)); break;
                default: return;
            }
            e.apply();
            if ("rotateMin".equals(k)) net().lastRotate = System.currentTimeMillis();
        }
        @JavascriptInterface public void panic(String confirm) { if ("SİL".equals(confirm) || "SIL".equalsIgnoreCase(confirm)) ui.post(GizliActivity.this::panicNow); }
        @JavascriptInterface public void battery() {
            ui.post(() -> {
                try {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (pm.isIgnoringBatteryOptimizations(getPackageName())) { toast("Pil optimizasyonu zaten kapalı."); return; }
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            });
        }
        @JavascriptInterface public void toast(String t) { GizliActivity.this.toast(t); }
        @JavascriptInterface public void close() { ui.post(GizliActivity.this::finish); }
        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME; }
    }

    private static String err(String m) { return GizliNet.j("ok", false, "error", m).toString(); }

    private JSONObject settingsJ() {
        android.content.SharedPreferences p = GizliStore.prefs(this);
        boolean batt = false; try { batt = ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()); } catch (Exception ignored) {}
        return GizliNet.j("rotateMin", p.getInt("rotateMin", 10), "lockSec", p.getInt("lockSec", 30), "readReceipts", p.getBoolean("readReceipts", true),
                "bootOnline", p.getBoolean("bootOnline", false), "wipeOnFail", p.getBoolean("wipeOnFail", false), "bio", p.getBoolean("bio", false), "battery", batt);
    }

    private void clearClip(String expected) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData c = cm.getPrimaryClip();
            if (c != null && c.getItemCount() > 0 && expected.contentEquals(c.getItemAt(0).coerceToText(this))) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip(); else cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } catch (Exception ignored) {}
    }

    private void panicNow() {
        GizliService.stop(this);
        GizliNet.reset();
        GizliStore.wipeAll(this);
        unlocked = false;
        if (web != null) web.clearCache(true);
        cleanOpenDir();
        toast("Gizli Hat verileri kalıcı olarak silindi.");
        js("__gzWiped");
    }

    private void cleanOpenDir() { GizliStore.deleteTree(new File(getCacheDir(), "gizli/open")); }

    private void bio() {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            BiometricPrompt p = new BiometricPrompt.Builder(this).setTitle("Gizli Hat").setSubtitle("Kilidi açmak için doğrulayın")
                    .setNegativeButton("PIN kullan", getMainExecutor(), (d, w) -> {}).build();
            p.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { unlocked = true; js("__gzUnlocked"); }
            });
        } catch (Exception e) { toast("Biyometrik doğrulama kullanılamıyor."); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_MIC) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED; String a = afterMic; afterMic = null;
            if (!ok) { toast("Sesli arama için mikrofon izni gerekiyor komutanım."); return; }
            if (a != null && a.startsWith("call:")) { String e = net().call.dial(Long.parseLong(a.substring(5))); if (e != null) toast(e); }
            else if ("answer".equals(a)) net().call.answer();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        if (req == REQ_FILE && data != null && data.getData() != null) { final Uri u = data.getData(); final long cid = fileCid; new Thread(() -> prepareFile(cid, u), "gz-file").start(); }
        if (req == REQ_QR_CAM) new Thread(() -> decodeQr(Uri.fromFile(new File(getCacheDir(), "gizli/qr.jpg"))), "gz-qr").start();
        if (req == REQ_QR_PICK && data != null && data.getData() != null) { final Uri u = data.getData(); new Thread(() -> decodeQr(u), "gz-qr").start(); }
    }

    private void decodeQr(Uri u) {
        String found = null;
        try {
            Bitmap b; try (InputStream in = getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in); }
            if (b != null) {
                Bitmap s = scale(b, 1400);
                for (int rot = 0; rot < 4 && found == null; rot++) {
                    Bitmap r = s; if (rot > 0) { Matrix m = new Matrix(); m.postRotate(90 * rot); r = Bitmap.createBitmap(s, 0, 0, s.getWidth(), s.getHeight(), m, true); }
                    int[] px = new int[r.getWidth() * r.getHeight()]; r.getPixels(px, 0, r.getWidth(), 0, 0, r.getWidth(), r.getHeight());
                    Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class); h.put(DecodeHintType.TRY_HARDER, Boolean.TRUE); h.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
                    try { found = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(r.getWidth(), r.getHeight(), px))), h).getText(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        new File(getCacheDir(), "gizli/qr.jpg").delete();
        final String f = found;
        ui.post(() -> { if (f == null) toast("Görüntüde QR kod bulunamadı."); else js("__gzScanned", f); });
    }

    /** Fotoğraflar yeniden kodlanır: EXIF (konum, cihaz, tarih) dahil tüm üst veriler atılır. */
    private void prepareFile(long cid, Uri u) {
        try {
            String name = "dosya", mime = getContentResolver().getType(u); long size = -1;
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) { name = c.getString(0); if (!c.isNull(1)) size = c.getLong(1); }
            }
            if (mime == null) mime = "application/octet-stream";
            if (size > GizliNet.MAX_FILE) { toast("Dosya çok büyük (en fazla 50 MB). Tor üzerinden büyük dosyalar çok yavaş gider."); return; }
            File out = new File(st().filesDir, "out-" + GizliCrypto.hex(GizliCrypto.rand(8)) + ".bin");
            String thumb = null; long written = 0; boolean stripped = false;
            if (mime.startsWith("image/") && !mime.contains("gif")) {
                int orient = ExifInterface.ORIENTATION_NORMAL;
                try (InputStream in = getContentResolver().openInputStream(u)) { orient = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); } catch (Exception ignored) {}
                Bitmap bm; try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = false; bm = BitmapFactory.decodeStream(in, null, o); }
                if (bm != null) {
                    bm = scale(bm, 2560);
                    int deg = orient == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orient == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : orient == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                    if (deg != 0) { Matrix m = new Matrix(); m.postRotate(deg); bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true); }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 85, bo); byte[] jpg = bo.toByteArray();
                    try (OutputStream o = st().encOut(out)) { o.write(jpg); } written = jpg.length;
                    ByteArrayOutputStream tb = new ByteArrayOutputStream(); scale(bm, 320).compress(Bitmap.CompressFormat.JPEG, 60, tb);
                    thumb = "data:image/jpeg;base64," + Base64.encodeToString(tb.toByteArray(), Base64.NO_WRAP);
                    name = "foto_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".jpg"; mime = "image/jpeg"; stripped = true;
                }
            }
            if (!stripped) {
                try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = st().encOut(out)) {
                    byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) { written += n; if (written > GizliNet.MAX_FILE) throw new java.io.IOException("50 MB sınırı aşıldı"); o.write(b, 0, n); }
                }
            }
            net().sendFile(cid, GizliNet.safeName(name), mime, out, written, thumb);
            final boolean s2 = stripped;
            ui.post(() -> js("__gzFileQueued", s2 ? "Fotoğraf yeniden kodlandı; konum/cihaz bilgisi (EXIF) silindi." : "Dosya olduğu gibi gönderilecek. Belge içi üst veriler (yazar, şirket vb.) temizlenmez."));
        } catch (Exception e) { toast("Dosya hazırlanamadı: " + e.getMessage()); }
    }

    static Bitmap scale(Bitmap b, int max) {
        int w = b.getWidth(), h = b.getHeight(); if (Math.max(w, h) <= max) return b;
        float f = (float) max / Math.max(w, h); return Bitmap.createScaledBitmap(b, Math.round(w * f), Math.round(h * f), true);
    }
}
