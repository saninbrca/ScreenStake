# Add project specific ProGuard rules here.

# Preserve line numbers in stack traces for debugging
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

# Stripe SDK — required for PaymentSheet to function correctly
-keep class com.stripe.** { *; }
-dontwarn com.stripe.**

# Firebase — keep all classes to prevent Firestore/Auth/Functions breakage
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Room — keep entity classes so reflection-based column mapping works
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Hilt — consumer rules handle most of it; keep component classes explicitly
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Kotlin serialization / coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Timber
-dontwarn timber.log.**

# Sentry — preserve annotations + source/line info for readable stack traces
-keepattributes Annotation
-keepattributes SourceFile,LineNumberTable
-keep class io.sentry.** { *; }