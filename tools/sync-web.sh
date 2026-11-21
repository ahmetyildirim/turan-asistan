#!/bin/bash
# Web arayüzünü (PWA kaynağı) depodaki web/ klasörüne eşitler. Kaynak: WEB_SRC (varsayılan /workspace/emir-eri-turan)
# Yalnız test dosyaları (xmock.html), ekran görüntüleri ve gizli dosyalar kopyalanmaz.
set -e
SRC="${WEB_SRC:-/workspace/emir-eri-turan}"; DST="$(cd "$(dirname "$0")/.." && pwd)/web"
[ -d "$SRC" ] || { echo "Kaynak yok: $SRC (web/ olduğu gibi kullanılır)"; exit 0; }
rm -rf "$DST"; mkdir -p "$DST"
cp -r "$SRC"/. "$DST"/
rm -f "$DST"/xmock.html "$DST"/screenshot*.png "$DST"/*.zip; find "$DST" -name '.*' -mindepth 1 -exec rm -rf {} +
echo "web/ eşitlendi ← $SRC"
