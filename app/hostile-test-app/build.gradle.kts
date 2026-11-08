plugins {
    id("com.android.application")
}

val targetSuffix = providers.gradleProperty("contakoCandidatePackageSuffix").orNull
    ?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
    ?.let { if (it.startsWith('.')) it else ".$it" } ?: ".securitycandidate"
require(targetSuffix.matches(Regex("^(?:\\.[a-z][a-z0-9_]*)+$"))) {
    "The hostile fixture requires an isolated contakoCandidatePackageSuffix"
}
val targetApplicationId = "com.patmanak.contako$targetSuffix"

android {
    namespace = "com.patmanak.contako.hostile"
    compileSdk = 36

    defaultConfig {
        applicationId = "$targetApplicationId.hostile"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.8-security-fixture"
        buildConfigField("String", "TARGET_APPLICATION_ID", "\"$targetApplicationId\"")
        manifestPlaceholders["hostileAction"] = "$targetApplicationId.hostile.RUN"
    }
    buildFeatures { buildConfig = true }
}
