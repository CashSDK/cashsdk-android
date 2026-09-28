# R8/ProGuard rules for building the SDK itself (release variant of this library).
# Consumer-facing keep rules live in consumer-rules.pro (bundled into the .aar).

# Preserve line numbers for readable crash reports coming from SDK code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
