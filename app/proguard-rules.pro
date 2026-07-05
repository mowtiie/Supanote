-keep class com.mowtiie.supanote.data.model.** { *; }

-keep class * extends androidx.preference.PreferenceFragmentCompat { *; }

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile