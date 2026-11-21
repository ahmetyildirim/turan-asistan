package com.ahmet.emirerituran;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Gizli Hat kriptografisi (BouncyCastle, Google hizmeti yok):
 *  - Kimlik: Ed25519. Kimlik açık anahtarı aynı zamanda Tor v3 onion hizmetinin anahtarıdır,
 *    yani "0x…" kimliği doğrudan kişinin .onion adresine dönüşür (sunucu/hesap yok).
 *  - Oturum: her bağlantıda yeni (geçici) X25519 anahtarları, Ed25519 imzalı el sıkışma,
 *    HKDF-SHA256 → yön başına ChaCha20-Poly1305 anahtarları (oturum başına ileri gizlilik).
 */
final class GizliCrypto {
    static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567";

    private GizliCrypto() {}

    static byte[] rand(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }

    // ---------- Ed25519 ----------
    static byte[] newSeed() { return rand(32); }
    static byte[] edPublic(byte[] seed) { byte[] pk = new byte[32]; Ed25519.generatePublicKey(seed, 0, pk, 0); return pk; }
    static byte[] sign(byte[] seed, byte[] msg) { byte[] sig = new byte[64]; Ed25519.sign(seed, 0, msg, 0, msg.length, sig, 0); return sig; }
    static boolean verify(byte[] pub, byte[] msg, byte[] sig) {
        try { return sig != null && sig.length == 64 && pub != null && pub.length == 32 && Ed25519.verify(sig, 0, pub, 0, msg, 0, msg.length); }
        catch (Exception e) { return false; }
    }

    /** Tor ADD_ONION için "genişletilmiş" gizli anahtar (SHA-512(seed), kenetlenmiş). */
    static byte[] torExpandedKey(byte[] seed) {
        SHA512Digest d = new SHA512Digest(); d.update(seed, 0, 32); byte[] h = new byte[64]; d.doFinal(h, 0);
        h[0] &= (byte) 248; h[31] &= 127; h[31] |= 64; return h;
    }

    // ---------- X25519 ----------
    static byte[][] xKeyPair() { byte[] k = new byte[32]; X25519.generatePrivateKey(RNG, k); byte[] p = new byte[32]; X25519.generatePublicKey(k, 0, p, 0); return new byte[][]{k, p}; }
    static byte[] xAgree(byte[] priv, byte[] peerPub) {
        byte[] out = new byte[32];
        if (!X25519.calculateAgreement(priv, 0, peerPub, 0, out, 0)) throw new SecurityException("geçersiz anahtar");
        return out;
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) {
        HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest()); g.init(new HKDFParameters(ikm, salt, info));
        byte[] o = new byte[len]; g.generateBytes(o, 0, len); return o;
    }

    static byte[] sha256(byte[]... parts) {
        try { MessageDigest m = MessageDigest.getInstance("SHA-256"); for (byte[] p : parts) m.update(p); return m.digest(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    static byte[] sha512(byte[]... parts) {
        SHA512Digest d = new SHA512Digest(); for (byte[] p : parts) d.update(p, 0, p.length); byte[] h = new byte[64]; d.doFinal(h, 0); return h;
    }

    /** Yön başına AEAD; nonce = 4 sıfır + 8 bayt sayaç (her anahtar yalnız bir oturumda kullanılır). */
    static final class Aead {
        private final byte[] key; private long ctr = 0;
        Aead(byte[] key) { this.key = key; }
        private byte[] nonce() { byte[] n = new byte[12]; long c = ctr++; for (int i = 0; i < 8; i++) n[4 + i] = (byte) (c >>> (8 * i)); return n; }
        synchronized byte[] seal(byte[] pt) {
            ChaCha20Poly1305 c = new ChaCha20Poly1305(); c.init(true, new AEADParameters(new KeyParameter(key), 128, nonce()));
            byte[] out = new byte[c.getOutputSize(pt.length)]; int n = c.processBytes(pt, 0, pt.length, out, 0);
            try { c.doFinal(out, n); } catch (InvalidCipherTextException e) { throw new RuntimeException(e); }
            return out;
        }
        synchronized byte[] open(byte[] ct) throws InvalidCipherTextException {
            ChaCha20Poly1305 c = new ChaCha20Poly1305(); c.init(false, new AEADParameters(new KeyParameter(key), 128, nonce()));
            byte[] out = new byte[c.getOutputSize(ct.length)]; int n = c.processBytes(ct, 0, ct.length, out, 0); c.doFinal(out, n); return out;
        }
    }

    // ---------- 0x kimliği / onion ----------
    static byte[] onionChecksum(byte[] pub) {
        SHA3Digest d = new SHA3Digest(256); byte[] pre = ".onion checksum".getBytes(StandardCharsets.US_ASCII);
        d.update(pre, 0, pre.length); d.update(pub, 0, 32); d.update((byte) 3); byte[] h = new byte[32]; d.doFinal(h, 0);
        return new byte[]{h[0], h[1]};
    }

    /** 0x + 64 hane açık anahtar + 4 hane sağlama = 70 karakter. Yazım hatasını sağlama yakalar. */
    static String idOf(byte[] pub) { return "0x" + hex(pub) + hex(onionChecksum(pub)); }

    static String shortId(byte[] pub) { String id = idOf(pub); return id.substring(0, 8) + "…" + id.substring(id.length() - 6); }

    /** Tor v3 onion adresi (".onion" olmadan, 56 karakter). */
    static String onionOf(byte[] pub) {
        byte[] all = new byte[35]; System.arraycopy(pub, 0, all, 0, 32); byte[] ck = onionChecksum(pub); all[32] = ck[0]; all[33] = ck[1]; all[34] = 3;
        return base32(all);
    }

    /** Yapıştırılan metinden açık anahtarı çıkarır: 0x kimliği veya .onion adresi. Geçersizse null. */
    static byte[] parseId(String s) {
        if (s == null) return null;
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_:·.…]", "");
        int i = t.indexOf("0x");
        if (i >= 0) {
            String h = t.substring(i + 2);
            StringBuilder hb = new StringBuilder();
            for (char ch : h.toCharArray()) { if (Character.digit(ch, 16) >= 0) hb.append(ch); else break; }
            if (hb.length() < 68) return null;
            byte[] all = unhex(hb.substring(0, 68)); byte[] pub = new byte[32]; System.arraycopy(all, 0, pub, 0, 32);
            byte[] ck = onionChecksum(pub);
            return (ck[0] == all[32] && ck[1] == all[33]) ? pub : null;
        }
        t = t.replace("onion", "").replaceAll("^https?//", "").replaceAll("^gizlihat", "").replace("/", "");
        if (t.length() == 56) {
            byte[] all = unbase32(t); if (all == null || all.length != 35 || all[34] != 3) return null;
            byte[] pub = new byte[32]; System.arraycopy(all, 0, pub, 0, 32); byte[] ck = onionChecksum(pub);
            return (ck[0] == all[32] && ck[1] == all[33]) ? pub : null;
        }
        return null;
    }

    /** Güvenlik numarası: iki kimlikten (sıra bağımsız) 10 × 5 hane. İki tarafta aynı çıkar. */
    static String safetyNumber(byte[] a, byte[] b) {
        byte[] lo = a, hi = b;
        for (int i = 0; i < 32; i++) { int x = a[i] & 255, y = b[i] & 255; if (x != y) { if (x > y) { lo = b; hi = a; } break; } }
        byte[] h = sha512("gizli-hat-guvenlik-v1".getBytes(StandardCharsets.US_ASCII), lo, hi);
        for (int r = 0; r < 1000; r++) h = sha512(h, lo, hi);
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 10; g++) {
            long v = 0; for (int k = 0; k < 5; k++) v = (v << 8) | (h[g * 5 + k] & 255);
            if (g > 0) sb.append(g % 5 == 0 ? "\n" : " ");
            sb.append(String.format(Locale.ROOT, "%05d", v % 100000));
        }
        return sb.toString();
    }

    static String fingerprint(byte[] pub) {
        String h = hex(sha256("gizli-hat-fp".getBytes(StandardCharsets.US_ASCII), pub)).substring(0, 32).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length(); i += 4) { if (i > 0) sb.append(' '); sb.append(h, i, i + 4); }
        return sb.toString();
    }

    // ---------- yardımcılar ----------
    static String hex(byte[] b) { char[] c = new char[b.length * 2]; for (int i = 0; i < b.length; i++) { c[2 * i] = HEX[(b[i] >> 4) & 15]; c[2 * i + 1] = HEX[b[i] & 15]; } return new String(c); }
    static byte[] unhex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
    static String base32(byte[] in) {
        StringBuilder sb = new StringBuilder(); int buf = 0, bits = 0;
        for (byte x : in) { buf = (buf << 8) | (x & 255); bits += 8; while (bits >= 5) { sb.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; } }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }
    static byte[] unbase32(String s) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); int buf = 0, bits = 0;
        for (char ch : s.toCharArray()) { int v = B32.indexOf(ch); if (v < 0) return null; buf = (buf << 5) | v; bits += 5; if (bits >= 8) { o.write((buf >> (bits - 8)) & 255); bits -= 8; } }
        return o.toByteArray();
    }
    static boolean eq(byte[] a, byte[] b) { return a != null && b != null && MessageDigest.isEqual(a, b); }
    static byte[] cat(byte[]... parts) { int n = 0; for (byte[] p : parts) n += p.length; byte[] o = new byte[n]; int k = 0; for (byte[] p : parts) { System.arraycopy(p, 0, o, k, p.length); k += p.length; } return o; }
    static void wipe(byte[] b) { if (b != null) java.util.Arrays.fill(b, (byte) 0); }
}
