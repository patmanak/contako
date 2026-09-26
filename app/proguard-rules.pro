# Proton Core brings Retrofit 2.9.0, whose consumer rules predate these R8
# full-mode fixes. Retrofit reflects on suspend Continuation response types.
# Backport maintained Retrofit rules without disabling optimization:
# https://github.com/square/retrofit/blob/trunk/retrofit/src/main/resources/META-INF/proguard/retrofit2.pro
-keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation
-keep,allowoptimization,allowshrinking,allowobfuscation class retrofit2.Response
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>

# ez-vcard dispatches property scribes from a runtime registry. R8 cannot infer every content-
# selected path and removed the minified PHOTO scribe. Keep only the binary image path; keeping
# the full library would incorrectly retain optional JSON/template integrations absent on Android.
-keep class ezvcard.io.scribe.ScribeIndex { *; }
-keep,allowoptimization,allowobfuscation class ezvcard.io.scribe.*Scribe
-keep class ezvcard.io.scribe.VCardPropertyScribe$* { *; }
-keep class ezvcard.io.scribe.BinaryPropertyScribe { *; }
-keep class ezvcard.io.scribe.BinaryPropertyScribe$* { *; }
-keep class ezvcard.io.scribe.PhotoScribe { *; }
-keep class ezvcard.io.scribe.LogoScribe { *; }
-keep class ezvcard.property.BinaryProperty { *; }
-keep class ezvcard.property.Photo { *; }
-keep class ezvcard.property.Logo { *; }
-keep class ezvcard.parameter.ImageType { *; }
-keep class ezvcard.parameter.MediaTypeParameter { *; }
-keep class ezvcard.util.DataUri { *; }
-keep class ezvcard.util.org.apache.commons.codec.binary.Base64 { *; }
