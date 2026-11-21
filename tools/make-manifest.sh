#!/bin/bash
# Kullanım: tools/make-manifest.sh <APK yolu> <Drive dosya kimliği> "<Türkçe yenilikler, satırlar \n ile>"
APK="$1"; ID="$2"; NOTES="$3"
B=/opt/android-sdk/build-tools/34.0.0
VC=$($B/aapt dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
VN=$($B/aapt dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
python3 - "$APK" "$ID" "$VC" "$VN" "$NOTES" <<'PY'
import json, hashlib, os, sys
apk, fid, vc, vn, notes = sys.argv[1:6]
b = open(apk, 'rb').read()
print(json.dumps({"versionCode": int(vc), "versionName": vn,
  "apkUrl": f"https://drive.usercontent.google.com/download?id={fid}&export=download&confirm=t",
  "size": len(b), "sha256": hashlib.sha256(b).hexdigest(), "notes": notes.replace('\\n', '\n')}, ensure_ascii=False, indent=2))
PY
