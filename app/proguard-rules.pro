# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class net.triton.frigateviewer.**$$serializer { *; }
-keepclassmembers class net.triton.frigateviewer.** {
    *** Companion;
}
-keepclasseswithmembers class net.triton.frigateviewer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions

# Hilt — handled by plugin

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# MQTT
-keep class com.hivemq.client.** { *; }
-dontwarn com.hivemq.client.**

# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
