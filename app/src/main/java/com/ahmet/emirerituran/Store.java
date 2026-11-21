package com.ahmet.emirerituran;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Ortak durum, metin ve zamanlama mantığı. Durum SharedPreferences'ta JSON olarak tutulur (tek doğru kaynak). */
public final class Store {
    static final String PREFS = "turan";
    static final String CHANNEL = "turan_tekmil";
    static final TimeZone TZ = TimeZone.getTimeZone("Europe/Istanbul");
    static final Locale TR = new Locale("tr", "TR");
    static final String ARZ_HEAD = "Emir eri Turan, tekmil veriyorum. Komutanım, müsaadenizle şu tarihli konuları arz ediyorum:";
    static final String ARZ_TAIL = "Arz ederim.";
    static final String NOT_TEXT = "Emir eri Turan, tekmil veriyorum. Komutanım, müsaadenizle gün sonu notlarını almak istiyorum. Arz ederim.";
    static final int NID_ARZ = 1, NID_NOT = 2, NID_TEST = 3, NID_PERSIST = 4;
    static final String CHANNEL_QUIET = "turan_hazir";
    static final String KEY_REPLY = "note_text";
    static final Object LOCK = new Object();

    private Store() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String getRaw(Context c) { synchronized (LOCK) { return prefs(c).getString("state", ""); } }

    static void setRaw(Context c, String json) {
        synchronized (LOCK) { prefs(c).edit().putString("state", json).commit(); }
    }

    static JSONObject getState(Context c) {
        try { String r = getRaw(c); return r.isEmpty() ? null : new JSONObject(r); } catch (Exception e) { return null; }
    }

    static JSONObject defaultSchedule() {
        try {
            return new JSONObject("{\"wd\":{\"arz\":\"08:30\",\"not\":\"17:00\",\"on\":true},"
                    + "\"sa\":{\"arz\":\"09:30\",\"not\":\"12:00\",\"on\":true},"
                    + "\"su\":{\"arz\":\"09:30\",\"not\":\"12:00\",\"on\":false}}");
        } catch (Exception e) { return new JSONObject(); }
    }

    static JSONObject schedule(JSONObject st) {
        JSONObject s = st == null ? null : st.optJSONObject("settings");
        JSONObject sc = s == null ? null : s.optJSONObject("schedule");
        return sc == null ? defaultSchedule() : sc;
    }

    // ---------- Tarih yardımcıları ----------
    static long parseIso(String s) {
        String[] pats = {"yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX"};
        for (String p : pats) {
            try { SimpleDateFormat f = new SimpleDateFormat(p, Locale.US); f.setLenient(false); return f.parse(s).getTime(); } catch (Exception ignored) {}
        }
        return 0;
    }

    static String fmtDate(long t) {
        SimpleDateFormat f = new SimpleDateFormat("d MMMM yyyy", TR); f.setTimeZone(TZ); return f.format(new Date(t));
    }

    static String dayKey(Calendar c) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US); f.setTimeZone(TZ); return f.format(c.getTime());
    }

    static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date());
    }

    // ---------- Arz metni ----------
    static String arzText(JSONObject st) {
        if (st == null) return "";
        JSONArray items = st.optJSONArray("items");
        if (items == null) return "";
        List<JSONObject> open = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && "open".equals(it.optString("status"))) open.add(it);
        }
        if (open.isEmpty()) return "";
        Collections.sort(open, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) { return Long.compare(parseIso(a.optString("created")), parseIso(b.optString("created"))); }
        });
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, List<String>> urgent = new LinkedHashMap<>();
        for (JSONObject it : open) {
            String d = fmtDate(parseIso(it.optString("created")));
            boolean acil = "acil".equals(it.optString("prio"));
            Map<String, List<String>> target = acil ? urgent : groups;
            if (!groups.containsKey(d)) groups.put(d, new ArrayList<String>());
            if (!urgent.containsKey(d)) urgent.put(d, new ArrayList<String>());
            target.get(d).add(itemLine(it));
        }
        // Her tarih grubunda acil işler önce
        for (Map.Entry<String, List<String>> e : groups.entrySet()) e.getValue().addAll(0, urgent.get(e.getKey()));
        StringBuilder sb = new StringBuilder(ARZ_HEAD);
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            sb.append("\n").append(e.getKey()).append(" tarihli:");
            for (String t : e.getValue()) sb.append("\n• ").append(t);
        }
        sb.append("\n").append(ARZ_TAIL);
        return sb.toString();
    }

    static String itemLine(JSONObject it) {
        StringBuilder sb = new StringBuilder();
        if ("acil".equals(it.optString("prio"))) sb.append("[ACİL] ");
        sb.append(it.optString("text"));
        String due = it.optString("due", "");
        if (due.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US); in.setTimeZone(TZ);
                long t = in.parse(due).getTime();
                Calendar today = Calendar.getInstance(TZ);
                boolean over = due.compareTo(dayKey(today)) < 0;
                sb.append(" (son tarih: ").append(fmtDate(t + 12 * 3600000L)).append(over ? ", süresi geçti" : "").append(")");
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    static int urgentCount(JSONObject st) {
        int n = 0; JSONArray items = st == null ? null : st.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject it = items.optJSONObject(i); if (it != null && "open".equals(it.optString("status")) && "acil".equals(it.optString("prio"))) n++; }
        return n;
    }

    static int openCount(JSONObject st) {
        int n = 0; JSONArray items = st == null ? null : st.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject it = items.optJSONObject(i); if (it != null && "open".equals(it.optString("status"))) n++; }
        return n;
    }

    // ---------- Zamanlama ----------
    /** Sonraki slot: {zaman(ms), tür(0=arz,1=not)} ya da null */
    static long[] nextSlot(JSONObject sched, long now) {
        for (int i = 0; i < 9; i++) {
            Calendar c = Calendar.getInstance(TZ);
            c.setTimeInMillis(now);
            c.add(Calendar.DAY_OF_MONTH, i);
            int wd = c.get(Calendar.DAY_OF_WEEK);
            String key = wd == Calendar.SUNDAY ? "su" : wd == Calendar.SATURDAY ? "sa" : "wd";
            JSONObject d = sched.optJSONObject(key);
            if (d == null || !d.optBoolean("on", false)) continue;
            long best = Long.MAX_VALUE; int type = -1;
            String[] types = {"arz", "not"};
            for (int t = 0; t < 2; t++) {
                String hm = d.optString(types[t], "");
                if (!hm.matches("\\d{1,2}:\\d{2}")) continue;
                String[] p = hm.split(":");
                Calendar s = (Calendar) c.clone();
                s.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
                s.set(Calendar.MINUTE, Integer.parseInt(p[1]));
                s.set(Calendar.SECOND, 0); s.set(Calendar.MILLISECOND, 0);
                long at = s.getTimeInMillis();
                if (at > now + 500 && at < best) { best = at; type = t; }
            }
            if (type >= 0) return new long[]{best, type};
        }
        return null;
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am.canScheduleExactAlarms();
    }

    static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.areNotificationsEnabled();
    }

    /** Var olan alarmı iptal edip bir sonrakini kurar. */
    static void reschedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, AlarmReceiver.class);
        JSONObject st = getState(c);
        long[] next = st == null ? null : nextSlot(schedule(st), System.currentTimeMillis());
        if (next == null) {
            PendingIntent old = PendingIntent.getBroadcast(c, 100, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
            if (old != null) { am.cancel(old); old.cancel(); }
            prefs(c).edit().remove("nextAt").remove("nextType").apply();
            return;
        }
        i.putExtra("type", next[1] == 0 ? "arz" : "not");
        i.putExtra("at", next[0]);
        PendingIntent pi = PendingIntent.getBroadcast(c, 100, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (canExact(c)) {
            // Doze modunda da tam saatinde çalışır
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[0], pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[0], pi);
        }
        prefs(c).edit().putLong("nextAt", next[0]).putString("nextType", next[1] == 0 ? "arz" : "not").apply();
    }

    /** Sıradaki arz (tür = arz) açıklaması: widget için */
    static String nextArzDescription(Context c) {
        JSONObject st = getState(c);
        if (st == null) return "–";
        long now = System.currentTimeMillis();
        for (int k = 0; k < 4; k++) {
            long[] n = nextSlot(schedule(st), now);
            if (n == null) return "Program kapalı";
            if (n[1] == 0) {
                SimpleDateFormat f = new SimpleDateFormat("EEE HH:mm", TR); f.setTimeZone(TZ);
                return f.format(new Date(n[0]));
            }
            now = n[0];
        }
        return "–";
    }

    /** Durum her değiştiğinde: alarmlar, hatırlatmalar, widget ve kalıcı bildirim güncellenir. */
    static void afterChange(Context c) {
        try { reschedule(c); } catch (Exception ignored) {}
        try { rescheduleReminders(c); } catch (Exception ignored) {}
        try { TuranWidget.updateAll(c); } catch (Exception ignored) {}
        try { updatePersistent(c, null); } catch (Exception ignored) {}
    }

    // ---------- Tek seferlik hatırlatmalar ----------
    static PendingIntent reminderIntent(Context c, String id, int flags) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction("rem:" + id).putExtra("type", "rem").putExtra("id", id);
        return PendingIntent.getBroadcast(c, 200, i, PendingIntent.FLAG_IMMUTABLE | flags);
    }

    static void rescheduleReminders(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        java.util.Set<String> old = prefs(c).getStringSet("remIds", new java.util.HashSet<String>());
        for (String id : old) {
            PendingIntent pi = reminderIntent(c, id, PendingIntent.FLAG_NO_CREATE);
            if (pi != null) { am.cancel(pi); pi.cancel(); }
        }
        java.util.HashSet<String> now = new java.util.HashSet<>();
        JSONObject st = getState(c);
        JSONArray items = st == null ? null : st.optJSONArray("items");
        long t0 = System.currentTimeMillis();
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || !"open".equals(it.optString("status")) || it.optBoolean("remindFired", false)) continue;
            String r = it.optString("remindAt", "");
            if (r.isEmpty() || "null".equals(r)) continue;
            long at = parseIso(r);
            if (at <= 0) continue;
            if (at < t0 - 6 * 3600000L) continue; // çok eskiyse atla
            if (at < t0) at = t0 + 2000;          // kaçırılmış: hemen göster
            String id = it.optString("id");
            PendingIntent pi = reminderIntent(c, id, PendingIntent.FLAG_UPDATE_CURRENT);
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            now.add(id);
        }
        prefs(c).edit().putStringSet("remIds", now).apply();
    }

    static JSONObject findItem(JSONObject st, String id) {
        JSONArray items = st == null ? null : st.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject it = items.optJSONObject(i); if (it != null && id.equals(it.optString("id"))) return it; }
        return null;
    }

    static int remNid(String id) { return 1000 + (id.hashCode() & 0x7fff); }

    static void showReminder(Context c, String id) {
        JSONObject it;
        synchronized (LOCK) {
            JSONObject st = getState(c);
            it = findItem(st, id);
            if (it == null || !"open".equals(it.optString("status"))) return;
            try { it.put("remindFired", true); setRaw(c, st.toString()); } catch (Exception ignored) {}
        }
        String line = itemLine(it);
        String body = "Emir eri Turan, tekmil veriyorum. Komutanım, hatırlatma: " + line + ". Arz ederim.";
        Notification.Builder b = builder(c)
                .setContentTitle(("acil".equals(it.optString("prio")) ? "ACİL – " : "") + "Turan Asistan – Hatırlatma")
                .setContentText(line)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openIntent(c, "open", 5));
        Intent done = new Intent(c, ActionReceiver.class).setAction("done:" + id).putExtra("op", "done").putExtra("id", id);
        Intent snooze = new Intent(c, ActionReceiver.class).setAction("snooze:" + id).putExtra("op", "snooze").putExtra("id", id);
        b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "✔ Tamamlandı",
                PendingIntent.getBroadcast(c, 6, done, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "10 dk ertele",
                PendingIntent.getBroadcast(c, 7, snooze, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        nm(c).notify(remNid(id), b.build());
    }

    // ---------- Kalıcı “hazır” bildirimi ----------
    @SuppressWarnings("deprecation")
    static void updatePersistent(Context c, String extra) {
        if (!prefs(c).getBoolean("persistent", false)) { nm(c).cancel(NID_PERSIST); return; }
        if (Build.VERSION.SDK_INT >= 26 && nm(c).getNotificationChannel(CHANNEL_QUIET) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_QUIET, "Hazır bekleme (kalıcı)", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false); nm(c).createNotificationChannel(ch);
        }
        JSONObject st = getState(c);
        int n = openCount(st), u = urgentCount(st);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL_QUIET) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat).setColor(0xFF3A4A28).setOngoing(true).setShowWhen(false)
                .setContentTitle("Turan Asistan emrinizde")
                .setContentText(extra != null ? extra : (n + " açık iş" + (u > 0 ? " · " + u + " acil" : "") + " · Sıradaki arz: " + nextArzDescription(c)))
                .setContentIntent(openIntent(c, "open", 8));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        RemoteInput ri = new RemoteInput.Builder(KEY_REPLY).setLabel("Not alınacak konu…").build();
        Intent ni = new Intent(c, NoteReceiver.class).putExtra("src", "persistent");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "✎ Not al",
                PendingIntent.getBroadcast(c, 9, ni, flags)).addRemoteInput(ri).build());
        b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "🎙 Sesli komut", openIntent(c, "voice", 10)).build());
        nm(c).notify(NID_PERSIST, b.build());
    }

    static String nextDescription(Context c) {
        long at = prefs(c).getLong("nextAt", 0);
        if (at == 0) return "Program kapalı";
        SimpleDateFormat f = new SimpleDateFormat("EEEE HH:mm", TR); f.setTimeZone(TZ);
        return f.format(new Date(at)) + " · " + ("arz".equals(prefs(c).getString("nextType", "")) ? "arz" : "not alma");
    }

    /** Uygulama içindeki “bugün gösterildi” kaydını işaretler (açılışta tekrar gösterilmesin). */
    static void markShown(Context c, String type, long at) {
        synchronized (LOCK) {
            JSONObject st = getState(c);
            if (st == null) return;
            try {
                JSONObject ls = st.optJSONObject("lastShown");
                if (ls == null) { ls = new JSONObject(); st.put("lastShown", ls); }
                Calendar cal = Calendar.getInstance(TZ); cal.setTimeInMillis(at);
                ls.put(type, dayKey(cal));
                setRaw(c, st.toString());
            } catch (Exception ignored) {}
        }
    }

    static boolean addItem(Context c, String text) {
        synchronized (LOCK) {
            JSONObject st = getState(c);
            if (st == null || text == null || text.trim().isEmpty()) return false;
            try {
                JSONArray items = st.optJSONArray("items");
                if (items == null) { items = new JSONArray(); st.put("items", items); }
                JSONObject it = new JSONObject();
                it.put("id", Long.toString(System.currentTimeMillis(), 36) + "n" + (int) (Math.random() * 1000));
                it.put("text", text.trim());
                it.put("created", isoNow());
                it.put("status", "open");
                it.put("closed", JSONObject.NULL);
                items.put(it);
                setRaw(c, st.toString());
                return true;
            } catch (Exception e) { return false; }
        }
    }

    // ---------- Bildirimler ----------
    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Tekmil (arz ve not alma)", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Turan Asistan'ın arz, not alma ve hatırlatma bildirimleri");
        ch.enableVibration(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ch);
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c) {
        ensureChannel(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat).setColor(0xFF2E3A23).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER).setShowWhen(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        return b;
    }

    static PendingIntent openIntent(Context c, String view, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.putExtra("view", view);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Arz bildirimi; açık iş yoksa false döner (gönderilmez). */
    static boolean showArz(Context c) {
        String txt = arzText(getState(c));
        if (txt.isEmpty()) return false;
        int n = openCount(getState(c));
        Notification.Builder b = builder(c)
                .setContentTitle("Turan Asistan – Arz (" + n + " açık iş)")
                .setContentText("Komutanım, müsaadenizle açık işleri arz ediyorum.")
                .setStyle(new Notification.BigTextStyle().bigText(txt))
                .setContentIntent(openIntent(c, "arz", 1));
        nm(c).notify(NID_ARZ, b.build());
        return true;
    }

    static void showNot(Context c, String extra) {
        Notification.Builder b = builder(c)
                .setContentTitle("Turan Asistan – Not alma")
                .setContentText(extra != null ? extra : NOT_TEXT)
                .setStyle(new Notification.BigTextStyle().bigText(extra != null ? extra : NOT_TEXT))
                .setContentIntent(openIntent(c, "not", 2));
        if (Build.VERSION.SDK_INT >= 24) {
            RemoteInput ri = new RemoteInput.Builder(KEY_REPLY).setLabel("Not alınacak konu…").build();
            Intent ni = new Intent(c, NoteReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, 3, ni, flags);
            Notification.Action act = new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Not al", pi).addRemoteInput(ri).build();
            b.addAction(act);
        }
        if (extra != null) b.setOnlyAlertOnce(true);
        nm(c).notify(NID_NOT, b.build());
    }

    static NotificationManager nm(Context c) { return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); }
}
