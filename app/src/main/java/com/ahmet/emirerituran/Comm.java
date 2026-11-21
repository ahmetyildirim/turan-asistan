package com.ahmet.emirerituran;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Rehber, arama, SMS ve WhatsApp yardımcıları. */
public class Comm {
    static final String[] WA_PKGS = {"com.whatsapp", "com.whatsapp.w4b"};

    static boolean has(Context c, String perm) { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    static String permOf(String name) {
        switch (name) {
            case "contacts": return android.Manifest.permission.READ_CONTACTS;
            case "call": return android.Manifest.permission.CALL_PHONE;
            case "sms": return android.Manifest.permission.SEND_SMS;
        }
        return null;
    }

    static JSONObject permState(Context c) throws Exception {
        return new JSONObject().put("contacts", has(c, permOf("contacts"))).put("call", has(c, permOf("call"))).put("sms", has(c, permOf("sms")))
                .put("telephony", c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
                .put("wa", waPackage(c) != null).put("waService", waServiceEnabled(c));
    }

    /** [{name, nums:[{n, t}]}] — t: mobile|home|work|other */
    static JSONArray contacts(Context c) throws Exception {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        String[] proj = {ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY};
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cur == null) return new JSONArray();
            while (cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), num = cur.getString(2);
                if (name == null || num == null) continue;
                JSONObject o = map.get(id);
                if (o == null) { o = new JSONObject().put("name", name).put("nums", new JSONArray()); map.put(id, o); }
                String clean = num.replaceAll("[^0-9+]", "");
                JSONArray nums = o.getJSONArray("nums");
                boolean dup = false;
                for (int i = 0; i < nums.length(); i++) if (tail(nums.getJSONObject(i).getString("n")).equals(tail(clean))) dup = true;
                if (dup) continue;
                int t = cur.getInt(3);
                String type = t == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE ? "mobile" : t == ContactsContract.CommonDataKinds.Phone.TYPE_HOME ? "home"
                        : t == ContactsContract.CommonDataKinds.Phone.TYPE_WORK ? "work" : "other";
                nums.put(new JSONObject().put("n", clean).put("t", type).put("p", cur.getInt(4) != 0));
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : map.values()) out.put(o);
        return out;
    }

    static String tail(String n) { String d = n.replaceAll("[^0-9]", ""); return d.length() > 10 ? d.substring(d.length() - 10) : d; }

    /** Türkiye varsayımıyla uluslararası biçim (WhatsApp için): 0532… → 90532…, 532… → 90532… */
    static String intl(String num) {
        String s = num.trim();
        boolean plus = s.startsWith("+");
        String d = s.replaceAll("[^0-9]", "");
        if (plus) return d;
        if (d.startsWith("00")) return d.substring(2);
        if (d.startsWith("0") && d.length() == 11) return "90" + d.substring(1);
        if (d.length() == 10 && d.startsWith("5")) return "90" + d;
        return d;
    }

    static String waUrl(String num, String text) {
        try {
            return "https://api.whatsapp.com/send?phone=" + intl(num) + "&text=" + URLEncoder.encode(text == null ? "" : text, "UTF-8").replace("+", "%20");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String waPackage(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String p : WA_PKGS) {
            try { pm.getPackageInfo(p, 0); return p; } catch (Exception ignored) {}
        }
        return null;
    }

    static Intent waIntent(Context c, String num, String text) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(waUrl(num, text)));
        String p = waPackage(c);
        if (p != null) i.setPackage(p);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static boolean waServiceEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(s)) return false;
        String me = c.getPackageName() + "/" + WaSendService.class.getName();
        String me2 = c.getPackageName() + "/.WaSendService";
        for (String x : s.split(":")) if (x.equalsIgnoreCase(me) || x.equalsIgnoreCase(me2)) return true;
        return false;
    }

    /** Erişilebilirlik gönderici yalnızca Turan sohbeti açtıktan sonraki kısa süre için devrededir. */
    static void armWa(Context c, String text) {
        Store.prefs(c).edit().putLong("waArmUntil", System.currentTimeMillis() + 20000).putString("waArmText", text).apply();
    }
    static void disarmWa(Context c) { Store.prefs(c).edit().remove("waArmUntil").remove("waArmText").apply(); }
}
