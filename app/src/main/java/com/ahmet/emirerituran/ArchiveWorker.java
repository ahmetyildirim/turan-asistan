package com.ahmet.emirerituran;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;

/**
 * Arka planda PDF işleme: sayfa sayfa metin çıkarır (PdfBox, geçici dosya belleğiyle; büyük PDF'lerde bellek taşmaz),
 * metni olmayan sayfaları PdfRenderer + ML Kit OCR ile okur, her sayfayı FTS'ye yazar. Kesilirse kaldığı sayfadan devam eder.
 */
public class ArchiveWorker extends Worker {
    static final String CH = "turan_arsiv";
    static final int NID = 77;

    public ArchiveWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    static void start(Context c) {
        WorkManager.getInstance(c).enqueueUniqueWork("arsiv-isleme", ExistingWorkPolicy.APPEND_OR_REPLACE,
                new OneTimeWorkRequest.Builder(ArchiveWorker.class).build());
    }

    @NonNull @Override
    public Result doWork() {
        Context c = getApplicationContext();
        Archive ar = Archive.get(c);
        PDFBoxResourceLoader.init(c);
        long id;
        while (!isStopped() && (id = ar.nextQueued()) > 0) {
            try { process(c, ar, id); }
            catch (Throwable e) {
                if (isStopped()) return Result.retry();
                ContentValues v = new ContentValues(); v.put("status", "error"); v.put("error", String.valueOf(e.getMessage())); ar.setDoc(id, v);
            }
            MainActivity.notifyArchive();
        }
        if (isStopped()) return Result.retry();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NID);
        return Result.success();
    }

    private void process(Context c, Archive ar, long id) throws Exception {
        ContentValues row = ar.docRow(id);
        if (row == null) return;
        long coll = row.getAsLong("coll");
        String name = row.getAsString("name");
        File f = new File(Archive.dir(c), row.getAsString("file"));
        int startPage = row.getAsInteger("done") + 1;
        int ocrCount = row.getAsInteger("ocr"); long chars = row.getAsLong("chars");
        ar.dropPagesFrom(id, startPage);
        ContentValues v = new ContentValues(); v.put("status", "running"); ar.setDoc(id, v);
        try (PDDocument doc = PDDocument.load(f, "", MemoryUsageSetting.setupTempFileOnly())) {
            int pages = doc.getNumberOfPages();
            v = new ContentValues(); v.put("pages", pages); ar.setDoc(id, v);
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            ParcelFileDescriptor pfd = null; PdfRenderer pr = null;
            try {
                for (int p = startPage; p <= pages; p++) {
                    if (isStopped()) throw new InterruptedException("durduruldu");
                    if (ar.docRow(id) == null) return; // silindi
                    st.setStartPage(p); st.setEndPage(p);
                    String text;
                    try { text = st.getText(doc); } catch (Throwable e) { text = ""; }
                    if (text.trim().length() < 25) {
                        // Taranmış sayfa: görüntüye çevir ve OCR
                        try {
                            if (pr == null) { pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); pr = new PdfRenderer(pfd); }
                            String o = ocrPage(pr, p - 1);
                            if (o.trim().length() > text.trim().length()) { text = o; ocrCount++; }
                        } catch (Throwable e) { android.util.Log.w("Turan", "ocr page " + p, e); }
                    }
                    ar.addPage(coll, id, p, text);
                    chars += text.length();
                    v = new ContentValues(); v.put("done", p); v.put("ocr", ocrCount); v.put("chars", chars); ar.setDoc(id, v);
                    if (p == startPage || p % 5 == 0 || p == pages) { progress(c, name, p, pages); MainActivity.notifyArchive(); }
                }
            } finally {
                if (pr != null) try { pr.close(); } catch (Exception ignored) {}
                if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
            }
        }
        v = new ContentValues(); v.put("status", "ok"); v.put("error", ""); ar.setDoc(id, v);
    }

    static String ocrPage(PdfRenderer pr, int index) throws Exception {
        try (PdfRenderer.Page pg = pr.openPage(index)) {
            int w = 1600; float scale = (float) w / pg.getWidth();
            int h = Math.max(1, Math.min(4000, (int) (pg.getHeight() * scale)));
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            try {
                new Canvas(bm).drawColor(Color.WHITE);
                pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return Files.ocr(bm);
            } finally { bm.recycle(); }
        }
    }

    private void progress(Context c, String name, int p, int total) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Arşiv işleme", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false); nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        Notification n = b.setSmallIcon(R.drawable.ic_stat).setColor(0xFF3A4A28).setOngoing(true).setOnlyAlertOnce(true)
                .setContentTitle("Turan Asistan – Arşiv taranıyor")
                .setContentText(name + " · " + p + "/" + total + " sayfa")
                .setProgress(total, p, false)
                .setContentIntent(Store.openIntent(c, "arsiv", 30)).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) setForegroundAsync(new ForegroundInfo(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC));
            else setForegroundAsync(new ForegroundInfo(NID, n));
        } catch (Throwable e) {
            nm.notify(NID, n);
        }
    }
}
