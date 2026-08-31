# Quantum player ProGuard/R8 rules.
#
# minifyEnabled is false for release today, so these rules are only a safety
# net for when shrinking gets turned on.

# Media3 / ExoPlayer relies on reflection for renderer and extractor discovery.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Room generates <Entity>_Impl / <Database>_Impl classes that are looked up reflectively.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Kotlin coroutines / metadata
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontwarn kotlinx.coroutines.**
