# JNI entry points are resolved by name; keep the bridge and its callback interface.
-keep class dev.localai.agent.inference.LlamaBridge { *; }
-keep class dev.localai.agent.inference.LlamaBridge$TokenSink { *; }
-keepclasseswithmembernames class * { native <methods>; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.localai.agent.**$$serializer { *; }
-keepclassmembers class dev.localai.agent.** { *** Companion; }
-keepclasseswithmembers class dev.localai.agent.** { kotlinx.serialization.KSerializer serializer(...); }

-dontwarn org.apache.commons.compress.**
