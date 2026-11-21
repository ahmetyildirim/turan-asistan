# Turan Asistan – sürüm çıkarma (release) süreci

Uygulama, Google Drive'daki **`turan-guncelleme.json`** dosyasını okuyarak güncelleme arar.
Bu dosyanın kimliği uygulamaya gömülüdür ve **asla değişmemelidir**:

| Ne | Drive kimliği |
|---|---|
| Klasör “Turan Asistan Güncelleme” | `12c3jsyDZ0yDZmKi7YlxpWyNSr-1-Sot3` |
| Manifest `turan-guncelleme.json` (SABİT) | `1UwPuURLi9EZehR3Tya0xM3nF6BNi0V86` |
| v1.5.0 arm64 APK (güncelleyicinin indirdiği – güncel) | `1hsVEfQB6gPJjxXp73BKuhh3AfSPM0Nhz` |
| v1.5.0 tam ARM APK (elle kurulum için) | `1bgHSG2WaL7yDcZjf4p_fw9_2yIBmNtvM` |
| v1.5.0 Linux AppImage (x86_64, manifeste GİRMEZ) | `1b1hILP9dzYesoBnu5eOLrTaVwyam8C6E` |
| v1.4.0 arm64 / tam APK (eski) | `1Ma99F6vicQAyAYJKACnj15KVeXdbCawX` / `1i7-_A-MuQfAEYFocWkhlaHzHQqplbi3J` |
| v1.3.0 arm64 / tam APK (eski) | `1ObeNskON1yng_3JDncn5gdHx8Hsp_wH_` / `17fiL82lUqWBRLpCaF6xJKUQH15hD0ThK` |

Uygulamadaki adres (app/build.gradle → `UPDATE_MANIFEST_URL`):
`https://drive.usercontent.google.com/download?id=1UwPuURLi9EZehR3Tya0xM3nF6BNi0V86&export=download&confirm=t`

## Bir kerelik ön koşul
Drive'da **“Turan Asistan Güncelleme” klasörü** → Paylaş → Genel erişim: **Bağlantıya sahip olan herkes – Görüntüleyen**.
Klasöre sonradan eklenen her dosya bu erişimi devralır; her sürümde ayrıca paylaşmak gerekmez.
(Grok Bot'un Drive bağlayıcısı “bağlantıya sahip herkes” paylaşımı yapamıyor; bu adımı Ahmet Drive'dan elle yapar.)

## Manifest biçimi
```json
{ "versionCode": 5, "versionName": "1.3.0",
  "apkUrl": "https://drive.usercontent.google.com/download?id=<APK_ID>&export=download&confirm=t",
  "size": 11214005, "sha256": "<apk sha256>", "notes": "• Yenilik 1\n• Yenilik 2" }
```
- Uygulama `versionCode` kendi sürümünden büyükse onay sorar, APK'yı indirir; **boyut, SHA-256, paket adı, sürüm ve imza sertifikası** tutmazsa kurmaz.
- `apkUrl` herhangi bir https adresi olabilir; varsayılan olarak aynı klasördeki **arm64 (küçük) APK**.

## Yeni sürüm adımları (HER sürümde hepsi)
Ön koşul: `/workspace/turan-android` içinde imza bilgisi `keystore.properties` dosyasında (git'e girmez; örnek: `keystore.properties.example`) ya da `TURAN_*` ortam değişkenlerinde. Anahtar dosyası `turan-release.keystore` da git'e girmez. **Anahtar dosyası kaybolursa güncelleme zinciri kırılır**; yedeğini sakla, parolasını kimseyle paylaşma, hiçbir dosyaya/rapora yazma.

1. Kodu değiştir. Web arayüzü kaynağı `/workspace/emir-eri-turan`; `tools/sync-web.sh` bunu depodaki `web/` klasörüne eşitler (xmock.html ve ekran görüntüleri hariç).
2. Derle + doğrula (sürüm numarasını build.gradle'da da günceller, web'i eşitler, iki APK'yı üretir, sürüm ve sertifikayı kontrol eder):
   ```bash
   tools/release.sh X.Y.Z <versionCode>     # → /workspace/turan-asistan-X.Y.Z.apk ve -arm64.apk
   ```
   Aynı komut sonunda `tools/build-desktop.sh X.Y.Z` ile **Linux AppImage**'ı da üretir (→ `/workspace/TuranAsistan-X.Y.Z-x86_64.AppImage`, sürüm `desktop/package.json` + `preload.js`'te eşitlenir) ve xvfb ile duman testi yapıp `/workspace/turan-linux-screenshot.png` alır. Yalnız masaüstü: `tools/build-desktop.sh X.Y.Z`; atlamak için `DESKTOP=0`.
   (Elle: `./gradlew assembleRelease` ve `./gradlew assembleRelease -Parm64 -Pslim`; kontrol `aapt dump badging` ve `apksigner verify --print-certs` → sertifika `1b04bcc7…b432`.)
3. APK'ları Drive'daki “Turan Asistan Güncelleme” klasörüne yükle (Grok Bot: `UploadFile`, folderId yukarıda). Yeni APK'ların Drive kimliklerini not et ve yukarıdaki tabloya yaz. **AppImage'ı da aynı klasöre yükle** (UploadFile sınırı 100 MB; şu an ~99 MB — büyürse `desktop/package.json` → `electronLanguages`'ı daralt). AppImage `turan-guncelleme.json` manifestine **eklenmez** (manifest yalnız Android içindir).
4. Manifest metnini üret: `tools/make-manifest.sh /workspace/turan-asistan-X.Y.Z-arm64.apk <APK_ID> "• yenilik 1\n• yenilik 2" > /tmp/turan-guncelleme.json`
5. Manifesti **yerinde** güncelle (kimlik değişmez): Grok Bot → `user-Google-drive.update_file_content` (fileId `1UwPuURLi9EZehR3Tya0xM3nF6BNi0V86`, contentMimeType `application/json`). Elle: Drive'da dosyaya sağ tık → Dosya bilgileri → *Sürümleri yönet* → *Yeni sürüm yükle* (aynı kimliği korur). **Yeni bir dosya yükleyip eskisini silme** — kimlik değişir ve eski uygulamalar güncellemeyi bulamaz.
6. Doğrula: `tools/check-update.sh` → manifest JSON, APK boyutu/SHA-256 TAMAM, sertifika aynı.
7. GitHub'a yayınla (depo: https://github.com/ahmetyildirim/turan-asistan):
   ```bash
   tools/release.sh --publish X.Y.Z "• yenilik 1
   • yenilik 2"
   ```
   Bu adım `web/`'i eşitler, izlenen dosyalarda anahtar/APK/parola olmadığını kontrol eder, commit eder, `vX.Y.Z` etiketini atar, `main` ve etiketi push eder ve iki APK'yı + `/workspace`'teki AppImage'ı ekleyerek GitHub release oluşturur (release zaten varsa AppImage'ı `--clobber` ile yükler).
8. Eski APK'lar Drive klasöründe kalabilir ya da silinebilir (manifestte artık geçmiyorlarsa).

## Test ipuçları (emülatör)
- `-Pemu` x86_64 ve http izinli derler; `-PupdateUrl=http://10.0.2.2:8098/turan-guncelleme.json` ile yerel test sunucusuna yönlendirilir; `-Pvc=6 -Pvn=1.3.1-test` ile deneme sürümü üretilir.
- `-PxHome=http://10.0.2.2:8765/xmock.html` X ekranını yerel sahte zaman tüneline yönlendirir (yalnız test; `xmock.html` APK'ya ve web paketine girmez).
- `-Pemu -Pslim` R8'li derlemeyi emülatörde denemeye yarar (PdfBox/WorkManager kuralları için).
- Uygulama günde en fazla bir kez otomatik kontrol eder (“Daha sonra” denen sürüm 3 gün sessiz kalır); Ayarlar → *Güncellemeleri kontrol et* her zaman hemen kontrol eder.

## Gizli Hat (v1.5.0+) notları
- Tor: `app/libs/tor-android-0.4.9.17-j17.aar`. Maven'deki orijinal AAR (sha1 Maven ile aynı, Guardian PGP imzası doğrulandı) Java 24 sınıf dosyası içerir; D8 bunu okuyamadığı için `tools/retarget-tor-aar.py` ile 6 sınıfın sürüm baytı 61'e (Java 17) çekildi (yalnızca NestHost/NestMembers ve lambda kullanıyorlar; davranış değişmez). Tor güncellenirse: yeni AAR'ı indir, imzasını doğrula, aynı betikle dönüştür, build.gradle'daki dosya adını değiştir.
- AAR'ın minCompileSdk=37 metadata kontrolü build.gradle'da kapatıldı (`check*AarMetadata`).
- `-PgzTestShots`: YALNIZ TEST. Gizli Hat ekranındaki FLAG_SECURE'u kapatır (emülatör ekran görüntüsü için) ve `GZDBG` logcat satırlarını açar. **Sürüm derlemesinde asla kullanma.** Sürümde Gizli Hat hiçbir log yazmaz.
- İki cihazlı test: `turan` + `turan2` AVD'leri (emulator-5554/5556), ikisi de gerçek Tor ağına bağlanır. `adb root` (google_apis imajı) ile `kill -3` iş parçacığı dökümü alınabilir.

## Linux masaüstü (AppImage) notları
- Kaynak `desktop/` (Electron + electron-builder). Web arayüzü derlemede `web/` → `desktop/app-web/` kopyalanır; `app://turan/` şemasıyla sunulur. `node_modules/`, `dist/`, `app-web/` git'e girmez.
- Preload yalnız `window.TuranDesktop = {platform, version}` verir; Android köprüsü (`TuranNative`) yoktur. Web arayüzü `html.desktop` sınıfıyla Android'e özgü bölümleri gizler, “Linux sürümünde desteklenmiyor” notunu gösterir (web kaynağında `D`/`NA` değişkenleri, `.desktop-only`/`.desktop-hide` sınıfları).
- Kutuda FUSE yoktur: test `APPIMAGE_EXTRACT_AND_RUN=1 xvfb-run -a … --no-sandbox` ile yapılır. `TURAN_SCREENSHOT=/yol.png TURAN_QUIT=1` ortam değişkenleri ekran görüntüsü alıp çıkar; `TURAN_DEBUG=1` tüm web konsolunu yazar (hatalar her zaman yazılır).
- İmza yoktur (AppImage imzası gerekmez); güncelleyici yoktur.
