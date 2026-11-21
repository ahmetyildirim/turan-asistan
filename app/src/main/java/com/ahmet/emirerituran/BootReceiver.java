package com.ahmet.emirerituran;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Yeniden başlatma, güncelleme, saat/izin değişikliğinde alarmı yeniden kurar. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Store.afterChange(c);
        String a = intent == null ? null : intent.getAction();
        // Gizli Hat: açılışta yalnızca kullanıcı "açılışta çevrimiçi ol" dediyse; güncellemeden sonra ise
        // güncelleme öncesi çevrimiçiyse kaldığı yerden devam eder.
        boolean boot = "android.intent.action.BOOT_COMPLETED".equals(a), upd = "android.intent.action.MY_PACKAGE_REPLACED".equals(a);
        if ((upd || (boot && GizliStore.prefs(c).getBoolean("bootOnline", false)))
                && GizliStore.prefs(c).getBoolean("online", false) && GizliStore.exists(c)) {
            try { GizliService.start(c); } catch (Exception ignored) {}
        }
    }
}
