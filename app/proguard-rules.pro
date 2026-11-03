# Add project specific ProGuard rules here.

# BouncyCastle - required for TLS certificate generation
# BC uses reflection to load algorithm implementations, so R8 strips them without these rules.
# The error "cannot create signer: no such algorithm: SHA256WITHRSA" occurs when these are missing.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Protobuf
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Keep the RemoteMessage and Polo proto classes
-keep class net.pcg.hearth.remote.proto.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
