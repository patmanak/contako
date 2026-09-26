import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val contakoReleaseVersion = "0.9.0"
val contakoBaseApplicationId = "com.patmanak.contako"
val contakoCandidatePackageSuffix = providers.gradleProperty("contakoCandidatePackageSuffix")
    .orNull
    ?.trim()
    ?.removeSurrounding("\"")
    ?.removeSurrounding("'")
    ?.let { suffix -> if (suffix.startsWith('.')) suffix else ".$suffix" }
    ?.also { suffix ->
        require(suffix.matches(Regex("^\\.[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*$"))) {
            "contakoCandidatePackageSuffix must be a dotted, lower-case Android package suffix"
        }
    }
val contakoApplicationId = contakoBaseApplicationId + contakoCandidatePackageSuffix.orEmpty()
val contakoTargetAbi = providers.gradleProperty("contakoTargetAbi").orNull?.also {
    require(it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
}
val contakoTestBuildType = providers.gradleProperty("contakoTestBuildType")
    .orElse("benchmark")
    .get()
    .also { require(it in setOf("debug", "benchmark", "diagnostic")) }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.patmanak.contako"
    compileSdk = 36

    defaultConfig {
        applicationId = contakoApplicationId
        minSdk = 31
        targetSdk = 36
        contakoTargetAbi?.let { targetAbi -> ndk { abiFilters += targetAbi } }
        versionCode = 9
        versionName = contakoReleaseVersion
        buildConfigField("String", "PROTON_RELEASE_VERSION", "\"$contakoReleaseVersion\"")
        buildConfigField("boolean", "SANITIZED_DIAGNOSTICS", "false")
        buildConfigField("boolean", "SYNC_DIAGNOSTICS", "false")
        buildConfigField("String", "ANDROID_ACCOUNT_TYPE", "\"$contakoApplicationId\"")
        resValue("string", "contako_account_type", contakoApplicationId)

        testInstrumentationRunner = "com.patmanak.contako.qa.ContakoInstrumentationRunner"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        testProguardFiles("benchmark-test-rules.pro")
    }

    testBuildType = contakoTestBuildType
    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            isPseudoLocalesEnabled = true
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("preview") {
            initWith(getByName("release"))
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("diagnostic") {
            initWith(getByName("debug"))
            versionNameSuffix = "-diagnostic"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "SANITIZED_DIAGNOSTICS", "true")
        }
        create("syncDiagnostic") {
            initWith(getByName("preview"))
            versionNameSuffix = "-sync-diagnostic"
            buildConfigField("boolean", "SYNC_DIAGNOSTICS", "true")
        }
        create("benchmark") {
            initWith(getByName("release"))
            versionNameSuffix = "-benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles("benchmark-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }

    // The app exposes an in-app language selector and must ship every advertised catalog in the
    // base install. Otherwise an AAB install can select a locale whose split is absent.
    bundle {
        language {
            enableSplit = false
        }
    }

    sourceSets.getByName("test").kotlin.srcDir("src/testShared/java")
    sourceSets.getByName("androidTest").apply {
        kotlin.srcDir("src/testShared/java")
        assets.srcDir("schemas")
    }
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val protonCoreVersion = libs.versions.protonCore.get()
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "me.proton.core" && requested.version != protonCoreVersion) {
            throw GradleException(
                "Mixed Proton Core release trains are forbidden: ${requested.name}:${requested.version}",
            )
        }
    }
}

dependencies {
    constraints {
        implementation(libs.proton.golib) {
            version { strictly(libs.versions.protonGolib.get()) }
            because("Qualify the maintained native crypto release separately from the Proton Core train")
        }
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.proton.account.domain)
    implementation(libs.proton.account.data)
    implementation(libs.proton.account.manager.domain)
    implementation(libs.proton.account.manager.data) {
        exclude(group = "me.proton.core", module = "auth-presentation")
        exclude(group = "me.proton.core", module = "notification-presentation")
        exclude(group = "me.proton.core", module = "account-recovery-presentation-compose")
    }
    implementation(libs.proton.auth.domain)
    implementation(libs.proton.auth.data)
    implementation(libs.proton.auth.fido.domain)
    implementation(libs.proton.challenge.domain)
    implementation(libs.proton.challenge.data)
    implementation(libs.proton.human.verification.domain)
    implementation(libs.proton.human.verification.data) {
        exclude(group = "me.proton.core", module = "presentation")
    }
    implementation(libs.proton.network.domain)
    implementation(libs.proton.network.data)
    implementation(libs.proton.user.domain)
    implementation(libs.proton.user.data)
    implementation(libs.proton.key.domain)
    implementation(libs.proton.key.data)
    implementation(libs.proton.crypto.android)
    implementation(libs.proton.contact.domain)
    implementation(libs.proton.contact.data)
    implementation(libs.proton.label.domain)
    implementation(libs.proton.label.data)
    implementation(libs.proton.data.room)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.tracing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestUtil(libs.androidx.test.services)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "benchmarkImplementation"(libs.androidx.compose.ui.test.manifest)
}

tasks.configureEach {
    when (name) {
        "testDebugUnitTest" -> dependsOn("processReleaseManifest")
        "testBenchmarkUnitTest" -> dependsOn("processDebugManifest", "processReleaseManifest")
    }
}

tasks.register("contakoJvmCheck") {
    group = "verification"
    description = "Runs Contako's real benchmark-variant JVM regression gate."
    dependsOn("testBenchmarkUnitTest")
}
