package com.ahmet.emirerituran;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Arz / not alma saati geldiğinde çalışır, bildirimi gösterir ve sonraki alarmı kurar. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String type = intent.getStringExtra("type");
        if ("rem".equals(type)) {
            Store.showReminder(c, intent.getStringExtra("id"));
            Store.afterChange(c);
            MainActivity.notifyStateChanged();
            return;
        }
        long at = intent.getLongExtra("at", System.currentTimeMillis());
        // Çok geç teslim edilen (ör. telefon kapalıydı) alarmlar 6 saatten eskiyse gösterilmez; uygulama açılışta telafi eder
        boolean fresh = System.currentTimeMillis() - at < 6 * 3600 * 1000L;
        if (fresh) {
            if ("arz".equals(type)) Store.showArz(c);
            else if ("not".equals(type)) Store.showNot(c, null);
            if (type != null) Store.markShown(c, type, at);
        }
        Store.afterChange(c);
        MainActivity.notifyStateChanged();
    }
}
