package com.ahmet.emirerituran;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Gizli Hat çevrimiçiyken çalışan ön plan hizmeti (FCM/Google bildirimi kullanılmaz).
 * Bildirimde mesaj içeriği ya da kişi adı gösterilmez.
 */
public class GizliService extends Service {
    static final String CH_SVC = "gizli_hat", CH_MSG = "gizli_mesaj", CH_CALL = "gizli_arama";
    static final int NOTE_SVC = 95, NOTE_MSG = 96, NOTE_CALL = 97;
    static final String ACT_START = "gizli.start", ACT_STOP = "gizli.stop", ACT_UPDATE = "gizli.update", ACT_CALL_ON = "gizli.call1", ACT_CALL_OFF = "gizli.call0";
    private static volatile boolean running = false;
    private boolean callMode = false;

    static boolean isRunning() { return running; }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm == null) return;
        NotificationChannel a = new NotificationChannel(CH_SVC, "Gizli Hat (çevrimiçi)", NotificationManager.IMPORTANCE_LOW);
        a.setLockscreenVisibility(Notification.VISIBILITY_SECRET); a.setShowBadge(false); nm.createNotificationChannel(a);
        NotificationChannel m = new NotificationChannel(CH_MSG, "Gizli Hat mesajları", NotificationManager.IMPORTANCE_DEFAULT);
        m.setLockscreenVisibility(Notification.VISIBILITY_SECRET); nm.createNotificationChannel(m);
        NotificationChannel k = new NotificationChannel(CH_CALL, "Gizli Hat aramaları", NotificationManager.IMPORTANCE_HIGH);
        k.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE); k.setSound(null, null); nm.createNotificationChannel(k);
    }

    static void start(Context c) {
        GizliStore.prefs(c).edit().putBoolean("online", true).apply();
        Intent i = new Intent(c, GizliService.class).setAction(ACT_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void stop(Context c) {
        GizliStore.prefs(c).edit().putBoolean("online", false).apply();
        if (running) c.startService(new Intent(c, GizliService.class).setAction(ACT_STOP));
        else GizliNet.get(c).stop();
    }
    static void update(Context c) { if (running) try { c.startService(new Intent(c, GizliService.class).setAction(ACT_UPDATE)); } catch (Exception ignored) {} }
    static void callMode(Context c, boolean on) { if (running) try { c.startService(new Intent(c, GizliService.class).setAction(on ? ACT_CALL_ON : ACT_CALL_OFF)); } catch (Exception ignored) {} }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null && intent.getAction() != null ? intent.getAction() : ACT_START;
        if (ACT_STOP.equals(a)) {
            GizliNet.get(this).stop(); running = false;
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf(); return START_NOT_STICKY;
        }
        if (ACT_CALL_ON.equals(a)) callMode = true; else if (ACT_CALL_OFF.equals(a)) callMode = false;
        if (!GizliStore.prefs(this).getBoolean("online", false) || !GizliStore.exists(this)) { stopSelf(); return START_NOT_STICKY; }
        channels(this);
        foreground();
        running = true;
        if (ACT_START.equals(a)) GizliNet.get(this).start();
        return START_STICKY;
    }

    private void foreground() {
        GizliTor t = GizliTor.get(this);
        Intent open = new Intent(this, GizliActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 93, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, 94, new Intent(this, GizliService.class).setAction(ACT_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CH_SVC).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(callMode ? "Gizli Hat · görüşme sürüyor" : "Gizli Hat çevrimiçi")
                .setContentText(t.published ? "Tor onion hizmeti yayında" : t.phase)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .addAction(0, "Çevrimdışı ol", off).build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
                if (callMode && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                try { startForeground(NOTE_SVC, n, type); } catch (Exception e) { startForeground(NOTE_SVC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING); }
            } else if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (callMode && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                try { startForeground(NOTE_SVC, n, type); } catch (Exception e) { startForeground(NOTE_SVC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); }
            } else startForeground(NOTE_SVC, n);
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() { running = false; super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
