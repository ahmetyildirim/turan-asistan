# Turan Asistan

**Turan Asistan**, askerî üslupla konuşan (“Komutanım”, “Arz ederim”) kişisel bir Android asistanıdır: iş takibi, hatırlatma, belge/PDF arşivi tarama, sesli komut, arama/mesaj/WhatsApp kısayolları, X zaman tüneli özeti ve **v1.5.0** ile Tor üzerinden çalışan, numarasız ve hesapsız **Gizli Hat** iletişimi.

Arayüz tamamen Türkçedir. Uygulama WebView içinde çalışan bir web arayüzü (`web/`) ile yerel Android kodundan (`app/`) oluşur.

## İndir / kur

Resmî, imzalı APK'lar Google Drive klasöründedir:
**https://drive.google.com/drive/folders/12c3jsyDZ0yDZmKi7YlxpWyNSr-1-Sot3**

- `turan-asistan-X.Y.Z-arm64.apk` — güncel telefonlar (arm64) için küçük APK; uygulama içi güncelleyici bunu indirir.
- `turan-asistan-X.Y.Z.apk` — arm64 + armv7 (eski telefonlar dahil) tam APK.

Aynı APK'lar GitHub **Releases** sayfasında da yayınlanır. Kurulumda Android “Bilinmeyen uygulamaları yükle” iznini ister. En düşük Android sürümü: 7.0 (API 24).

## Sürümlere göre özellikler

### 1.5 — Gizli Hat (Tor)
- **Numarasız, e-postasız kimlik:** Telefonda üretilen Ed25519 anahtarından türetilen `0x…` kimlik (aynı anahtardan Tor v3 onion adresi). Kopyala / paylaş / QR; kişi eklemek için kimliği yapıştırın ya da QR okutun.
- **Yalnızca Tor:** Gömülü Tor (Guardian Project tor-android). Tüm Gizli Hat trafiği Tor'dan geçer; Tor yoksa bağlantı kurulmaz (açık internete geri dönüş yok). Her telefon kendi onion hizmetini yayınlar; sunucu yoktur.
- **Sohbette Tor röleleri:** Sağ üstte devre; dokununca röle takma adları, ülkeleri ve IP'leri. Varsayılan **10 dakikada bir** otomatik, istenirse **“Anlık değiştir”** ile devre değişimi.
- **Uçtan uca şifreleme:** Tor'un üstüne ayrıca X25519 + ChaCha20-Poly1305, her oturumda yeni anahtar (oturum bazlı ileri gizlilik); güvenlik numarası ile karşı tarafı doğrulama.
- İletildi/okundu bilgisi, çevrimdışı kuyruk (iki taraf aynı anda çevrimiçi olunca iletilir), kaybolan mesajlar, dosya/fotoğraf (fotoğraflarda EXIF/konum silinir).
- **Tor üzerinden sesli arama** (Opus; 1–3 sn gecikme olabilir). Görüntülü arama yoktur.
- PIN / biyometrik kilit, şifreli yerel veritabanı (Android Keystore), ekran görüntüsü engeli, panik silme, pano otomatik temizleme, öğrenmeyen klavye modu. Analitik, FCM ya da Google hizmeti kullanılmaz; rehbere erişmez.

### 1.4 — Arşiv tarama, sesli komut, WhatsApp, X özeti
- **Arşiv tarama:** PDF arşivleri oluşturun; taranmış sayfalar dahil (OCR) tüm sayfalar dizinlenir, sorulara sayfa numarasıyla kaynak gösterilerek cevap verilir.
- **Sesli komutlar:** iş ekleme/kapatma, hatırlatma, araştırma, “gizli hattı aç” vb.
- **Arama, SMS ve WhatsApp komutları:** “Mehmet'i ara”, “Ayşe'ye yola çıktığımı söyle”, “Ali'ye WhatsApp'tan … yaz”. (İsteğe bağlı erişilebilirlik hizmetiyle otomatik gönderim.)
- **X (Twitter) zaman tüneli özeti:** Uygulama içi pencerede kendi hesabınızla giriş; şifre uygulamaya verilmez/saklanmaz.

### 1.3 — Drive üzerinden güncelleme
- Uygulama, Drive'daki sabit kimlikli `turan-guncelleme.json` manifestini kontrol eder; yeni sürümü indirir ve **boyut, SHA-256, paket adı, sürüm ve imza sertifikası** tutarsa kurar.

### Temel özellikler
Takipteki işler (öncelik, son tarih, hatırlatma), fotoğraf/dosya ekleri, bildirimler ve günlük rapor, widget ve hızlı ayar kutucuğu, yedekleme, isteğe bağlı yapay zekâ ile belge analizi (kendi OpenAI uyumlu anahtarınızla).

## Gizlilik ve güvenlik notları

- Kayıtlar ve ekler yalnızca telefonda tutulur. Yapay zekâ analizi **yalnızca** siz bir API anahtarı girerseniz, analiz edilen belge için o servise gönderilir.
- Arşiv taramadaki OCR, telefonda çalışan Google ML Kit kütüphanesini kullanır (çevrimdışı). Gizli Hat bu kütüphaneyi kullanmaz.
- **Gizli Hat'ın sınırları (dürüstçe):**
  - Tor IP adresinizi gizler; **telefonun ele geçirilmesine (zararlı yazılım, kilitsiz cihaz) karşı koruma sağlamaz.** Telefon en zayıf halkadır.
  - Onion↔onion trafikte “çıkış IP'si” yoktur; görünen röleler yalnızca sizin taraftaki devredir.
  - Giriş (guard) rölesi Tor'un güvenlik tasarımı gereği uzun süre sabit kalır.
  - Kimliğinizi bilen biri ne zaman çevrimiçi olduğunuzu görebilir. Mesajlar ancak iki taraf aynı anda çevrimiçiyken iletilir.
  - Arka planda Tor pil tüketir; pil optimizasyonundan muaf tutmak gerekebilir.
  - Şifreleme Signal tarzı “double ratchet” değildir (oturum başına yeni anahtar kullanılır).
  - Bu uygulama **onaylı/akredite bir askerî haberleşme sistemi değildir.**
- Güvenlik açığı bildirmek için lütfen herkese açık issue yerine depo sahibine özel olarak ulaşın.

## Kaynaktan derleme

Gereksinimler: JDK 17, Android SDK (platform 34, build-tools 34.0.0).

```bash
git clone https://github.com/ahmetyildirim/turan-asistan.git
cd turan-asistan
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease                    # arm64 + armv7
./gradlew assembleRelease -Parm64 -Pslim     # yalnız arm64, R8 ile küçültülmüş
./gradlew assembleRelease -Pemu              # emülatör (x86_64)
```

Çıktı: `app/build/outputs/apk/release/`. Web arayüzü derleme sırasında `web/` klasöründen APK'ya kopyalanır.

### Kendi imza anahtarınız

İmza bilgileri depoda **yoktur**. Kendi anahtarınızı oluşturun:

```bash
keytool -genkeypair -v -keystore my-release.keystore -alias mykey -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # değerleri doldurun (bu dosya git'e girmez)
```

Ya da ortam değişkenleri: `TURAN_STORE_FILE`, `TURAN_STORE_PASSWORD`, `TURAN_KEY_ALIAS`, `TURAN_KEY_PASSWORD`. İmza bilgisi yoksa imzasız `app-release-unsigned.apk` üretilir.

> **Önemli:** Resmî sürümler depo sahibinin özel anahtarıyla imzalanır. Kendi derlediğiniz APK farklı bir anahtarla imzalanacağı için **resmî APK'nın üzerine güncelleme olarak kurulamaz** (önce resmî sürümü kaldırmanız gerekir; bu, telefondaki verileri siler). Uygulama içi güncelleyici de yalnızca resmî sertifikayla imzalı APK'ları kabul eder.

### Notlar
- `app/libs/tor-android-0.4.9.17-j17.aar`: Maven Central'daki Guardian Project tor-android 0.4.9.17 AAR'ının (imzası doğrulanmış) Java sınıf sürümü `tools/retarget-tor-aar.py` ile 61'e (Java 17) çekilmiş halidir; `libtor.so` değiştirilmemiştir.
- Sürüm çıkarma süreci: [RELEASE.md](RELEASE.md).

## Lisans

Bu proje **GNU General Public License v3.0** ile lisanslanmıştır — bkz. [LICENSE](LICENSE). Üçüncü taraf bileşenler ve GPL §7 ek izni için bkz. [NOTICE.md](NOTICE.md).
