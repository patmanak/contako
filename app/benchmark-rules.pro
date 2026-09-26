-keep class com.patmanak.contako.** { *; }
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep,allowoptimization class androidx.activity.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class javax.inject.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }

# The separately minified instrumentation APK calls ez-vcard's public facade by name.
# Keep this benchmark-only entry point so a physical parser probe measures product behavior.
-keep,includedescriptorclasses class ezvcard.Ezvcard { *; }

# `testBuildType` is `benchmark`, so the whole instrumented suite runs against a
# minified build. Library entry points reached only from test code look unreachable
# to R8 and are stripped or have their signatures rewritten, which surfaces as
# NoClassDefFoundError/NoSuchMethodError. Those are harness artifacts, not product
# defects, and they previously masked the real pass/fail state of the suite.
#
# Scope note: these rules apply to `benchmark` only. The shipped `release` variant
# keeps full minification and shrinking and is unaffected. Startup/runtime timings
# measured on `benchmark` are now closer to debug than to release for the kept
# packages, so treat absolute performance numbers from this variant as indicative.
-keep class androidx.** { *; }
-dontwarn androidx.**
-keep class me.proton.core.** { *; }
-dontwarn me.proton.core.**

# kotlinx.serialization needs its generated `$$serializer` singletons and the
# synthetic members R8 would otherwise rewrite; a plain package keep is not enough
# and leaves AbstractMethodError on GeneratedSerializer.typeParametersSerializers.
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
