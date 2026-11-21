package com.ahmet.emirerituran;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 10, REQ_VOICE = 11, REQ_EXPORT = 12, REQ_IMPORT = 13, REQ_PICK = 14, REQ_CAMERA = 15, REQ_CMD = 16, REQ_ARCH = 17, REQ_PERM = 18, REQ_LISTEN = 19;
    private long archTarget = 0;
    private String listenTag = "";
    private String permAsking = null;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<String> pendingJs = new ArrayList<>();
    private String pickTarget = "new";
    private String cameraPath = null;
    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingExport = null;
    private String pendingView = null;
    private boolean pageReady = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("pickTarget", pickTarget);
        out.putString("cameraPath", cameraPath);
        out.putLong("archTarget", archTarget);
        out.putString("listenTag", listenTag);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) { pickTarget = b.getString("pickTarget", "new"); cameraPath = b.getString("cameraPath"); archTarget = b.getLong("archTarget", 0); listenTag = b.getString("listenTag", ""); }
        current = new WeakReference<>(this);
        Store.ensureChannel(this);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                android.util.Log.i("TuranJS", m.message() + " @" + m.lineNumber());
                return true;
            }
            @Override public boolean onJsAlert(WebView v, String url, String msg, final android.webkit.JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Turan Asistan").setMessage(msg)
                        .setPositiveButton("Tamam", (d, w) -> r.confirm()).setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, final android.webkit.JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Turan Asistan").setMessage(msg)
                        .setPositiveButton("Evet", (d, w) -> r.confirm()).setNegativeButton("Hayır", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("file:///android_asset/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                if (pendingView != null) { route(pendingView); pendingView = null; }
                ui.postDelayed(() -> Updater.autoCheck(MainActivity.this), 4000);
                for (String code : pendingJs) web.evaluateJavascript(code, null);
                pendingJs.clear();
            }
        });
        web.addJavascriptInterface(new Bridge(), "TuranNative");
        if (b == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) pendingView = viewOf(getIntent());
        web.loadUrl("file:///android_asset/www/index.html");
        handleShare(getIntent());
        Store.afterChange(this);
        new Thread(() -> Updater.cleanup(getApplicationContext())).start();
        if (!Store.prefs(this).getBoolean("onboarded", false)) ui.postDelayed(this::onboardStart, 600);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String v = viewOf(intent);
        if (v != null) { if (pageReady) route(v); else pendingView = v; }
        handleShare(intent);
    }

    /** Kısayol, widget, kutucuk ve asistan (ASSIST / VOICE_COMMAND) girişlerini görünüme çevirir. */
    private String viewOf(Intent i) {
        if (i == null) return null;
        String a = i.getAction();
        if (Intent.ACTION_ASSIST.equals(a) || Intent.ACTION_VOICE_COMMAND.equals(a)) { i.setAction(Intent.ACTION_MAIN); return "voice"; }
        String v = i.getStringExtra("view");
        if (v != null) i.removeExtra("view");
        return v;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (pageReady) js("window.__turanTheme && window.__turanTheme(" + isNight() + ")");
    }

    boolean isNight() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.onResume(this);
        current = new WeakReference<>(this);
        Store.afterChange(this);
        if (pageReady) js("window.__turanReload && window.__turanReload()");
        if (pageReady) js("window.__turanPermRefresh && window.__turanPermRefresh()");
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        js("(function(){var m=document.querySelector('.modal:not(.hidden)');if(m){m.classList.add('hidden');return;}"
                + "var a=document.querySelector('.view.active');if(a&&a.id!=='view-open'){document.querySelector('.tabs button[data-view=open]').click();return;}"
                + "TuranNative.exit();})()");
    }

    private void route(String v) {
        if (v.matches("[a-z]+")) js("window.__turanRoute && window.__turanRoute('" + v + "')");
    }

    void js(final String code) { ui.post(() -> web.evaluateJavascript(code, null)); }

    /** Sayfa hazır değilse kuyruğa alır. */
    void jsReady(final String code) {
        ui.post(() -> { if (pageReady) web.evaluateJavascript(code, null); else pendingJs.add(code); });
    }

    // ---------- Paylaş > Turan Asistan ----------
    private void handleShare(Intent in) {
        if (in == null) return;
        String act = in.getAction();
        if (!Intent.ACTION_SEND.equals(act) && !Intent.ACTION_SEND_MULTIPLE.equals(act)) return;
        final ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(act)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        final String text = t == null ? "" : t.toString();
        final String subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
        in.setAction(Intent.ACTION_MAIN); // tekrar işlenmesin
        io.execute(() -> {
            JSONArray files = new JSONArray(); StringBuilder err = new StringBuilder();
            for (Uri u : uris) {
                try { files.put(Files.importUri(this, u)); } catch (Exception e) { android.util.Log.w("Turan", "share import", e); err.append(e.getMessage()).append(' '); }
            }
            android.util.Log.i("Turan", "shared files=" + files.length() + " text=" + text.length());
            try {
                JSONObject o = new JSONObject().put("files", files).put("text", text).put("subject", subject == null ? "" : subject).put("error", err.toString());
                jsReady("window.__turanShared && window.__turanShared(" + o + ")");
            } catch (Exception ignored) {}
        });
    }

    private void deliverFiles(final String target, final JSONArray files, final String err) {
        js("window.__turanFiles && window.__turanFiles(" + JSONObject.quote(target) + "," + files + "," + JSONObject.quote(err) + ")");
    }

    static void notifyArchive() {
        MainActivity a = current.get();
        if (a != null && a.pageReady) a.js("window.__turanArchiveChanged && window.__turanArchiveChanged()");
    }

    static void notifyStateChanged() {
        MainActivity a = current.get();
        if (a != null && a.pageReady) a.js("window.__turanReload && window.__turanReload()");
    }

    // ---------- İlk açılış izin akışı ----------
    private void onboardStart() {
        new AlertDialog.Builder(this)
                .setTitle("Emir eri Turan, tekmil veriyorum")
                .setMessage("Komutanım, müsaadenizle: arz ve not alma saatlerinde size tam vaktinde bildirim gönderebilmem için "
                        + "bildirim iznine ve tam zamanlı alarm iznine ihtiyacım var. Uygulama kapalıyken ve telefon yeniden "
                        + "başladığında da bildirimler gelir. Arz ederim.")
                .setCancelable(false)
                .setPositiveButton("Devam", (d, w) -> requestNotif())
                .show();
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT >= 33 && !Store.canNotify(this)) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        } else onboardExact();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_PERM) {
            boolean ok = res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            String name = permAsking == null ? "" : permAsking; permAsking = null;
            js("window.__turanPerm && window.__turanPerm(" + JSONObject.quote(name) + "," + ok + ")");
            return;
        }
        if (code == REQ_NOTIF) {
            if (!Store.prefs(this).getBoolean("onboarded", false)) onboardExact();
            refreshStatus();
        }
    }

    private void onboardExact() {
        if (!Store.canExact(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Tam zamanlı alarm izni")
                    .setMessage("Komutanım, bildirimlerin dakikası dakikasına gelmesi için açılacak ekranda "
                            + "“Alarm ve hatırlatıcılara izin ver” seçeneğini açmanız gerekiyor. Arz ederim.")
                    .setCancelable(false)
                    .setPositiveButton("Ayarları aç", (d, w) -> { openExactSettings(); onboardBatteryLater(); })
                    .setNegativeButton("Sonra", (d, w) -> onboardBattery())
                    .show();
        } else onboardBattery();
    }

    private void onboardBatteryLater() { ui.postDelayed(this::onboardBattery, 1500); }

    private void onboardBattery() {
        Store.prefs(this).edit().putBoolean("onboarded", true).apply();
        if (ignoringBattery()) return;
        new AlertDialog.Builder(this)
                .setTitle("Pil optimizasyonu")
                .setMessage("Komutanım, bazı telefonlar pil tasarrufu için uygulamaların alarmlarını geciktirebilir. "
                        + "Bildirimlerin aksamaması için Turan Asistan'ı pil optimizasyonundan muaf tutmanızı arz ederim. "
                        + "(Xiaomi, Huawei, Oppo gibi telefonlarda ayrıca “Otomatik başlatma” iznini açın.)")
                .setPositiveButton("Muaf tut", (d, w) -> requestBattery())
                .setNegativeButton("Şimdi değil", null)
                .show();
    }

    private boolean ignoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openExactSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) { openAppSettings(); }
        }
    }

    private void openAppSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
    }

    @android.annotation.SuppressLint("BatteryLife")
    private void requestBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void refreshStatus() { js("window.__turanStatus && window.__turanStatus()"); }

    // ---------- Sonuçlar ----------
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK) {
            final ArrayList<Uri> uris = new ArrayList<>();
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            final String target = pickTarget;
            io.execute(() -> {
                JSONArray files = new JSONArray(); StringBuilder err = new StringBuilder();
                for (Uri u : uris) { try { files.put(Files.importUri(this, u)); } catch (Exception e) { err.append(e.getMessage()).append(' '); } }
                deliverFiles(target, files, err.toString());
            });
            return;
        }
        if (req == REQ_ARCH) {
            final ArrayList<Uri> uris = new ArrayList<>();
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            final long coll = archTarget;
            io.execute(() -> {
                int ok = 0; StringBuilder err = new StringBuilder();
                for (Uri u : uris) { try { Archive.get(this).addFromUri(coll, u); ok++; } catch (Exception e) { err.append(e.getMessage()).append("\n"); } }
                ArchiveWorker.start(this);
                js("window.__turanArchiveAdded && window.__turanArchiveAdded(" + ok + "," + JSONObject.quote(err.toString().trim()) + ")");
            });
            return;
        }
        if (req == REQ_LISTEN) {
            String t = "";
            if (res == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) t = r.get(0);
            }
            js("window.__turanHeard && window.__turanHeard(" + JSONObject.quote(listenTag) + "," + JSONObject.quote(t) + ")");
            return;
        }
        if (req == REQ_CAMERA) {
            final String path = cameraPath; cameraPath = null;
            if (res != RESULT_OK || path == null) return;
            final String target = pickTarget;
            io.execute(() -> {
                JSONArray files = new JSONArray(); String err = "";
                try {
                    File f = new File(path);
                    if (f.length() == 0) throw new Exception("Fotoğraf alınamadı");
                    java.text.SimpleDateFormat fm = new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US); fm.setTimeZone(Store.TZ);
                    files.put(Files.importFile(this, f, "Foto_" + fm.format(new java.util.Date()) + ".jpg", "image/jpeg"));
                } catch (Exception e) { err = e.getMessage(); }
                deliverFiles(target, files, err);
            });
            return;
        }
        if (req == REQ_CMD) {
            String t = "";
            if (res == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) t = r.get(0);
            }
            js("window.__turanCommand && window.__turanCommand(" + JSONObject.quote(t) + ")");
            return;
        }
        if (req == REQ_VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) js("window.__turanVoice(" + JSONObject.quote(r.get(0)) + ")");
        } else if (req == REQ_EXPORT && res == RESULT_OK && data != null && pendingExport != null) {
            final String json = pendingExport; final Uri dest = data.getData(); pendingExport = null;
            io.execute(() -> {
                // ZIP yedek: state.json + att/<id> (ekler)
                try (OutputStream os = getContentResolver().openOutputStream(dest); ZipOutputStream zo = new ZipOutputStream(os)) {
                    zo.putNextEntry(new ZipEntry("state.json"));
                    zo.write(json.getBytes(StandardCharsets.UTF_8)); zo.closeEntry();
                    JSONObject st = new JSONObject(json); JSONObject atts = st.optJSONObject("attachments");
                    int n = 0;
                    if (atts != null) for (java.util.Iterator<String> it = atts.keys(); it.hasNext(); ) {
                        String id = it.next(); File f = Files.file(this, id);
                        if (!f.exists()) continue;
                        zo.putNextEntry(new ZipEntry("att/" + Files.safeId(id)));
                        try (InputStream in = new FileInputStream(f)) { Files.copy(in, zo); }
                        zo.closeEntry(); n++;
                    }
                    final int cnt = n;
                    ui.post(() -> Toast.makeText(this, "Emredersiniz komutanım, yedek kaydedildi (" + cnt + " ek dosya ile).", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    ui.post(() -> Toast.makeText(this, "Yedek kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        } else if (req == REQ_IMPORT && res == RESULT_OK && data != null) {
            final Uri src = data.getData();
            io.execute(() -> {
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try (InputStream is = getContentResolver().openInputStream(src)) { Files.copy(is, bo); }
                    byte[] b = bo.toByteArray(); String json = null;
                    if (b.length > 1 && b[0] == 'P' && b[1] == 'K') {
                        try (ZipInputStream zi = new ZipInputStream(new java.io.ByteArrayInputStream(b))) {
                            ZipEntry e;
                            while ((e = zi.getNextEntry()) != null) {
                                if (e.getName().equals("state.json")) { ByteArrayOutputStream s2 = new ByteArrayOutputStream(); Files.copy(zi, s2); json = s2.toString("UTF-8"); }
                                else if (e.getName().startsWith("att/")) {
                                    try (OutputStream o = new FileOutputStream(Files.file(this, e.getName().substring(4)))) { Files.copy(zi, o); }
                                }
                            }
                        }
                    } else json = new String(b, StandardCharsets.UTF_8);
                    if (json == null) throw new Exception("Yedekte state.json yok");
                    js("window.__turanImport(" + JSONObject.quote(json) + ")");
                } catch (Exception e) {
                    ui.post(() -> Toast.makeText(this, "Yedek okunamadı: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    // ---------- JavaScript köprüsü ----------
    class Bridge {
        @JavascriptInterface public String getState() { return Store.getRaw(MainActivity.this); }

        @JavascriptInterface public void setState(String json) {
            try { new JSONObject(json); } catch (Exception e) { return; }
            Store.setRaw(MainActivity.this, json);
            Store.afterChange(MainActivity.this);
        }

        @JavascriptInterface public String status() {
            try {
                JSONObject o = new JSONObject();
                o.put("notif", Store.canNotify(MainActivity.this));
                o.put("exact", Store.canExact(MainActivity.this));
                o.put("battery", ignoringBattery());
                o.put("next", Store.nextDescription(MainActivity.this));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void requestNotif() {
            ui.post(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
                        && !Store.prefs(MainActivity.this).getBoolean("notifAsked", false)) {
                    Store.prefs(MainActivity.this).edit().putBoolean("notifAsked", true).apply();
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
                } else {
                    // Daha önce reddedildiyse sistem ayarlarına yönlendir
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    try { startActivity(i); } catch (Exception e) { openAppSettings(); }
                }
            });
        }

        @JavascriptInterface public void requestExact() { ui.post(MainActivity.this::openExactSettings); }

        @JavascriptInterface public void requestBattery() { ui.post(MainActivity.this::requestBattery); }

        @JavascriptInterface public void test() {
            ui.post(() -> {
                if (!Store.showArz(MainActivity.this)) Store.showNot(MainActivity.this, null);
            });
        }

        @JavascriptInterface public void listen() {
            ui.post(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Komutanım, not alınacak konuyu söyleyin");
                try { startActivityForResult(i, REQ_VOICE); }
                catch (ActivityNotFoundException e) { Toast.makeText(MainActivity.this, "Bu telefonda ses tanıma bulunamadı.", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface public void speak(final String text) {
            ui.post(() -> {
                if (tts == null) {
                    tts = new TextToSpeech(MainActivity.this, st -> {
                        ttsReady = st == TextToSpeech.SUCCESS;
                        if (ttsReady) { tts.setLanguage(Store.TR); tts.setSpeechRate(0.95f); tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arz"); }
                        else Toast.makeText(MainActivity.this, "Sesli okuma kullanılamıyor.", Toast.LENGTH_LONG).show();
                    });
                } else if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arz");
            });
        }

        @JavascriptInterface public void stopSpeak() { ui.post(() -> { if (tts != null) tts.stop(); }); }

        @JavascriptInterface public void exportBackup(final String json, final String name) {
            ui.post(() -> {
                pendingExport = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name.replaceAll("\\.json$", ".zip"));
                try { startActivityForResult(i, REQ_EXPORT); } catch (Exception e) { Toast.makeText(MainActivity.this, "Dosya kaydedici açılamadı.", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface public void importBackup() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(i, REQ_IMPORT); } catch (Exception e) { Toast.makeText(MainActivity.this, "Dosya seçici açılamadı.", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface public void exit() { ui.post(MainActivity.this::goBack); }

        // ----- Sesli komut -----
        @JavascriptInterface public void listenCommand() {
            ui.post(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Emredin komutanım");
                try { startActivityForResult(i, REQ_CMD); }
                catch (ActivityNotFoundException e) { js("window.__turanCommand && window.__turanCommand('', 'nomic')"); }
            });
        }

        // ----- Güncelleme -----
        @JavascriptInterface public String appInfo() { return Updater.info(MainActivity.this).toString(); }
        @JavascriptInterface public void checkUpdate() { ui.post(() -> Updater.check(MainActivity.this, true)); }

        @JavascriptInterface public boolean isNight() { return MainActivity.this.isNight(); }

        // ----- Araştır -----
        @JavascriptInterface public void fetch(final String reqId, final String url) {
            io.execute(() -> {
                JSONObject r;
                try { r = Files.httpGet(url); }
                catch (Throwable e) { r = new JSONObject(); try { r.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {} }
                js("window.__turanFetch && window.__turanFetch(" + JSONObject.quote(reqId) + "," + r + ")");
            });
        }

        @JavascriptInterface public void aiChat(final String reqId, final String q, final String ctx) {
            new Thread(() -> {
                JSONObject r = new JSONObject();
                try { r.put("ok", true).put("answer", Files.aiChat(MainActivity.this, q, ctx)); }
                catch (Throwable e) { try { r.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {} }
                js("window.__turanChat && window.__turanChat(" + JSONObject.quote(reqId) + "," + r + ")");
            }).start();
        }

        // ----- Hızlı erişim -----
        @JavascriptInterface public boolean getPersistent() { return Store.prefs(MainActivity.this).getBoolean("persistent", false); }

        @JavascriptInterface public void setPersistent(boolean on) {
            Store.prefs(MainActivity.this).edit().putBoolean("persistent", on).apply();
            Store.updatePersistent(MainActivity.this, null);
        }

        @JavascriptInterface public void pinWidget() {
            ui.post(() -> {
                android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(MainActivity.this);
                if (Build.VERSION.SDK_INT >= 26 && m.isRequestPinAppWidgetSupported()) {
                    m.requestPinAppWidget(new android.content.ComponentName(MainActivity.this, TuranWidget.class), null, null);
                } else Toast.makeText(MainActivity.this, "Ana ekranda boş yere basılı tutun → Widget'lar → Turan Asistan", Toast.LENGTH_LONG).show();
            });
        }

        @JavascriptInterface public void addTile() {
            ui.post(() -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    android.app.StatusBarManager sbm = getSystemService(android.app.StatusBarManager.class);
                    sbm.requestAddTileService(new android.content.ComponentName(MainActivity.this, VoiceTile.class), "Turan: Sesli not",
                            android.graphics.drawable.Icon.createWithResource(MainActivity.this, R.drawable.ic_stat), getMainExecutor(), r -> {});
                } else Toast.makeText(MainActivity.this, "Bildirim panelini aşağı çekin → kalem (düzenle) → “Turan: Sesli not” kutucuğunu sürükleyin", Toast.LENGTH_LONG).show();
            });
        }

        @JavascriptInterface public boolean isAssistant() {
            if (Build.VERSION.SDK_INT >= 29) {
                android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
                return rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT);
            }
            String a = Settings.Secure.getString(getContentResolver(), "assistant");
            return a != null && a.startsWith(getPackageName());
        }

        @JavascriptInterface public void openAssistantSettings() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
                catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); } catch (Exception e2) { openAppSettings(); }
                }
            });
        }

        // ----- Ekler -----
        @JavascriptInterface public void pickFiles(final String target, final String kind) {
            ui.post(() -> {
                pickTarget = target == null ? "new" : target;
                Intent i;
                if ("gallery".equals(kind)) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
                    } else {
                        i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                } else {
                    i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try { startActivityForResult(i, REQ_PICK); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Seçici açılamadı.", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface public void takePhoto(final String target) {
            ui.post(() -> {
                try {
                    pickTarget = target == null ? "new" : target;
                    File d = new File(getCacheDir(), "camera"); d.mkdirs();
                    File f = new File(d, "foto_" + System.currentTimeMillis() + ".jpg");
                    cameraPath = f.getPath();
                    Uri u = FileProvider.getUriForFile(MainActivity.this, Files.AUTH, f);
                    Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, u)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setClipData(android.content.ClipData.newRawUri("", u));
                    startActivityForResult(i, REQ_CAMERA);
                } catch (Exception e) { Toast.makeText(MainActivity.this, "Kamera açılamadı.", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface public void openAtt(final String id, final String name, final String mime) {
            io.execute(() -> {
                try {
                    Uri u = Files.shareUri(MainActivity.this, id, name);
                    Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ui.post(() -> {
                        try { startActivity(Intent.createChooser(i, name)); }
                        catch (Exception e) { Toast.makeText(MainActivity.this, "Bu dosyayı açacak uygulama yok.", Toast.LENGTH_LONG).show(); }
                    });
                } catch (Exception e) { ui.post(() -> Toast.makeText(MainActivity.this, "Dosya açılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
            });
        }

        /** files: [{id,name,mime}] JSON; text: paylaşılacak metin */
        @JavascriptInterface public void share(final String text, final String filesJson) {
            io.execute(() -> {
                try {
                    JSONArray fa = new JSONArray(filesJson == null || filesJson.isEmpty() ? "[]" : filesJson);
                    ArrayList<Uri> uris = new ArrayList<>(); String mime = null;
                    for (int k = 0; k < fa.length(); k++) {
                        JSONObject f = fa.getJSONObject(k);
                        uris.add(Files.shareUri(MainActivity.this, f.getString("id"), f.optString("name")));
                        String m = f.optString("mime", "*/*");
                        mime = mime == null ? m : (mime.equals(m) ? mime : "*/*");
                    }
                    Intent i;
                    if (uris.isEmpty()) i = new Intent(Intent.ACTION_SEND).setType("text/plain");
                    else if (uris.size() == 1) i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    else i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (!uris.isEmpty()) {
                        android.content.ClipData cd = android.content.ClipData.newRawUri("", uris.get(0));
                        for (int k = 1; k < uris.size(); k++) cd.addItem(new android.content.ClipData.Item(uris.get(k)));
                        i.setClipData(cd);
                    }
                    final Intent ch = Intent.createChooser(i, "Paylaş");
                    ui.post(() -> startActivity(ch));
                } catch (Exception e) { ui.post(() -> Toast.makeText(MainActivity.this, "Paylaşılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
            });
        }

        @JavascriptInterface public void deleteAtt(String id) { Files.file(MainActivity.this, id).delete(); }

        @JavascriptInterface public void saveText(final String target, final String text, final String name) {
            io.execute(() -> {
                JSONArray files = new JSONArray(); String err = "";
                try { files.put(Files.importText(MainActivity.this, text, name)); } catch (Exception e) { err = e.getMessage(); }
                deliverFiles(target, files, err);
            });
        }

        /** Metin çıkarır, sonucu window.__turanAnalysis(reqId, sonuç) ile döndürür. */
        @JavascriptInterface public void extract(final String reqId, final String id, final String mime, final String name) {
            io.execute(() -> {
                JSONObject r;
                try { r = Files.extract(MainActivity.this, id, mime, name); r.put("ok", true); }
                catch (Throwable e) { r = new JSONObject(); try { r.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {} }
                js("window.__turanAnalysis && window.__turanAnalysis(" + JSONObject.quote(reqId) + "," + r + ")");
            });
        }

        @JavascriptInterface public String aiConfig() {
            try {
                return new JSONObject().put("url", Store.prefs(MainActivity.this).getString("ai_url", ""))
                        .put("model", Store.prefs(MainActivity.this).getString("ai_model", ""))
                        .put("hasKey", !Store.prefs(MainActivity.this).getString("ai_key", "").isEmpty()).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void setAiConfig(String url, String model, String key, boolean clearKey) {
            android.content.SharedPreferences.Editor e = Store.prefs(MainActivity.this).edit().putString("ai_url", url == null ? "" : url.trim()).putString("ai_model", model == null ? "" : model.trim());
            if (clearKey) e.remove("ai_key"); else if (key != null && !key.trim().isEmpty()) e.putString("ai_key", key.trim());
            e.apply();
        }

        @JavascriptInterface public void ai(final String reqId, final String text, final String attId, final String mime) {
            new Thread(() -> {
                JSONObject r;
                try { r = Files.ai(MainActivity.this, text, attId, mime); r.put("ok", true); }
                catch (Throwable e) { r = new JSONObject(); try { r.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {} }
                js("window.__turanAI && window.__turanAI(" + JSONObject.quote(reqId) + "," + r + ")");
            }).start();
        }

        // ----- Arşiv Tarama -----
        @JavascriptInterface public String archiveList() {
            try { return Archive.get(MainActivity.this).list().toString(); } catch (Exception e) { return "{\"colls\":[],\"docs\":[]}"; }
        }
        @JavascriptInterface public long archiveCreate(String name) { return Archive.get(MainActivity.this).createColl(name == null ? "" : name); }
        @JavascriptInterface public void archiveRename(long id, String name) { Archive.get(MainActivity.this).renameColl(id, name); }
        @JavascriptInterface public void docRename(long id, String name) { Archive.get(MainActivity.this).renameDoc(id, name); }
        @JavascriptInterface public void archiveDelete(final long id) { io.execute(() -> { Archive.get(MainActivity.this).deleteColl(id); notifyArchive(); }); }
        @JavascriptInterface public void docDelete(final long id) { io.execute(() -> { Archive.get(MainActivity.this).deleteDoc(id); notifyArchive(); }); }
        @JavascriptInterface public void archiveRetry(long id) { Archive.get(MainActivity.this).retry(id); ArchiveWorker.start(MainActivity.this); }
        @JavascriptInterface public void archivePick(final long coll) {
            ui.post(() -> {
                archTarget = coll;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, REQ_ARCH); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Dosya seçici açılamadı.", Toast.LENGTH_LONG).show(); }
            });
        }
        /** Paylaş ile gelen ekleri (att id listesi) arşive aktarır. */
        @JavascriptInterface public void archiveAddAtts(final long coll, final String json) {
            io.execute(() -> {
                int ok = 0; StringBuilder err = new StringBuilder();
                try {
                    JSONArray a = new JSONArray(json);
                    for (int k = 0; k < a.length(); k++) {
                        JSONObject f = a.getJSONObject(k);
                        try { Archive.get(MainActivity.this).addFile(coll, Files.file(MainActivity.this, f.getString("id")), f.optString("name", "belge.pdf")); ok++; }
                        catch (Exception e) { err.append(e.getMessage()).append("\n"); }
                    }
                } catch (Exception e) { err.append(e.getMessage()); }
                ArchiveWorker.start(MainActivity.this);
                js("window.__turanArchiveAdded && window.__turanArchiveAdded(" + ok + "," + JSONObject.quote(err.toString().trim()) + ")");
            });
        }
        @JavascriptInterface public void archiveAsk(final String reqId, final long coll, final String q) {
            new Thread(() -> {
                JSONObject r;
                try { r = Archive.get(MainActivity.this).ask(coll, q); r.put("ok", true); }
                catch (Throwable e) { r = new JSONObject(); try { r.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {} }
                js("window.__turanArchive && window.__turanArchive(" + JSONObject.quote(reqId) + "," + r + ")");
            }).start();
        }
        @JavascriptInterface public void archiveSummary(final String reqId, final long coll, final long doc) {
            new Thread(() -> {
                JSONObject r;
                try { r = Archive.get(MainActivity.this).summary(coll, doc); r.put("ok", true); }
                catch (Throwable e) { r = new JSONObject(); try { r.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {} }
                js("window.__turanArchive && window.__turanArchive(" + JSONObject.quote(reqId) + "," + r + ")");
            }).start();
        }
        @JavascriptInterface public void archiveKick() { ArchiveWorker.start(MainActivity.this); }
        @JavascriptInterface public void archiveOpen(final long docId) {
            io.execute(() -> {
                try {
                    android.content.ContentValues row = Archive.get(MainActivity.this).docRow(docId);
                    if (row == null) return;
                    File f = new File(Archive.dir(MainActivity.this), row.getAsString("file"));
                    Uri u = FileProvider.getUriForFile(MainActivity.this, Files.AUTH, f);
                    Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ui.post(() -> {
                        try { startActivity(Intent.createChooser(i, row.getAsString("name"))); }
                        catch (Exception e) { Toast.makeText(MainActivity.this, "PDF açacak uygulama yok.", Toast.LENGTH_LONG).show(); }
                    });
                } catch (Exception e) { ui.post(() -> Toast.makeText(MainActivity.this, "Belge açılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
            });
        }

        /** Etiketli sesli giriş: sonuç window.__turanHeard(tag, metin) */
        @JavascriptInterface public void listenTo(final String tag) {
            ui.post(() -> {
                listenTag = tag == null ? "" : tag;
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR").putExtra(RecognizerIntent.EXTRA_PROMPT, "Komutanım, sorunuzu söyleyin");
                try { startActivityForResult(i, REQ_LISTEN); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Ses tanıma bulunamadı.", Toast.LENGTH_LONG).show(); }
            });
        }

        // ----- Rehber, arama, SMS, WhatsApp -----
        @JavascriptInterface public String permState() { try { return Comm.permState(MainActivity.this).toString(); } catch (Exception e) { return "{}"; } }

        @JavascriptInterface public void requestPerm(final String name) {
            ui.post(() -> {
                final String perm = Comm.permOf(name);
                if (perm == null) return;
                if (Comm.has(MainActivity.this, perm)) { js("window.__turanPerm && window.__turanPerm(" + JSONObject.quote(name) + ",true)"); return; }
                String why = "contacts".equals(name) ? "“Ahmet'i ara”, “Mehmet'e mesaj at” gibi komutlarda kişiyi rehberinizden bulabilmem için rehberi okuma iznine ihtiyacım var. Rehberiniz telefonda kalır, hiçbir yere gönderilmez."
                        : "call".equals(name) ? "sesli komutla, onayınızdan sonra doğrudan arama başlatabilmem için telefon araması iznine ihtiyacım var. İzin vermezseniz numarayı tuşlanmış olarak açarım, aramayı siz başlatırsınız."
                        : "sesli komutla, onayınızdan sonra SMS gönderebilmem için SMS gönderme iznine ihtiyacım var. Her mesajı göndermeden önce size okuyup onay alırım. Operatör ücretleri uygulanabilir.";
                final boolean asked = Store.prefs(MainActivity.this).getBoolean("asked_" + name, false);
                final boolean blocked = asked && !shouldShowRequestPermissionRationale(perm);
                AlertDialog.Builder bd = new AlertDialog.Builder(MainActivity.this).setTitle("İzin gerekiyor")
                        .setMessage("Komutanım, " + why + (blocked ? "\n\nBu izin daha önce reddedildiği için Ayarlar > İzinler bölümünden açmanız gerekiyor." : "") + " Arz ederim.")
                        .setNegativeButton("Vazgeç", (d, w) -> js("window.__turanPerm && window.__turanPerm(" + JSONObject.quote(name) + ",false)"));
                if (blocked) bd.setPositiveButton("Ayarları aç", (d, w) -> openAppSettings());
                else bd.setPositiveButton("İzin ver", (d, w) -> {
                    Store.prefs(MainActivity.this).edit().putBoolean("asked_" + name, true).apply();
                    permAsking = name;
                    requestPermissions(new String[]{perm}, REQ_PERM);
                });
                bd.setCancelable(false).show();
            });
        }

        @JavascriptInterface public void contacts(final String reqId) {
            io.execute(() -> {
                String r;
                try { r = Comm.contacts(MainActivity.this).toString(); } catch (Exception e) { r = "null"; }
                js("window.__turanContacts && window.__turanContacts(" + JSONObject.quote(reqId) + "," + r + ")");
            });
        }

        @JavascriptInterface public void call(final String number) {
            ui.post(() -> {
                Uri u = Uri.parse("tel:" + Uri.encode(number));
                try {
                    if (Comm.has(MainActivity.this, android.Manifest.permission.CALL_PHONE)) startActivity(new Intent(Intent.ACTION_CALL, u));
                    else startActivity(new Intent(Intent.ACTION_DIAL, u));
                } catch (Exception e) {
                    try { startActivity(new Intent(Intent.ACTION_DIAL, u)); } catch (Exception e2) { Toast.makeText(MainActivity.this, "Arama başlatılamadı.", Toast.LENGTH_LONG).show(); }
                }
            });
        }

        @JavascriptInterface public void sms(final String reqId, final String number, final String text) {
            ui.post(() -> {
                if (!Comm.has(MainActivity.this, android.Manifest.permission.SEND_SMS)) { smsCompose(number, text); js("window.__turanSmsResult && window.__turanSmsResult(" + JSONObject.quote(reqId) + ",'compose')"); return; }
                try {
                    android.telephony.SmsManager sm = Build.VERSION.SDK_INT >= 31 ? getSystemService(android.telephony.SmsManager.class) : android.telephony.SmsManager.getDefault();
                    ArrayList<String> parts = sm.divideMessage(text);
                    final String action = getPackageName() + ".SMS_SENT." + reqId;
                    final int[] left = {parts.size()}; final boolean[] fail = {false};
                    android.content.BroadcastReceiver br = new android.content.BroadcastReceiver() {
                        @Override public void onReceive(android.content.Context c, Intent in) {
                            if (getResultCode() != RESULT_OK) fail[0] = true;
                            if (--left[0] <= 0) {
                                try { unregisterReceiver(this); } catch (Exception ignored) {}
                                js("window.__turanSmsResult && window.__turanSmsResult(" + JSONObject.quote(reqId) + "," + JSONObject.quote(fail[0] ? "fail" : "ok") + ")");
                            }
                        }
                    };
                    if (Build.VERSION.SDK_INT >= 33) registerReceiver(br, new android.content.IntentFilter(action), RECEIVER_NOT_EXPORTED);
                    else registerReceiver(br, new android.content.IntentFilter(action));
                    ArrayList<android.app.PendingIntent> sent = new ArrayList<>();
                    for (int k = 0; k < parts.size(); k++)
                        sent.add(android.app.PendingIntent.getBroadcast(MainActivity.this, k, new Intent(action).setPackage(getPackageName()), android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_ONE_SHOT));
                    sm.sendMultipartTextMessage(number, null, parts, sent, null);
                } catch (Exception e) {
                    android.util.Log.w("Turan", "sms", e);
                    smsCompose(number, text);
                    js("window.__turanSmsResult && window.__turanSmsResult(" + JSONObject.quote(reqId) + ",'compose')");
                }
            });
        }

        @JavascriptInterface public String waUrl(String number, String text) { return Comm.waUrl(number, text); }

        @JavascriptInterface public String whatsapp(final String number, final String text, final boolean autoSend) {
            String pkg = Comm.waPackage(MainActivity.this);
            if (autoSend && pkg != null && Comm.waServiceEnabled(MainActivity.this)) Comm.armWa(MainActivity.this, text);
            else Comm.disarmWa(MainActivity.this);
            ui.post(() -> {
                try { startActivity(Comm.waIntent(MainActivity.this, number, text)); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "WhatsApp açılamadı.", Toast.LENGTH_LONG).show(); }
            });
            return pkg == null ? "web" : (autoSend && Comm.waServiceEnabled(MainActivity.this) ? "auto" : "app");
        }

        @JavascriptInterface public void openAccessibility() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception e) { openAppSettings(); }
            });
        }
        @JavascriptInterface public void openAppDetails() { ui.post(MainActivity.this::openAppSettings); }

        // ----- X (Twitter) -----
        @JavascriptInterface public void openGizli() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, GizliActivity.class)));
        }
        @JavascriptInterface public String gizliInfo() {
            try {
                boolean ex = GizliStore.exists(MainActivity.this);
                return new org.json.JSONObject().put("exists", ex).put("online", GizliService.isRunning()).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void openX(final boolean auto) {
            ui.post(() -> startActivity(new Intent(MainActivity.this, XActivity.class).putExtra("auto", auto)));
        }
        @JavascriptInterface public String xInfo() {
            try {
                return new JSONObject().put("loggedIn", XActivity.loggedIn()).put("screens", XActivity.screens(MainActivity.this))
                        .put("last", new JSONObject(Store.prefs(MainActivity.this).getString("xLast", "{}"))).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void setXScreens(int n) { Store.prefs(MainActivity.this).edit().putInt("xScreens", Math.max(3, Math.min(60, n))).apply(); }
        @JavascriptInterface public void xLogout() {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            ui.post(() -> { cm.removeAllCookies(null); cm.flush(); });
        }
    }

    private void smsCompose(String number, String text) {
        try { startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text)); }
        catch (Exception e) { Toast.makeText(this, "Mesaj uygulaması açılamadı.", Toast.LENGTH_LONG).show(); }
    }

    private void goBack() { moveTaskToBack(true); }
}
