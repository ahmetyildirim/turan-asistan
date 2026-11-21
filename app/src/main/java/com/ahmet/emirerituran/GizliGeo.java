package com.ahmet.emirerituran;

import android.content.Context;

import java.io.DataInputStream;
import java.io.InputStream;

/** Tor'un GeoIP veritabanından (IPFire Location) üretilmiş, çevrimdışı IPv4 → ülke tablosu. Ağ sorgusu yapılmaz. */
final class GizliGeo {
    private static int[] starts; private static short[] idx; private static String[] ccs;

    static synchronized String country(Context c, String ip) {
        try {
            if (starts == null) load(c);
            String[] p = ip.split("\\."); long v = 0; for (String x : p) v = (v << 8) | Integer.parseInt(x);
            int key = (int) (v ^ 0x80000000L); // işaretli karşılaştırma için kaydır
            int lo = 0, hi = starts.length - 1, ans = 0;
            while (lo <= hi) { int m = (lo + hi) >>> 1; if (starts[m] <= key) { ans = m; lo = m + 1; } else hi = m - 1; }
            return ccs[idx[ans] & 0xffff];
        } catch (Exception e) { return "??"; }
    }

    private static void load(Context c) throws Exception {
        try (InputStream raw = c.getAssets().open("gizli_geo.bin"); DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 65536))) {
            byte[] mg = new byte[4]; in.readFully(mg);
            int nc = in.readUnsignedShort(); int n = in.readInt();
            byte[] cb = new byte[nc * 2]; in.readFully(cb);
            String[] cc = new String[nc]; for (int i = 0; i < nc; i++) cc[i] = new String(cb, 2 * i, 2, "US-ASCII");
            short[] ix = new short[n]; for (int i = 0; i < n; i++) ix[i] = in.readShort();
            int[] st = new int[n]; long prev = 0;
            for (int i = 0; i < n; i++) {
                long d = 0; int sh = 0, b;
                do { b = in.readUnsignedByte(); d |= (long) (b & 0x7f) << sh; sh += 7; } while ((b & 0x80) != 0);
                prev += d; st[i] = (int) (prev ^ 0x80000000L);
            }
            ccs = cc; idx = ix; starts = st;
        }
    }
}
