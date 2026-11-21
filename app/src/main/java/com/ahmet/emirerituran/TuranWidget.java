package com.ahmet.emirerituran;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** Ana ekran widget'ı: açık iş sayısı, sıradaki arz, Not al / Sesli / Arz düğmeleri. */
public class TuranWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { update(c, m, ids); }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        int[] ids = m.getAppWidgetIds(new ComponentName(c, TuranWidget.class));
        if (ids != null && ids.length > 0) update(c, m, ids);
    }

    static PendingIntent open(Context c, String view, int req) {
        Intent i = new Intent(c, MainActivity.class).putExtra("view", view)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 300 + req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void update(Context c, AppWidgetManager m, int[] ids) {
        JSONObject st = Store.getState(c);
        int n = Store.openCount(st), u = Store.urgentCount(st);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.w_count, n + " açık iş" + (u > 0 ? " · " + u + " acil" : ""));
        v.setTextViewText(R.id.w_next, "Sıradaki arz: " + Store.nextArzDescription(c));
        v.setOnClickPendingIntent(R.id.w_root, open(c, "open", 0));
        v.setOnClickPendingIntent(R.id.w_not, open(c, "not", 1));
        v.setOnClickPendingIntent(R.id.w_voice, open(c, "voice", 2));
        v.setOnClickPendingIntent(R.id.w_arz, open(c, "arz", 3));
        m.updateAppWidget(ids, v);
    }
}
