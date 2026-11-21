package com.ahmet.emirerituran;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Hatırlatma bildirimindeki “Tamamlandı” ve “10 dk ertele” düğmeleri. */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String id = intent.getStringExtra("id"), op = intent.getStringExtra("op");
        if (id == null || op == null) return;
        synchronized (Store.LOCK) {
            JSONObject st = Store.getState(c);
            JSONObject it = Store.findItem(st, id);
            if (it == null) return;
            try {
                if ("done".equals(op)) {
                    it.put("status", "done"); it.put("closed", Store.isoNow());
                } else if ("snooze".equals(op)) {
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                    f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    it.put("remindAt", f.format(new java.util.Date(System.currentTimeMillis() + 10 * 60000L)));
                    it.put("remindFired", false);
                }
                Store.setRaw(c, st.toString());
            } catch (Exception ignored) {}
        }
        Store.nm(c).cancel(Store.remNid(id));
        Store.afterChange(c);
        MainActivity.notifyStateChanged();
        android.widget.Toast.makeText(c, "done".equals(op) ? "Emredersiniz komutanım, tamamlandı." : "Emredersiniz komutanım, 10 dakika sonra hatırlatacağım.", android.widget.Toast.LENGTH_SHORT).show();
    }
}
