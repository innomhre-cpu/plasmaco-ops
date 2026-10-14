# kotlinx.serialization: the library ships R8 rules for @Serializable classes; these keep the wire DTOs
# (core/api, package rw.plasmaco.ops.api) and any app-local @Serializable class explicitly as well.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class rw.plasmaco.ops.** { *** Companion; }
-keepclasseswithmembers class rw.plasmaco.ops.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class rw.plasmaco.ops.**$$serializer { *; }
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface rw.plasmaco.ops.data.remote.PlasmacoApi
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# SAX parser used by the workbook importer
-dontwarn org.xml.sax.**
