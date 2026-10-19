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
