# Add project specific ProGuard rules here.
-keep class com.atarayocast.app.bridge.** { *; }
-keepclassmembers class com.atarayocast.app.** {
    native <methods>;
}
