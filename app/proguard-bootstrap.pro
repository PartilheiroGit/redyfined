# Regras do bootstrap (GitHub): só calendário + PIN + canal de update + install.
# Ao contrário do proguard-rules.pro da app completa, NÃO se mantém
# com.lasvegas.secmess.** — o R8 tem de apagar o chat, P2P, relay, contactos, etc.

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Entrada e o que ela usa diretamente.
-keep class com.lasvegas.secmess.bootstrap.** { *; }
-keep class com.lasvegas.secmess.network.TorManager { *; }
-keep class com.lasvegas.secmess.network.UpdateManager { *; }
-keep class com.lasvegas.secmess.network.RelayConfig { *; }
-keep class com.lasvegas.secmess.ui.theme.** { *; }

# kmp-tor: JNI, reflection e o initializer do androidx.startup.
-keep class io.matthewnelson.** { *; }
-dontwarn io.matthewnelson.**

# Bibliotecas que o bootstrap não usa (o R8 apaga; sem warnings).
-dontwarn com.google.android.gms.**
-dontwarn com.journeyapps.**
-dontwarn com.google.zxing.**
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
