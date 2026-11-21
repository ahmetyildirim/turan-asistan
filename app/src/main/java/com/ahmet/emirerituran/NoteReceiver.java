package com.ahmet.emirerituran;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Not alma bildirimindeki “Not al” yanıtını listeye ekler. */
public class NoteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Bundle r = RemoteInput.getResultsFromIntent(intent);
        CharSequence cs = r == null ? null : r.getCharSequence(Store.KEY_REPLY);
        String text = cs == null ? "" : cs.toString().trim();
        if (text.isEmpty()) return;
        if (Store.addItem(c, text)) {
            if ("persistent".equals(intent.getStringExtra("src"))) {
                Store.prefs(c); Store.afterChange(c);
                Store.updatePersistent(c, "Emredersiniz komutanım. Not alındı: " + text);
            } else {
                Store.afterChange(c);
                Store.showNot(c, "Emredersiniz komutanım. Not alındı: " + text + "\nBaşka konu varsa yine “Not al”a basabilirsiniz.");
            }
            MainActivity.notifyStateChanged();
        }
    }
}
