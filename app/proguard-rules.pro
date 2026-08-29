# Vanta ProGuard & R8 Bytecode Optimization Rules

-keep class com.vanta.app.** { *; }

# Jetpack Compose Optimizations
-keepclassmembers class * extends androidx.compose.ui.Modifier { *; }
-dontwarn androidx.compose.**

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Android Health Connect Client SDK
-keep class androidx.health.connect.client.** { *; }
-keepclassmembers class androidx.health.connect.client.** { *; }
-dontwarn androidx.health.connect.client.**

# Google LiteRT-LM and MediaPipe
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Firebase Crashlytics references a hidden Android API (android.os.ProfilingTrigger)
# that is absent from the SDK stubs — R8 cannot find it during minification.
# Crashlytics no-ops cleanly when it is missing at runtime.
-dontwarn android.os.ProfilingTrigger
-dontwarn android.os.ProfilingTrigger$Builder

# Annotation processors and build-time metadata
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
-dontwarn org.checkerframework.**

# ── llama.cpp chat engine (native jni) ──────────────────────────────────────
-keep class com.vanta.app.data.ai.VantaLllamaEngine { *; }
