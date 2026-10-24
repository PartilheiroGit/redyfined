# P4 (2026-10-22): R8 + resource shrinking.
# Keep rules are intentionally broad for reflection/JNI-heavy dependencies; the app's own code is
# kept as well because it is small and the win comes from the extended Compose icon set.

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- App (serialization models, JNI callbacks, reflection helpers) ----
-keep class com.lasvegas.secmess.** { *; }
-keepclassmembers class com.lasvegas.secmess.** { *; }

# ---- kotlinx.serialization ----
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# ---- Ktor / coroutines / OkHttp ----
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**

# ---- Embedded Tor (kmp-tor) ----
-keep class io.matthewnelson.** { *; }
-dontwarn io.matthewnelson.**

# ---- BouncyCastle ----
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---- ZXing ----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- Play services location ----
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ---- AndroidX security crypto / Compose ----
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.**
