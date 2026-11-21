# Uygulama sınıfları (JS köprüsü, alıcılar, widget, kutucuk) aynen kalsın
-keep class com.ahmet.emirerituran.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface,*Annotation*,Signature,InnerClasses,EnclosingMethod
# PdfBox-Android: yansıma ve kaynak dosyaları ile yükleme yapar
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
# BouncyCastle (şifreli PDF) – pqc tabloları paketten çıkarıldı
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.slf4j.**
# Gizli Hat: Tor JNI ve kontrol kütüphanesi
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn org.torproject.jni.**
-dontwarn kotlin.**
