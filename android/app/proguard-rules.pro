# Proguard Rules for LocalSync Android Application

# Keep general Kotlin structures
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Keep data models that are serialized/deserialized or stored in Room
-keep class com.example.localsync.data.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Room Database rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# ML Kit and CameraX fallbacks
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn androidx.camera.**
