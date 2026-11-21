package com.ahmet.emirerituran;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * X (Twitter) Zaman Tüneli: Ahmet kendi hesabıyla bu pencerede giriş yapar (yalnızca X'in çerezleri saklanır, şifre saklanmaz).
 * "Özetle" ekranı belirli sayıda kaydırır, gönderileri sayfadan toplar ve özetler.
 */
public class XActivity extends Activity {
    private WebView web;
    private TextView status;
    private Button btnSum;
    private boolean running = false, autoPending = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF2E3A23); int pad = dp(8); bar.setPadding(pad, pad / 2, pad / 2, pad / 2);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(13); status.setText("X Zaman Tüneli");
        status.setTypeface(Typeface.DEFAULT_BOLD); status.setMaxLines(2);
        bar.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        btnSum = button("Özetle"); btnSum.setOnClickListener(v -> summarize());
        Button close = button("Kapat"); close.setOnClickListener(v -> finish());
        bar.addView(btnSum); bar.addView(close);
        root.addView(bar);
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setSaveFormData(false); s.setSavePassword(false);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager cm = CookieManager.getInstance(); cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new XBridge(), "TuranX");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sch = r.getUrl().getScheme();
                return sch == null || !(sch.equals("https") || sch.equals("http"));
            }
            @Override public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
                updateStatus();
                if (autoPending && loggedIn()) { autoPending = false; ui.postDelayed(XActivity.this::summarize, 3500); }
            }
        });
        autoPending = getIntent().getBooleanExtra("auto", false);
        web.loadUrl(BuildConfig.X_HOME);
        updateStatus();
    }

    private Button button(String t) {
        Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setTextColor(0xFF2E3A23); b.setBackgroundColor(0xFFD4AF37);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)); lp.leftMargin = dp(6);
        b.setLayoutParams(lp); b.setPadding(dp(10), 0, dp(10), 0); return b;
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    static boolean loggedIn() {
        String c = CookieManager.getInstance().getCookie(BuildConfig.X_HOME);
        return c != null && c.contains("auth_token=");
    }

    private void updateStatus() {
        if (running) return;
        status.setText(loggedIn() ? "X Zaman Tüneli · giriş yapıldı" : "X Zaman Tüneli · lütfen kendi hesabınızla giriş yapın");
    }

    static int screens(android.content.Context c) { return Math.max(3, Math.min(60, Store.prefs(c).getInt("xScreens", 15))); }

    private void summarize() {
        if (running) return;
        if (!loggedIn() && !BuildConfig.X_HOME.startsWith("http://")) {
            new AlertDialog.Builder(this).setTitle("Turan Asistan").setMessage("Komutanım, önce bu pencerede X hesabınızla giriş yapmanız gerekiyor. "
                    + "Şifreniz bende saklanmaz; yalnızca X'in oturum çerezi bu uygulamada kalır. Arz ederim.").setPositiveButton("Tamam", null).show();
            return;
        }
        running = true; btnSum.setEnabled(false);
        int n = screens(this);
        status.setText("Zaman tüneli taranıyor… 0/" + n);
        web.evaluateJavascript(SCRAPER.replace("__N__", String.valueOf(n)), null);
    }

    static final String SCRAPER = "(function(N){var seen={},out=[],i=0;" +
            "function num(el){if(!el)return 0;var m=(el.getAttribute('aria-label')||'').replace(/[.,](?=\\d{3})/g,'').match(/\\d+/);return m?parseInt(m[0],10):0;}" +
            "function grab(){document.querySelectorAll('article[data-testid=\"tweet\"],article').forEach(function(a){" +
            "var t=a.querySelector('[data-testid=\"tweetText\"]');var link=a.querySelector('a[href*=\"/status/\"]');var href=link?link.getAttribute('href'):'';" +
            "var txt=t?t.innerText.trim():'';var key=href||txt;if(!key||seen[key])return;seen[key]=1;" +
            "var u=a.querySelector('[data-testid=\"User-Name\"]');var name='',handle='';if(u){var it=u.innerText||'';var hm=it.match(/@[A-Za-z0-9_]{1,15}/);" +
            "handle=hm?hm[0]:'';name=(hm?it.slice(0,hm.index):it.split('\\n')[0]).replace(/\\s+/g,' ').trim();}" +
            "var tm=a.querySelector('time');var sc=a.querySelector('[data-testid=\"socialContext\"]');" +
            "out.push({name:name,handle:handle,text:txt,time:tm?tm.getAttribute('datetime'):'',url:href?(href.indexOf('http')==0?href:location.origin+href):''," +
            "likes:num(a.querySelector('[data-testid=\"like\"],[data-testid=\"unlike\"]')),rts:num(a.querySelector('[data-testid=\"retweet\"],[data-testid=\"unretweet\"]'))," +
            "replies:num(a.querySelector('[data-testid=\"reply\"]')),ctx:sc?sc.innerText:''});});}" +
            "function step(){grab();TuranX.progress(i,N,out.length);if(i++>=N){TuranX.done(JSON.stringify(out));window.scrollTo(0,0);return;}" +
            "window.scrollBy(0,Math.round(window.innerHeight*0.9));setTimeout(step,1700);}step();})(__N__)";

    class XBridge {
        @JavascriptInterface public void progress(int i, int n, int count) {
            ui.post(() -> status.setText("Zaman tüneli taranıyor… " + Math.min(i, n) + "/" + n + " ekran · " + count + " gönderi"));
        }
        @JavascriptInterface public void done(String json) {
            ui.post(() -> status.setText("Özet hazırlanıyor…"));
            new Thread(() -> {
                String text;
                try { text = summarizeTweets(XActivity.this, new JSONArray(json)); }
                catch (Exception e) { text = "Komutanım, özet çıkarılamadı: " + e.getMessage(); }
                final String t = text;
                try {
                    Store.prefs(XActivity.this).edit().putString("xLast", new JSONObject().put("text", t).put("at", System.currentTimeMillis()).toString()).apply();
                } catch (Exception ignored) {}
                ui.post(() -> { running = false; btnSum.setEnabled(true); updateStatus(); show(t); });
            }).start();
        }
    }

    private void show(String text) {
        if (isFinishing()) return;
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Zaman tüneli özeti").setMessage(text)
                .setPositiveButton("Sesli oku", null)
                .setNeutralButton("Nota kaydet", (x, w) -> {
                    String first = text.length() > 1500 ? text.substring(0, 1500) + "…" : text;
                    Store.addItem(this, "X özeti (" + new SimpleDateFormat("d MMM HH:mm", new Locale("tr")).format(new Date()) + "): " + first);
                    Toast.makeText(this, "Not olarak kaydedildi.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Kapat", (x, w) -> { if (tts != null) tts.stop(); }).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> speak(text)));
        d.show();
    }

    private void speak(String text) {
        String t = text.replaceAll("https?://\\S+", "").replace("•", "");
        if (tts == null) {
            tts = new TextToSpeech(this, st -> { if (st == TextToSpeech.SUCCESS) { tts.setLanguage(new Locale("tr", "TR")); tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "x"); } });
        } else tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "x");
    }

    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); CookieManager.getInstance().flush(); if (web != null) web.destroy(); super.onDestroy(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    // ---------- Özet ----------
    static final Set<String> XSTOP = new HashSet<>(java.util.Arrays.asList(("bir icin ile gibi daha cok ama ben sen biz siz onlar bunu buna olan oldu olsun degil "
            + "kadar sonra once diye simdi bugun yeni var yok her hep tum neden nasil boyle soyle sadece bile zaten artik ancak cunku olarak "
            + "the and for that this with you your are was have has not but from they will just what about all more can like out get one").split(" ")));
    static final Pattern TAG = Pattern.compile("[#@][\\p{L}\\p{N}_]{2,}");

    static String summarizeTweets(android.content.Context c, JSONArray tw) throws Exception {
        if (tw.length() == 0) return "Komutanım, zaman tünelinde okunabilir gönderi bulamadım. Giriş yaptığınızdan ve sayfanın yüklendiğinden emin olup tekrar deneyin. Arz ederim.";
        Map<String, Integer> authors = new HashMap<>(); Map<String, String> authorName = new HashMap<>();
        Map<String, Integer> terms = new HashMap<>(); Map<String, String> surface = new HashMap<>(); Map<String, Integer> tags = new HashMap<>();
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < tw.length(); i++) {
            JSONObject t = tw.getJSONObject(i); list.add(t);
            String h = t.optString("handle", "?"); authors.put(h, authors.containsKey(h) ? authors.get(h) + 1 : 1); authorName.put(h, t.optString("name", h));
            String text = t.optString("text", "");
            Matcher m = TAG.matcher(text);
            while (m.find()) { String g = m.group(); tags.put(g, tags.containsKey(g) ? tags.get(g) + 1 : 1); }
            Set<String> once = new HashSet<>();
            for (String w : TAG.matcher(text.replaceAll("https?://\\S+", "")).replaceAll(" ").split("[^\\p{L}\\p{N}]+")) {
                if (w.length() < 4) continue;
                String f = Archive.fold(w); if (f.length() < 4 || XSTOP.contains(f) || Archive.STOP.contains(f) || f.matches("\\d+")) continue;
                String k = Archive.stem(f); if (!once.add(k)) continue;
                terms.put(k, terms.containsKey(k) ? terms.get(k) + 1 : 1);
                if (!surface.containsKey(k)) surface.put(k, w.toLowerCase(new Locale("tr")));
            }
        }
        boolean ai = !Store.prefs(c).getString("ai_key", "").trim().isEmpty();
        if (ai) {
            StringBuilder sb = new StringBuilder();
            for (JSONObject t : list) {
                if (sb.length() > 14000) break;
                sb.append("- ").append(t.optString("name")).append(" ").append(t.optString("handle")).append(" (♥").append(t.optInt("likes")).append(", RT ").append(t.optInt("rts")).append("): ")
                        .append(t.optString("text").replace('\n', ' ')).append("\n");
            }
            try {
                return Files.aiChatSys(c, "Sen Turan Asistan'sın; kullanıcıya 'Komutanım' diye hitap eden askeri nezaketli bir emir erisin. Kullanıcının X (Twitter) zaman tünelinden "
                        + "toplanan gönderileri Türkçe özetle: 1) Gündemin genel özeti (3-5 cümle), 2) Öne çıkan konular (madde madde, kim ne demiş), 3) Dikkat çeken/çok etkileşim alan gönderiler, "
                        + "4) Varsa takip edilmesi gereken gelişmeler. Uydurma bilgi ekleme. Sonunda 'Arz ederim.' de.",
                        tw.length() + " gönderi:\n" + sb);
            } catch (Exception e) { android.util.Log.w("Turan", "x ai", e); }
        }
        StringBuilder out = new StringBuilder("Emir eri Turan, tekmil veriyorum. Komutanım, zaman tünelinizde ").append(tw.length()).append(" gönderi taradım.\n\n");
        out.append("En çok paylaşım yapanlar:\n");
        for (Map.Entry<String, Integer> e : top(authors, 5)) out.append("• ").append(authorName.get(e.getKey())).append(" ").append(e.getKey()).append(" – ").append(e.getValue()).append(" gönderi\n");
        List<Map.Entry<String, Integer>> tt = top(terms, 10);
        if (!tt.isEmpty()) {
            out.append("\nÖne çıkan konular: ");
            List<String> s = new ArrayList<>(); for (Map.Entry<String, Integer> e : tt) if (e.getValue() > 1) s.add(surface.get(e.getKey()) + " (" + e.getValue() + ")");
            out.append(s.isEmpty() ? "belirgin bir tekrar yok" : android.text.TextUtils.join(", ", s)).append("\n");
        }
        List<Map.Entry<String, Integer>> tg = top(tags, 8);
        if (!tg.isEmpty()) { List<String> s = new ArrayList<>(); for (Map.Entry<String, Integer> e : tg) s.add(e.getKey() + (e.getValue() > 1 ? " (" + e.getValue() + ")" : "")); out.append("Etiketler/anılanlar: ").append(android.text.TextUtils.join(", ", s)).append("\n"); }
        Collections.sort(list, (x, y) -> (y.optInt("likes") + 2 * y.optInt("rts")) - (x.optInt("likes") + 2 * x.optInt("rts")));
        out.append("\nDikkat çeken gönderiler:\n");
        int k = 0;
        for (JSONObject t : list) {
            String text = t.optString("text").replace('\n', ' ').trim(); if (text.isEmpty()) continue;
            if (text.length() > 220) text = text.substring(0, 220) + "…";
            out.append("• ").append(t.optString("name")).append(": “").append(text).append("”");
            if (t.optInt("likes") > 0 || t.optInt("rts") > 0) out.append(" (♥ ").append(t.optInt("likes")).append(", RT ").append(t.optInt("rts")).append(")");
            out.append("\n");
            if (++k >= 5) break;
        }
        out.append(ai ? "\n(Yapay zekâ servisine ulaşılamadı; özet telefonda çıkarıldı.) Arz ederim." : "\n(Yapay zekâ anahtarı girilirse konu konu daha akıllı bir özet hazırlarım.) Arz ederim.");
        return out.toString();
    }

    static List<Map.Entry<String, Integer>> top(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, (x, y) -> y.getValue() - x.getValue());
        return l.size() > n ? l.subList(0, n) : l;
    }
}
