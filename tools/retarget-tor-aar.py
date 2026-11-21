#!/usr/bin/env python3
"""tor-android AAR'ındaki Java sınıflarının sürüm numarasını 61'e (Java 17) çeker.
Kullanım: tools/retarget-tor-aar.py tor-android-X.aar app/libs/tor-android-X-j17.aar
Yalnızca Java 11+ (NestHost, lambda) özellikleri kullanıldığı doğrulandıktan sonra kullanın (javap -v)."""
import io, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'classes.jar':
            jin = zipfile.ZipFile(io.BytesIO(data)); buf = io.BytesIO()
            with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as jout:
                for ji in jin.infolist():
                    b = bytearray(jin.read(ji.filename))
                    if ji.filename.endswith('.class') and b[:4] == b'\xca\xfe\xba\xbe' and b[7] > 61: b[6], b[7] = 0, 61
                    jout.writestr(ji, bytes(b))
            data = buf.getvalue()
        zout.writestr(item, data)
print('ok', dst)
