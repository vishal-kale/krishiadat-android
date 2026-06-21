# KrishiAdat ProGuard / R8 rules

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# WebView JavaScript bridge — any method annotated @JavascriptInterface must
# keep its exact name; renaming it breaks the JS → Kotlin calls.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.vallixo.krishiadat.MainActivity$AndroidPrintBridge { *; }
-keepclassmembers class com.vallixo.krishiadat.MainActivity$AndroidShareBridge { *; }

# Play Core (in-app updates)
-keep class com.google.android.play.core.** { *; }

# Play Integrity
-keep class com.google.android.play.integrity.** { *; }

# Biometric
-keep class androidx.biometric.** { *; }

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
