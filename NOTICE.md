# Üçüncü taraf bileşenler ve lisans notları

Turan Asistan — Telif hakkı © 2026 Ahmet Yıldırım. GNU GPL v3.0 (bkz. LICENSE).

## GPL v3 §7 ek izni (Additional permission under GNU GPL version 3 section 7)

If you modify this Program, or any covered work, by linking or combining it with the Google ML Kit Text Recognition library (`com.google.mlkit:text-recognition` and its Google Play services / Firebase component dependencies), containing parts covered by the terms of the Google APIs / ML Kit Terms of Service, the licensors of this Program grant you additional permission to convey the resulting work. Corresponding Source for a non-source form of such a combination shall not include the source code for the parts of that library used as well as that of the covered work.

(Türkçe özet: ML Kit kapalı kaynaklı bir Google kütüphanesidir; bu ek izin, onunla birlikte dağıtılan APK'ların GPL v3 kapsamında yayınlanabilmesini sağlar. ML Kit yalnızca Arşiv tarama OCR'ı için kullanılır, Gizli Hat'ta kullanılmaz.)

## Bileşenler

| Bileşen | Lisans |
|---|---|
| Tor (0.4.9.17) ve Guardian Project tor-android | BSD 3-Clause (Tor içinde OpenSSL — Apache 2.0, libevent — BSD, zlib vb.) |
| Guardian Project jtorctl | BSD 3-Clause |
| ZXing core | Apache 2.0 |
| PdfBox-Android (Tom Roush) | Apache 2.0 |
| Bouncy Castle (PdfBox bağımlılığı, kriptografi) | MIT benzeri Bouncy Castle lisansı |
| AndroidX (core, work, localbroadcastmanager) ve Kotlin standart kütüphanesi | Apache 2.0 |
| Google ML Kit Text Recognition | Kapalı kaynak, Google APIs / ML Kit hizmet şartları (yukarıdaki §7 ek izni) |
| `app/src/main/assets/gizli_geo.bin` (IP → ülke) | Tor'un `geoip` dosyasından türetildi; kaynağı IPFire Location veritabanı — **CC BY-SA 4.0** (© IPFire Development Team). Bu türetilmiş veri dosyası da CC BY-SA 4.0 kapsamındadır. |
