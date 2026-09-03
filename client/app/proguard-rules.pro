# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
# Keep useful release stack traces without exposing original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson reads these models through reflection. Keep generic element types and
# runtime annotations so List<T>, Map<K,V> and @SerializedName survive R8.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# DTO field names are both the V2Ray JSON schema and the persisted MMKV schema.
# R8 must not remove, rename, strengthen, merge or make these reflection targets
# abstract. A narrow @SerializedName-only rule is not sufficient because many
# V2rayConfig/ProfileItem fields intentionally use their Kotlin property names.
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.enums.** { *; }

# HEV registers its JNI entry points by the exact Java class, method names and
# descriptors during JNI_OnLoad. Keep all native declarations stable under R8.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# libhev-socks5-tunnel registers all three methods below as one table during
# JNI_OnLoad. TProxyGetStats is not called by Kotlin yet, so a generic
# keepclasseswithmembernames rule is insufficient: R8 can remove that method and
# make registration of the entire native library fail. Keep this exact JNI host
# class and every native member unconditionally.
-keep class com.v2ray.ang.service.TProxyService {
    native <methods>;
}

# The signed image payload DTOs are deserialized by Gson reflection. Their JSON field names are
# part of the backend contract and must never be renamed by R8.
-keep class com.v2ray.ang.haima.SorenServer { *; }
-keep class com.v2ray.ang.haima.SorenAdsSettings { *; }
-keep class com.v2ray.ang.haima.SorenAdPlacement { *; }
-keep class com.v2ray.ang.haima.SorenAdPlacements { *; }
-keep class com.v2ray.ang.haima.SorenAppSettings { *; }
-keep class com.v2ray.ang.haima.SorenUpdatePolicy { *; }
-keep class com.v2ray.ang.haima.SorenBootstrapPayload { *; }
