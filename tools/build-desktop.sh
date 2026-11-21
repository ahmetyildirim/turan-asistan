#!/bin/bash
# Turan Asistan Linux masaüstü (Electron AppImage, x86_64) derlemesi. Kullanım: tools/build-desktop.sh X.Y.Z
# Çıktı: $OUT_DIR/TuranAsistan-X.Y.Z-x86_64.AppImage (varsayılan /workspace). İmza anahtarı gerekmez.
set -euo pipefail
cd "$(dirname "$0")/.."
V="${1:?sürüm (X.Y.Z)}"; OUT="${OUT_DIR:-/workspace}"
tools/sync-web.sh
cd desktop
# Sürümü package.json ve preload.js'te eşitle
node -e 'const f="package.json",p=require("./"+f);p.version=process.argv[1];require("fs").writeFileSync(f,JSON.stringify(p,null,2)+"\n")' "$V"
sed -i -E "s/version: '[^']+'/version: '$V'/" preload.js
[ -d node_modules ] || npm ci --no-audit --no-fund
rm -rf dist
npm run -s dist
A="dist/TuranAsistan-$V-x86_64.AppImage"
[ -f "$A" ] || { echo "AppImage üretilemedi"; exit 1; }
cp "$A" "$OUT/"
SZ=$(stat -c %s "$A")
echo "AppImage: $OUT/$(basename "$A") ($SZ bayt)"
# Grok Bot'un Drive UploadFile sınırı 100 MB: aşarsa electronLanguages / sıkıştırmayı gözden geçirin
[ "$SZ" -lt 100000000 ] || echo "UYARI: AppImage 100 MB'ı aşıyor; Drive'a UploadFile ile yüklenemez"
# Duman testi (FUSE gerekmez): başlat, ekran görüntüsü al, çık
if command -v xvfb-run >/dev/null; then
  APPIMAGE_EXTRACT_AND_RUN=1 TURAN_SCREENSHOT="$OUT/turan-linux-screenshot.png" TURAN_QUIT=1 \
    timeout 90 xvfb-run -a -s "-screen 0 1280x900x24" "$A" --no-sandbox 2>&1 | grep -E '^\[(web|test)' || true
fi
