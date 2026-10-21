# --- Anthropic SDK: Jackson (de)serializes its models by reflection on annotated members. Keep exactly those.
-keep class com.anthropic.core.** { *; }
-keepclassmembers class com.anthropic.** {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonCreator *;
    @com.fasterxml.jackson.annotation.JsonProperty *;
    @com.fasterxml.jackson.annotation.JsonAnySetter *;
    @com.fasterxml.jackson.annotation.JsonAnyGetter *;
    @com.fasterxml.jackson.annotation.JsonValue *;
}
-keep @com.fasterxml.jackson.databind.annotation.JsonDeserialize class com.anthropic.** { *; }
-keep @com.fasterxml.jackson.databind.annotation.JsonSerialize class com.anthropic.** { *; }
-keep class com.anthropic.** extends com.fasterxml.jackson.databind.JsonDeserializer { *; }
-keep class com.anthropic.** extends com.fasterxml.jackson.databind.JsonSerializer { *; }
-keep class * extends com.fasterxml.jackson.databind.Module { *; }
-keep class com.fasterxml.jackson.databind.ext.** { *; }
-keep class com.fasterxml.jackson.module.kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn com.anthropic.**
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.annotations.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.google.auto.value.AutoValue
# --- JavaMail: providers and data handlers are loaded by name.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.callback.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**
# --- kotlinx.serialization: generated serializers
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.qshlab.daybreak.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.qshlab.daybreak.** { *** Companion; }
-dontwarn okhttp3.internal.**

# The home-screen widget (Glance) uses WorkManager, which creates its Room database by reflection.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
# WorkManager also creates its input mergers and workers (Glance draws widgets in one) by class name.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
