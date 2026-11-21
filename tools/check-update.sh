#!/bin/bash
# Drive'daki güncelleme zincirini doğrular: manifest JSON mu, APK indiriliyor mu, boyut/SHA-256 tutuyor mu?
MANIFEST_ID=1UwPuURLi9EZehR3Tya0xM3nF6BNi0V86
U="https://drive.usercontent.google.com/download?id=$MANIFEST_ID&export=download&confirm=t"
export PATH=/opt/jdk17/bin:$PATH
set -e
curl -sL -o /tmp/turan-manifest.json -w "manifest: HTTP %{http_code} %{content_type}\n" "$U"
if head -c 20 /tmp/turan-manifest.json | grep -qi "<"; then echo "HATA: manifest yerine HTML geldi (klasör 'Bağlantıya sahip olan herkes' ile paylaşılmamış)"; exit 1; fi
python3 - <<'PY'
import json, hashlib, subprocess, sys
m = json.load(open('/tmp/turan-manifest.json'))
print('manifest:', m['versionName'], 'vc', m['versionCode'])
subprocess.run(['curl', '-sL', '-o', '/tmp/turan-update.apk', m['apkUrl']], check=True)
b = open('/tmp/turan-update.apk', 'rb').read()
ok = b[:2] == b'PK' and len(b) == m.get('size', len(b)) and (not m.get('sha256') or hashlib.sha256(b).hexdigest() == m['sha256'])
print('apk:', len(b), 'bayt', 'sha256', hashlib.sha256(b).hexdigest(), '->', 'TAMAM' if ok else 'HATA')
sys.exit(0 if ok else 1)
PY
/opt/android-sdk/build-tools/34.0.0/aapt dump badging /tmp/turan-update.apk | head -1
/opt/android-sdk/build-tools/34.0.0/apksigner verify --print-certs /tmp/turan-update.apk | grep "certificate SHA-256"
echo "Beklenen sertifika: 1b04bcc7428da5333a32021a11aa56e5741393fcf59cce83f30fea04f9efb432"
