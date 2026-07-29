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

# Giữ số dòng để đọc được stack trace crash trong bản release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================================
# Gson + Retrofit — bản release bật R8 sẽ đổi tên/xoá field. Model app map JSON
# theo TÊN FIELD (không có @SerializedName), đổi tên = Gson parse fail = "lỗi
# server" khi login. Các rule dưới giữ nguyên model + metadata cần cho reflection.
# ============================================================================

# Metadata bắt buộc: Signature (generic Call<ApiResponse<T>>), annotation @SerializedName.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes InnerClasses,EnclosingMethod

# Model/DTO app được Gson (de)serialize qua reflection → cấm đổi tên field.
-keep class vn.chat9.app.data.model.** { *; }
-keep class vn.chat9.app.data.vapi.** { *; }

# Field gắn @SerializedName (DTO vapi) — giữ dù có obfuscate class.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Enum dùng trong payload Gson.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Gson nội bộ.
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-dontwarn com.google.gson.**

# Retrofit / OkHttp / Okio (giữ interface service + im cảnh báo thư viện).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep interface vn.chat9.app.data.api.ApiService { *; }
-keep interface vn.chat9.app.data.vapi.VapiApiService { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**