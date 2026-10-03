package com.patmanak.contako.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProjectInvariantTest {
    @Test
    fun domainAndUiDependenciesPointInward() {
        val domainSources = sourceFiles("src/main/java/com/patmanak/contako/domain")
        assertTrue("The domain source set must not be empty", domainSources.isNotEmpty())

        val forbiddenDomainImports = listOf(
            "import android.",
            "import androidx.",
            "import com.patmanak.contako.data.",
            "import okhttp3.",
            "import retrofit2.",
            "import kotlinx.serialization.",
        )
        domainSources.forEach { source ->
            val text = source.readText()
            forbiddenDomainImports.forEach { forbidden ->
                assertFalse("${source.name} must not depend on $forbidden", text.contains(forbidden))
            }
        }

        sourceFiles("src/main/java/com/patmanak/contako/ui").forEach { source ->
            val text = source.readText()
            assertFalse("${source.name} must use the repository boundary", text.contains(".data.local."))
            assertFalse("${source.name} must not call a peer gateway", text.contains(".data.gateway."))
        }

        sourceFiles("src/main/java/com/patmanak/contako/data").forEach { source ->
            assertFalse("${source.name} must not depend on presentation models", source.readText().contains(
                "import com.patmanak.contako.ui.",
            ))
        }

        val allProduction = sourceFiles("src/main/java")
        assertEquals(
            "Canonical validation must have one production implementation",
            1,
            allProduction.sumOf { Regex("\\bobject\\s+ContactValidation\\b").findAll(it.readText()).count() },
        )
        assertEquals(
            "Canonical contact must have one production definition",
            1,
            allProduction.sumOf { Regex("\\bdata\\s+class\\s+CanonicalContact\\b").findAll(it.readText()).count() },
        )
    }

    @Test
    fun gateCDependencyAndPermissionPolicyIsFailClosed() {
        val productionSources = sourceFiles("src/main")
        val productionText = productionSources.joinToString("\n") { it.readText() }
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val build = projectFile("build.gradle.kts").readText()
        val catalog = projectFile("gradle/libs.versions.toml").readText()

        listOf(
            "java.net.HttpURLConnection",
            "java.net.Socket",
            "java.net.URL(",
            "java.net.http.",
        ).forEach { forbidden ->
            assertFalse("Gate C must not implement direct boundary access: $forbidden", productionText.contains(forbidden))
        }
        productionSources.filterNot { source ->
            source.absolutePath.replace('\\', '/').contains("/data/android/provider/") ||
                source.name == "AndroidAccountRemovalCoordinator.kt"
        }.forEach { source ->
            listOf(
                "android.provider.ContactsContract",
                "android.content.ContentResolver",
                "android.content.ContentProviderOperation",
            ).forEach { forbidden ->
                // The owner-approved system photo picker reads one selected document; it is
                // not a ContactsProvider gateway and must not gain contacts/write access.
                val selectedImageRead = source.name == "SelectedContactImageReader.kt" &&
                    forbidden == "android.content.ContentResolver"
                assertFalse(
                    "Only the replaceable Android provider boundary may use $forbidden: ${source.name}",
                    !selectedImageRead && source.readText().contains(forbidden),
                )
            }
        }
        val gateDWire = productionSources.single { it.name == "ProtonGateDProductionComposition.kt" }.readText()
        val gateCRuntime = productionSources.single { it.name == "ProtonGateCRuntime.kt" }.readText()
        productionSources.filterNot { it.name == "ProtonGateDProductionComposition.kt" }.forEach { source ->
            assertFalse("Only the replaceable Gate D wire boundary may declare Retrofit routes", source.readText().contains("retrofit2."))
        }
        assertTrue(gateDWire.contains("interface ProtonGateDWireApi : BaseRetrofitApi"))
        assertFalse(gateDWire.contains("OkHttpClient.Builder"))
        assertFalse(gateDWire.contains("https://"))
        val gateDComposition = gateCRuntime.substringAfter("val gateD = ProtonGateDComposition(")
            .substringBefore("return ProtonGateCRuntime(")
        assertTrue(gateDComposition.contains("inventory = publicContactGateway"))
        assertFalse(gateDComposition.contains("inventory = ProtonRichInventoryAdapter"))
        productionSources.filterNot {
            it.name in setOf("ProtonGateCNetwork.kt", "ProtonGateDProductionComposition.kt")
        }.forEach { source ->
            assertFalse("Only the Gate C composition root may configure OkHttp", source.readText().contains("okhttp3."))
        }
        assertTrue(catalog.contains("protonCore = \"36.8.0\""))
        assertEquals(23, Regex("module = \"me\\.proton\\.core:").findAll(catalog).count())
        assertEquals(23, Regex("implementation\\(libs\\.proton\\.(?!golib\\))").findAll(build).count())
        assertTrue(catalog.contains("protonGolib = \"2.10.0-2-go1.27.1\""))
        assertTrue(catalog.contains("module = \"com.patmanak.contako.crypto:android-golib\""))
        assertTrue(build.contains("requested.group == \"me.proton.crypto\" && requested.name == \"android-golib\""))
        assertTrue(build.contains("strictly(libs.versions.protonGolib.get())"))
        assertEquals(23, Regex("version\\.ref = \"protonCore\"").findAll(catalog).count())
        assertTrue(build.contains("requested.group == \"me.proton.core\""))
        assertTrue(build.contains("requested.version != protonCoreVersion"))
        assertTrue(build.contains("enableSplit = false"))
        assertTrue(build.contains("tasks.register(\"contakoJvmCheck\")"))
        assertFalse("Remote contact references must not trigger automatic host fetches", productionText.contains(
            "RemotePhotoFetcher",
        ))
        val humanVerificationSource = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/GateCInteractiveHumanVerification.kt",
        ).readText()
        assertFalse(humanVerificationSource.contains("endsWith(\".proton.me\""))
        assertTrue(humanVerificationSource.contains("deleteOrigin(HUMAN_VERIFICATION_ORIGIN)"))
        assertTrue(humanVerificationSource.contains("removeAllCookies { flush() }"))

        // Gate D reuses the sole ApiProvider; exactly one maintained contact/label remote is composed.
        assertEquals(1, Regex("ContactRemoteDataSourceImpl\\(").findAll(productionText).count())
        assertEquals(1, Regex("LabelRemoteDataSourceImpl\\(").findAll(productionText).count())
        assertEquals(1, Regex("ApiProvider\\(").findAll(productionText).count())
        assertTrue(manifest.contains("android:name=\"android.permission.INTERNET\""))
        assertTrue(manifest.contains("android:name=\"android.permission.ACCESS_NETWORK_STATE\""))
        listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_SYNC_SETTINGS",
            "android.permission.WRITE_SYNC_SETTINGS",
        ).forEach { permission ->
            assertTrue(manifest.contains("android:name=\"$permission\""))
        }
        assertFalse(manifest.contains("android.permission.GET_ACCOUNTS"))
        assertFalse(manifest.contains("android.permission.AUTHENTICATE_ACCOUNTS"))
        listOf(
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
        ).forEach { permission ->
            assertTrue(manifest.contains("android:name=\"$permission\" tools:node=\"remove\""))
        }
        assertTrue(manifest.contains("android:name=\"androidx.work.WorkManagerInitializer\""))
        assertEquals(12, Regex("androidx\\.work\\.[^\"]+").findAll(manifest).count())

        listOf("android-mail@", "android-mail-contako", "ProtonMailAndroid/", "mail.proton.me/api", "2026.8.1")
            .forEach { forbidden ->
                assertFalse("Forbidden identity, route, or artificial version found", productionText.contains(forbidden))
            }
        assertTrue(productionText.contains("Other_"))
        assertTrue(productionText.contains("+contako"))
        val version = Regex("val contakoReleaseVersion = \"([0-9]+\\.[0-9]+\\.[0-9]+)\"").find(build)
        assertNotNull("A single release version must be declared", version)
        assertTrue(build.contains("versionName = contakoReleaseVersion"))
        assertTrue(Regex("versionCode = ([0-9]+)").find(build)!!.groupValues[1].toInt() > 0)
        assertTrue(build.contains("buildConfigField(\"String\", \"PROTON_RELEASE_VERSION\""))
        val networkSource = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/ProtonGateCNetwork.kt",
        ).readText()
        assertTrue(networkSource.contains("BuildConfig.PROTON_RELEASE_VERSION"))
        assertTrue(networkSource.contains("ContakoApiClient.forReleaseVersion(releaseVersion)"))
        assertTrue(networkSource.contains("ContakoClientVersionValidator.forReleaseVersion(releaseVersion)"))
        assertTrue(networkSource.contains("retryOnConnectionFailure(false)"))
        assertFalse(networkSource.contains("0.2.0"))

        val runtimeSource = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/ProtonGateCRuntime.kt",
        ).readText()
        assertTrue(runtimeSource.contains("ContactRemoteDataSourceImpl(network.apiProvider)"))
        assertTrue(runtimeSource.contains("LabelRemoteDataSourceImpl(network.apiProvider)"))
        assertTrue(runtimeSource.contains("ProtonCoreUnlockedKeyHolderContextProvider(userManager, cryptoContext)"))
        assertTrue(runtimeSource.contains("richInventoryUnitStatus = ProtonRichInventoryUnitStatus.LIVE_VALIDATION_REQUIRED"))
        val protectedStorageSource = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/GateCProtectedStorageGuard.kt",
        ).readText()
        val gateCDatabaseSource = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/ProtonGateCDatabase.kt",
        ).readText()
        val gateCDatabaseSchema = projectFile(
            "schemas/com.patmanak.contako.data.proton.ProtonGateCDatabase/1.json",
        ).readText()
        val cryptoCreation = runtimeSource.indexOf("val cryptoContext = AndroidCryptoContext()")
        val protectedStorageGuard = runtimeSource.indexOf("GateCProtectedStorageGuard.requireAvailable(")
        val databaseCreation = runtimeSource.indexOf("val database = ProtonGateCDatabase.build(context, protectedStorage)")
        val accountRepositoryCreation = runtimeSource.indexOf("val accountRepository = AccountRepositoryImpl(")
        assertTrue(cryptoCreation >= 0)
        assertTrue(protectedStorageGuard > cryptoCreation)
        assertTrue(databaseCreation > protectedStorageGuard)
        assertTrue(accountRepositoryCreation > protectedStorageGuard)
        assertFalse(runtimeSource.contains(".isUsingKeyStore()"))
        assertTrue(protectedStorageSource.contains("keyStoreCrypto.isUsingKeyStore()"))
        assertTrue(protectedStorageSource.contains("throw GateCProtectedStorageUnavailable()"))
        assertTrue(protectedStorageSource.contains("PROTECTED_STORAGE_UNAVAILABLE"))
        assertTrue(
            gateCDatabaseSource.contains(
                "protectedStorage: GateCProtectedStorageGuard.Proof",
            ),
        )
        assertTrue(gateCDatabaseSource.contains("exportSchema = true"))
        assertTrue(gateCDatabaseSchema.contains("\"version\": 1"))
        assertTrue(gateCDatabaseSchema.contains("\"tableName\": \"AccountEntity\""))
        assertTrue(gateCDatabaseSchema.contains("\"tableName\": \"SessionEntity\""))
        val vCardCryptoSource = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/ProtonContactVCardCodec.kt",
        ).readText()
        assertFalse(vCardCryptoSource.contains("decryptContactCard"))
        assertTrue(vCardCryptoSource.contains("decryptBoundedContactText(keyHolder, card.data,"))
        val boundedDecryptSource = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/ProtonBoundedContactDecryptor.kt",
        ).readText()
        assertTrue(boundedDecryptSource.contains("import me.proton.core.key.domain.decryptText"))
        assertTrue(boundedDecryptSource.contains("ring.decryptStream"))
        assertTrue(vCardCryptoSource.contains("keyHolder.verifyText(decrypted, signature)"))
        // Owner-approved wire type 1 has no signature; signed cards still fail verification closed.
        assertTrue(vCardCryptoSource.contains("val signature = card.signature"))
        assertTrue(vCardCryptoSource.contains("val verified = signature == null || try"))
        assertTrue(vCardCryptoSource.contains("if (!verified)"))
        assertTrue(vCardCryptoSource.contains("GatewayContactHydrationCategory.WIRE_CARD_VALIDATION"))
        assertTrue(vCardCryptoSource.contains("GatewayContactHydrationCategory.DECRYPT_OPERATION"))
        assertTrue(vCardCryptoSource.contains("GatewayContactHydrationCategory.SIGNATURE_VERIFICATION"))
        assertTrue(vCardCryptoSource.contains("GatewayContactHydrationCategory.PLAINTEXT_VCARD_NORMALIZATION"))
        assertTrue(vCardCryptoSource.contains("GatewayContactHydrationCategory.PLAINTEXT_VCARD_STRUCTURE"))
        assertTrue(vCardCryptoSource.contains("GatewayContactHydrationCategory.PLAINTEXT_VCARD_VERSION"))
        assertTrue(vCardCryptoSource.contains("GatewayContactHydrationCategory.PLAINTEXT_VCARD_PARSER"))
        assertTrue(vCardCryptoSource.contains("GatewayContactHydrationCategory.PLAINTEXT_BOUNDS"))
        assertTrue(vCardCryptoSource.contains("validateSingleCompleteHydratedVCard(plain)"))
        assertTrue(
            vCardCryptoSource.contains(
                "private fun parseSingle(value: String): VCard = parseSingleCompleteVCard(value)",
            ),
        )
        assertTrue(vCardCryptoSource.contains("parsed.version != VCardVersion.V4_0"))
        val coordinatorSource = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/ProtonCoreSessionCoordinator.kt",
        ).readText()
        assertTrue(runtimeSource.contains("MAX_SECOND_FACTOR_CODE_CHARS = 128"))
        assertTrue(runtimeSource.contains("withContext(NonCancellable)"))
        assertTrue(runtimeSource.contains("owned.fill(0)"))
        assertTrue(runtimeSource.contains("authDiagnostic: GateCAuthDiagnostic = GateCAuthDiagnostic.Disabled"))
        assertTrue(runtimeSource.contains("GATE_C_AUTH_DIAGNOSTIC_REQUIRED"))
        assertTrue(runtimeSource.contains("diagnostic.onFailure(error.toGateCAuthDiagnostic())"))
        assertTrue(coordinatorSource.contains("beginLoginAttempt"))
        assertTrue(coordinatorSource.contains("refreshFlights"))
        assertTrue(coordinatorSource.contains("return refreshSingleFlight(session)"))
        assertTrue(coordinatorSource.contains("withLock(sessionId) { refreshSessionStrict(session) }"))
        assertTrue(coordinatorSource.contains("validateSessionIdentity(session, refreshed)"))
        assertTrue(coordinatorSource.contains("cleanupRejectedSession(session)"))
        assertTrue(coordinatorSource.contains("actual.userId != expected.userId"))
        assertTrue(coordinatorSource.contains("valueOrThrow"))
    }

    @Test
    fun mergedDebugAndReleaseManifestsContainOnlyApprovedPermissionsAndComponents() {
        listOf("debug", "release").forEach { variant ->
            val generatedRoot = projectFile("build/intermediates/merged_manifests/$variant")
            val mergedManifest = generatedRoot.walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .maxByOrNull(File::lastModified)
                ?: error("Missing merged $variant manifest")
            val merged = mergedManifest.readText()

            listOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS",
                "android.permission.READ_SYNC_SETTINGS",
                "android.permission.WRITE_SYNC_SETTINGS",
            ).forEach { required ->
                assertTrue("Merged $variant manifest is missing $required", merged.contains(required))
            }
            listOf(
                "android.permission.WAKE_LOCK",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.FOREGROUND_SERVICE",
                "androidx.work.WorkManagerInitializer",
                "androidx.work.impl.background.",
                "androidx.work.impl.foreground.",
                "androidx.work.impl.diagnostics.",
            ).forEach { forbidden ->
                assertFalse("Merged $variant Gate C manifest contains $forbidden", merged.contains(forbidden))
            }
            assertTrue(merged.contains("androidx.startup.InitializationProvider"))
            assertTrue(merged.contains("android:exported=\"false\""))
            assertTrue(merged.contains("com.patmanak.contako.android.account.ContakoAccountAuthenticatorService"))
            assertTrue(merged.contains("android.permission.BIND_ACCOUNT_AUTHENTICATOR"))
            assertTrue(merged.contains("com.patmanak.contako.android.sync.ContakoContactsSyncAdapterService"))
            assertTrue(merged.contains("android.permission.BIND_SYNC_ADAPTER"))
            assertFalse(merged.contains("android.permission.GET_ACCOUNTS"))
            assertFalse(merged.contains("android.permission.AUTHENTICATE_ACCOUNTS"))
        }
    }

    @Test
    fun backupTransferAndDatabaseBaselineAreFailClosed() {
        val build = projectFile("build.gradle.kts").readText()
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val legacyRules = projectFile("src/main/res/xml/backup_rules.xml").readText()
        val extractionRules = projectFile("src/main/res/xml/data_extraction_rules.xml").readText()
        val databaseSource = projectFile(
            "src/main/java/com/patmanak/contako/data/local/ContakoDatabase.kt",
        ).readText()
        val schema = projectFile(
            "schemas/com.patmanak.contako.data.local.ContakoDatabase/16.json",
        ).readText()
        val checkpointStore = projectFile(
            "src/main/java/com/patmanak/contako/data/local/RoomContactInventoryCheckpointStore.kt",
        ).readText()

        assertTrue(build.contains("namespace = \"com.patmanak.contako\""))
        assertTrue(build.contains("val contakoBaseApplicationId = \"com.patmanak.contako\""))
        assertTrue(build.contains("applicationId = contakoApplicationId"))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(build.contains("minSdk = 31"))
        assertTrue(build.contains("targetSdk = 36"))
        assertFalse(build.contains("applicationIdSuffix"))
        assertTrue(build.contains("isDebuggable = false"))
        assertTrue(build.contains("isMinifyEnabled = true"))
        assertTrue(build.contains("isShrinkResources = true"))

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))

        listOf("root", "file", "database", "sharedpref", "external").forEach { domain ->
            assertTrue("Legacy backup must exclude $domain", legacyRules.hasWholeDomainExclusion(domain))
            assertTrue("Cloud/D2D rules must exclude $domain", extractionRules.hasWholeDomainExclusion(domain))
        }
        listOf("device_root", "device_file", "device_database", "device_sharedpref").forEach { domain ->
            assertTrue("Device-protected data must exclude $domain", extractionRules.hasWholeDomainExclusion(domain))
        }
        assertTrue(extractionRules.contains("<cloud-backup"))
        assertTrue(extractionRules.contains("<device-transfer>"))

        assertTrue(databaseSource.contains("version = 18"))
        assertTrue(databaseSource.contains("exportSchema = true"))
        assertFalse(databaseSource.contains("fallbackToDestructiveMigration"))
        listOf(
            "MIGRATION_1_2",
            "MIGRATION_2_3",
            "MIGRATION_3_4",
            "MIGRATION_4_5",
            "MIGRATION_5_6",
            "MIGRATION_6_7",
            "MIGRATION_7_8",
            "MIGRATION_8_9",
            "MIGRATION_9_10",
            "MIGRATION_10_11",
            "MIGRATION_11_12",
            "MIGRATION_12_13",
            "MIGRATION_13_14",
            "MIGRATION_14_15",
            "MIGRATION_15_16",
            "MIGRATION_16_17",
            "MIGRATION_17_18",
        )
            .forEach { migration -> assertTrue(databaseSource.contains(migration)) }
        assertTrue(schema.contains("\"version\": 16"))
        assertTrue(schema.contains("\"tableName\": \"contacts\""))
        assertTrue(schema.contains("\"tableName\": \"outbox_mutations\""))
        assertTrue(schema.contains("\"tableName\": \"sync_account_status\""))
        assertTrue(schema.contains("\"tableName\": \"full_repair_progress\""))
        assertTrue(schema.contains("\"tableName\": \"android_projection_accounts\""))
        assertTrue(schema.contains("\"tableName\": \"android_projection_ledger\""))
        assertTrue(schema.contains("\"tableName\": \"android_provider_identity_owners\""))
        assertTrue(schema.contains("\"tableName\": \"android_provider_row_bindings\""))
        assertTrue(schema.contains("\"tableName\": \"android_projection_baselines\""))
        assertTrue(schema.contains("\"tableName\": \"android_group_projection_ledger\""))
        assertTrue(schema.contains("\"tableName\": \"android_group_projection_baselines\""))
        assertTrue(schema.contains("\"tableName\": \"android_group_membership_projection_ledger\""))
        assertTrue(schema.contains("\"tableName\": \"android_group_membership_baselines\""))
        assertTrue(schema.contains("\"tableName\": \"android_group_membership_commit_receipts\""))
        assertTrue(schema.contains("\"tableName\": \"android_group_provider_write_journal\""))
        assertTrue(schema.contains("\"tableName\": \"android_photo_provider_write_journal\""))
        listOf(
            "state",
            "requires_reconciliation",
            "device_elapsed_realtime_millis",
            "server_offset_millis",
            "uncertainty_millis",
            "interval_earliest_epoch_millis",
            "interval_latest_epoch_millis",
            "clock_jump_detected",
        ).forEach { column -> assertTrue(schema.contains("\"columnName\": \"$column\"")) }
        listOf(
            "contact_inventory_checkpoints",
            "contact_inventory_entries",
            "contact_inventory_groups",
            "contact_inventory_email_memberships",
            "contact_inventory_email_groups",
        ).forEach { table -> assertTrue(schema.contains("\"tableName\": \"$table\"")) }
        assertTrue(checkpointStore.contains("database.withTransaction"))
        assertTrue(checkpointStore.contains("compareAndSetGeneration"))
        assertTrue(checkpointStore.contains("AFTER_COMMIT"))
        assertFalse(checkpointStore.contains("android.util.Log"))
    }

    @Test
    fun productionSourcesDoNotLogPayloadsOrPersistAuthenticationFields() {
        val kotlinSources = sourceFiles("src/main/java")
        val forbiddenLogging = listOf("android.util.Log", "Timber.", "println(", "printStackTrace(")
        kotlinSources.forEach { source ->
            val text = if (source.name == "SanitizedDiagnosticLog.kt") {
                val sink = source.readText()
                val call = "android.util.Log.i(\"ContakoDiagnostic\", renderDiagnostic(event))"
                assertEquals(1, Regex(Regex.escape(call)).findAll(sink).count())
                assertTrue(sink.contains("fun write(event: SanitizedDiagnosticEvent)"))
                val guard = "if (BuildConfig.SANITIZED_DIAGNOSTICS || (BuildConfig.SYNC_DIAGNOSTICS && isSyncDiagnostic(event)))"
                assertTrue(Regex(Regex.escape(guard) + " \\{\\s*runCatching \\{ " +
                    Regex.escape(call) + " \\}\\s*\\}").containsMatchIn(sink))
                sink.replace(call, "")
            } else source.readText()
            forbiddenLogging.forEach { forbidden ->
                assertFalse("${source.name} contains a forbidden logging surface", text.contains(forbidden))
            }
        }

        val persistenceText = sourceFiles("src/main/java/com/patmanak/contako/data/local")
            .joinToString("\n") { it.readText() }
        listOf(
            Regex("\\bpassword\\b", RegexOption.IGNORE_CASE),
            Regex("\\botp\\b", RegexOption.IGNORE_CASE),
            Regex("\\brecovery(Code)?s?\\b", RegexOption.IGNORE_CASE),
            Regex("\\bsessionToken\\b", RegexOption.IGNORE_CASE),
            Regex("\\bprivateKey\\b", RegexOption.IGNORE_CASE),
        ).forEach { forbidden ->
            assertFalse("Authentication material must not enter local persistence", forbidden.containsMatchIn(persistenceText))
        }
    }

    private fun sourceFiles(relativeDirectory: String): List<File> =
        projectFile(relativeDirectory).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList()

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
        )
        return candidates.firstOrNull(File::exists)
            ?: error("Missing project file: $relativePath")
    }

    private fun String.hasWholeDomainExclusion(domain: String): Boolean =
        Regex("<exclude\\s+domain=\\\"$domain\\\"\\s+path=\\\"\\.\\\"\\s*/>").containsMatchIn(this)
}
