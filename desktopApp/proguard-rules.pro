# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.qshlab.daybreak.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.qshlab.daybreak.**$$serializer { *; }
-keepclassmembers class com.qshlab.daybreak.** { *** Companion; }
# OkHttp / Okio / ZXing / coroutines: quiet warnings about optional platform classes
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**
-dontnote **

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
-dontwarn kotlin.concurrent.atomics.**
# Jackson reads enum constants and its own classes by reflection.
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keep class com.fasterxml.jackson.** { *; }
# Kotlin reflection (jackson-module-kotlin) calls the SDK's constructors, including the synthetic ones for default arguments.
-keepclassmembers class com.anthropic.** { <init>(...); }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault, MethodParameters
