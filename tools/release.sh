#!/bin/bash
# Turan Asistan sürüm çıkarma yardımcısı (bkz. RELEASE.md).
# Kullanım: tools/release.sh X.Y.Z VERSION_CODE        → derler, doğrular, APK'ları /workspace'e kopyalar
#           tools/release.sh --publish X.Y.Z "notlar"   → Drive adımlarından SONRA: commit + tag + push + GitHub release
# Drive yükleme ve manifest güncelleme Grok Bot'un Drive araçlarıyla yapılır (bu betik Drive'a dokunmaz).
# İmza: keystore.properties (git'e girmez) ya da TURAN_* ortam değişkenleri. Parolayı asla yazdırma/commit etme.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/jdk17}"; export PATH="$JAVA_HOME/bin:$PATH"
BT="${BUILD_TOOLS:-/opt/android-sdk/build-tools/34.0.0}"
CERT=1b04bcc7428da5333a32021a11aa56e5741393fcf59cce83f30fea04f9efb432
OUT="${OUT_DIR:-/workspace}"

if [ "${1:-}" = "--publish" ]; then
  V="$2"; NOTES="${3:-Turan Asistan $V}"
  [ -f "$OUT/turan-asistan-$V.apk" ] && [ -f "$OUT/turan-asistan-$V-arm64.apk" ] || { echo "APK'lar yok: $OUT/turan-asistan-$V*.apk"; exit 1; }
  tools/sync-web.sh
  # Güvenlik: izlenen dosyalarda imza/anahtar olmamalı
  git add -A
  if git diff --cached --name-only | grep -Ei '\.(keystore|jks|p12|apk|aab)$|keystore\.properties$|\.env'; then echo "HATA: gizli/ikili dosya commit edilmek üzere!"; exit 1; fi
  if [ -f keystore.properties ]; then
    PW=$(grep -oP '^storePassword=\K.*' keystore.properties || true)
    if [ -n "$PW" ] && git grep --cached -q -F -- "$PW"; then echo "HATA: imza parolası izlenen bir dosyada!"; exit 1; fi
  fi
  git diff --cached --quiet || git commit -q -m "Sürüm $V"
  git rev-parse -q --verify "refs/tags/v$V" >/dev/null || git tag -a "v$V" -m "Turan Asistan $V"
  gh auth setup-git >/dev/null 2>&1 || true   # git push için gh kimlik bilgisi
  git push -q origin main "v$V"
  gh release view "v$V" >/dev/null 2>&1 && { echo "Release v$V zaten var"; exit 0; }
  gh release create "v$V" "$OUT/turan-asistan-$V-arm64.apk" "$OUT/turan-asistan-$V.apk" --title "Turan Asistan $V" --notes "$NOTES"
  echo "Yayınlandı: v$V"; exit 0
fi

V="${1:?sürüm (X.Y.Z)}"; VC="${2:?versionCode}"
# build.gradle'daki varsayılan sürümü güncelle (depoda da doğru sürüm görünsün)
sed -i -E "s/(findProperty\('vc'\) \?: ')[0-9]+(')/\1$VC\2/; s/(findProperty\('vn'\) \?: ')[^']+(')/\1$V\2/" app/build.gradle
grep -q "?: '$VC') as int" app/build.gradle && grep -q "?: '$V') as String" app/build.gradle || { echo "build.gradle sürümü güncellenemedi"; exit 1; }
tools/sync-web.sh
G="./gradlew --no-daemon -q -Pvn=$V -Pvc=$VC"
$G assembleRelease;                cp app/build/outputs/apk/release/app-release.apk "$OUT/turan-asistan-$V.apk"
$G assembleRelease -Parm64 -Pslim; cp app/build/outputs/apk/release/app-release.apk "$OUT/turan-asistan-$V-arm64.apk"
for f in "$OUT/turan-asistan-$V.apk" "$OUT/turan-asistan-$V-arm64.apk"; do
  "$BT/aapt" dump badging "$f" | grep -q "versionCode='$VC' versionName='$V'" || { echo "Sürüm tutmuyor: $f"; exit 1; }
  "$BT/apksigner" verify --print-certs "$f" | grep -q "$CERT" || { echo "Sertifika tutmuyor: $f"; exit 1; }
  echo "TAMAM $(stat -c %s "$f") bayt  $f"
done
echo "Sıradaki: APK'ları Drive'a yükle → tools/make-manifest.sh → manifesti yerinde güncelle → tools/check-update.sh → tools/release.sh --publish $V \"notlar\""
