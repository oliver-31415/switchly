############################################
# Kotlin/Coroutines
############################################
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

############################################
# Firebase (BOM)
############################################
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

############################################
# ML Kit Barcode (Play Services)
############################################
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.odml.**
-dontwarn com.google.android.libraries.vision.**

############################################
# CameraX
############################################
-dontwarn androidx.camera.**
-dontwarn androidx.concurrent.futures.**
-dontwarn com.google.common.util.concurrent.**   # ListenableFuture/Guava

############################################
# ZXing (QR generate)
############################################
-dontwarn com.google.zxing.**

############################################
# Material/AppCompat (optional; mostly noise suppression)
############################################
-dontwarn com.google.android.material.**
-dontwarn androidx.appcompat.**

############################################
# Keep annotations (helps some libs/tools)
############################################
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

############################################
# If you use deep links with reflection/custom class loading: not needed for your current setup
############################################
# -keep class at.saltyy.switchly.** { *; }

# Play Integrity is loaded reflectively for soft diagnostics, so keep the SDK API classes in minified release builds.
-keep class com.google.android.play.core.integrity.** { *; }
-keep class com.google.android.gms.tasks.OnSuccessListener { *; }
-keep class com.google.android.gms.tasks.OnFailureListener { *; }
