import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

val contakoReleaseVersion = "0.10.0-RC1"
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
        versionCode = 17
        versionName = contakoReleaseVersion
        buildConfigField("String", "PROTON_RELEASE_VERSION", "\"${contakoReleaseVersion.substringBefore('-')}\"")
        buildConfigField("boolean", "SANITIZED_DIAGNOSTICS", "false")
        buildConfigField("boolean", "SYNC_DIAGNOSTICS", "false")
        buildConfigField("boolean", "IMPORT_INVESTIGATION", "false")
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
        create("investigation") {
            initWith(getByName("release"))
            versionNameSuffix = "-investigation${defaultConfig.versionCode}"
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "SYNC_DIAGNOSTICS", "true")
            buildConfigField("boolean", "IMPORT_INVESTIGATION", "true")
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
val sourceBuiltGolibVersion = libs.versions.protonGolib.get()
val sourceBuiltGolib = layout.projectDirectory.file(
    "native/build/maven/com/patmanak/contako/crypto/android-golib/$sourceBuiltGolibVersion/" +
        "android-golib-$sourceBuiltGolibVersion.aar",
)
val nativeSourceNames = listOf("go.mod", "go.sum", "dependencies.go", "build.ps1", "package-aar.py", "golib.pom")
val verifySourceBuiltCrypto = tasks.register("verifySourceBuiltCrypto") {
    group = "verification"
    description = "Reject missing or stale source-built Proton crypto artifacts."
    inputs.file(sourceBuiltGolib)
    inputs.file(layout.projectDirectory.file("gradle/verification-metadata.xml"))
    inputs.file(sourceBuiltGolib.asFile.resolveSibling(sourceBuiltGolib.asFile.name.replace(".aar", ".pom")))
    inputs.files(nativeSourceNames.map { layout.projectDirectory.file("native/golib/$it") })
    doLast {
        val artifact = sourceBuiltGolib.asFile
        check(artifact.isFile) { "Build native/golib first; see native/golib/README.md" }
        // Gradle trusts local Maven repositories; enforce their reviewed pins explicitly.
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val components = factory.newDocumentBuilder().parse(file("gradle/verification-metadata.xml"))
            .getElementsByTagName("component")
        val component = (0 until components.length).map { components.item(it) as Element }.single {
            it.getAttribute("group") == "com.patmanak.contako.crypto" &&
                it.getAttribute("name") == "android-golib" && it.getAttribute("version") == sourceBuiltGolibVersion
        }
        val pinnedArtifacts = component.getElementsByTagName("artifact")
        for (candidate in listOf(artifact, artifact.resolveSibling(artifact.name.replace(".aar", ".pom")))) {
            val pin = (0 until pinnedArtifacts.length).map { pinnedArtifacts.item(it) as Element }
                .single { it.getAttribute("name") == candidate.name }.getElementsByTagName("sha256")
            check(pin.length == 1) { "Native crypto requires one reviewed SHA-256 pin per artifact" }
            val digest = MessageDigest.getInstance("SHA-256")
            candidate.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var count = input.read(buffer)
                while (count >= 0) {
                    if (count > 0) digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual.equals((pin.item(0) as Element).getAttribute("value"), ignoreCase = true)) {
                "Native crypto checksum mismatch: ${candidate.name}"
            }
        }
        ZipFile(artifact).use { archive ->
            val entry = checkNotNull(archive.getEntry("META-INF/contako-native-provenance.json"))
            val provenance = archive.getInputStream(entry).use { JsonSlurper().parse(it) } as Map<*, *>
            check(provenance["coordinate"] == "com.patmanak.contako.crypto:android-golib:$sourceBuiltGolibVersion")
            val hashes = provenance["source_descriptors"] as Map<*, *>
            for (name in nativeSourceNames) {
                val bytes = file("native/golib/$name").readText(Charsets.UTF_8).replace("\r\n", "\n")
                    .toByteArray(Charsets.UTF_8)
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(hashes[name] == digest) { "Native crypto source changed: rebuild and review $name" }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifySourceBuiltCrypto) }
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "me.proton.crypto" && requested.name == "android-golib") {
            useTarget("com.patmanak.contako.crypto:android-golib:${libs.versions.protonGolib.get()}")
            because("Use the API-checked Proton source rebuild with a supported Go runtime")
        }
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
            because("Pin the qualified Proton source rebuild independently of the Core train")
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
    // Contact decryption uses the maintained bounded streaming reader from the
    // same strictly constrained source-built native artifact used by Proton Core.
    implementation(libs.proton.golib)
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
    // Room's migration bundles require this serializer ABI. AGP aligns instrumentation
    // dependencies to its host APK, so the selected test host must include it too.
    // Release and preview keep their production dependency graph.
    add("${contakoTestBuildType}Implementation", libs.kotlinx.serialization.room.test)
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
