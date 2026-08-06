# Consumer ProGuard/R8 rules — shipped INSIDE the .aar and applied automatically to any
# app that depends on CashSDK. Keep this minimal: only what a consuming app's R8 pass needs
# to not break the SDK. (The SDK's own build uses proguard-rules.pro.)

# kotlinx.serialization: keep the generated serializers for our wire models. Without this,
# R8 can strip the synthetic `Companion.serializer()` and `$serializer` classes and JSON
# encode/decode fails at runtime in release builds.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.cashsdk.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.cashsdk.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cashsdk.model.**$$serializer { *; }

# The public facade is the app's entry point — never rename/strip it.
-keep class com.cashsdk.CashSDK { *; }
-keep class com.cashsdk.CashSDK$* { *; }

# Google Play Billing ships its own consumer rules; nothing extra needed here.
