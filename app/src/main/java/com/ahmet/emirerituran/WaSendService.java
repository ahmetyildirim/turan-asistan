package com.ahmet.emirerituran;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * "Turan WhatsApp gönderici" — isteğe bağlı erişilebilirlik hizmeti.
 * Yalnızca Turan onaylanmış bir komutla WhatsApp sohbetini açtıktan sonraki ~20 saniye içinde,
 * mesaj kutusundaki metin Turan'ın hazırladığı metinle aynıysa "Gönder" düğmesine bir kez dokunur.
 * Başka hiçbir durumda ekrana dokunmaz, hiçbir içerik okumaz/saklamaz.
 */
public class WaSendService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (!pkg.equals("com.whatsapp") && !pkg.equals("com.whatsapp.w4b")) return;
        SharedPreferences p = Store.prefs(this);
        long until = p.getLong("waArmUntil", 0);
        if (until == 0) return;
        if (System.currentTimeMillis() > until) { Comm.disarmWa(this); return; }
        String expected = p.getString("waArmText", "");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            AccessibilityNodeInfo entry = first(root.findAccessibilityNodeInfosByViewId(pkg + ":id/entry"));
            if (entry == null || entry.getText() == null) return;
            if (!norm(entry.getText().toString()).equals(norm(expected))) return;
            AccessibilityNodeInfo send = first(root.findAccessibilityNodeInfosByViewId(pkg + ":id/send"));
            if (send == null) {
                for (String d : new String[]{"Gönder", "Send"}) {
                    for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(d)) {
                        if (n.getContentDescription() != null && n.getContentDescription().toString().equalsIgnoreCase(d) && n.isClickable()) { send = n; break; }
                    }
                    if (send != null) break;
                }
            }
            if (send == null) return;
            Comm.disarmWa(this); // tek sefer
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            android.util.Log.i("Turan", "WhatsApp gönder düğmesine dokunuldu");
        } catch (Exception ex) {
            android.util.Log.w("Turan", "wa send", ex);
        }
    }

    static AccessibilityNodeInfo first(List<AccessibilityNodeInfo> l) { return l == null || l.isEmpty() ? null : l.get(0); }
    static String norm(String s) { return s.replaceAll("\\s+", " ").trim(); }

    @Override public void onInterrupt() {}
}
