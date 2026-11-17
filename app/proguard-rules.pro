# Add project specific ProGuard rules here.

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.karelisio.fichio.**$$serializer { *; }
-keepclassmembers class com.karelisio.fichio.** {
    *** Companion;
}
-keepclasseswithmembers class com.karelisio.fichio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Shizuku
-keep class rikka.shizuku.** { *; }

# Ktor
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# Zip4j / commons-compress / junrar
-dontwarn org.apache.commons.**
-dontwarn net.lingala.zip4j.**
-dontwarn com.github.junrar.**

# Media3
-dontwarn androidx.media3.**

# Coil
-dontwarn coil.**
