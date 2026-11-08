package com.patmanak.contako.data.android.provider

import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.AndroidLedgerCasResult
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidLinkedValueRole
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContactEntity
import com.patmanak.contako.data.local.ContactValueEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.data.local.toEntity
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.RoomMutationExecutionStore
import com.patmanak.contako.data.local.RoomRemoteCanonicalReconciliationStore
import com.patmanak.contako.data.local.toDomain
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.domain.repository.SaveResult
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.domain.entity.UserId
import com.patmanak.contako.data.local.AndroidProjectionBaselineEntity
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAndroidProviderIdentityResolverDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
        seedScope()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized && database.isOpen) database.close()
        if (::context.isInitialized) context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun pendingProjectionBindingAttachesAtomicallyAndSurvivesRestart() {
        val binding = AndroidDurableValueBinding(
            canonicalValueId = "name-id",
            linkedCanonicalValueIds = mapOf(AndroidLinkedValueRole.PHONETIC_NAME to "phonetic-id"),
        )
        assertEquals(
            AndroidPendingBindingRegistration.Registered,
            resolver().registerPendingBindings(
                ANDROID_ACCOUNT,
                CONTACT_ID,
                RAW_CONTACT_ID,
                listOf(AndroidPendingProviderBinding(AndroidRowKind.STRUCTURED_NAME, binding)),
            ),
        )

        reopenDatabase()
        val first = resolver().resolve(
            listOf(claim(101, AndroidRowKind.STRUCTURED_NAME, "name-id", binding.linkedCanonicalValueIds)),
        ) as AndroidProviderIdentityResolution.Bound
        assertEquals(binding, first.bindingsByProviderRowId[101L])
        assertTrue(database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).all {
            it.state == "ATTACHED" && it.dataRowLocator == 101L
        })

        reopenDatabase()
        val replay = resolver().resolve(
            listOf(claim(101, AndroidRowKind.STRUCTURED_NAME, "name-id", binding.linkedCanonicalValueIds)),
        ) as AndroidProviderIdentityResolution.Bound
        assertEquals(binding, replay.bindingsByProviderRowId[101L])
    }

    @Test fun anExistingOrganizationCanAddATitleOnlyAfterExactPendingReadbackAndSurviveRestart() = runBlocking {
        val fixture = seedCompanionTransition(AndroidRowKind.ORGANIZATION, emptyMap(), mapOf(AndroidLinkedValueRole.TITLE to "new-title"))
        val before = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
        assertEquals(AndroidProviderIdentityResolution.Divergence, resolver().resolve(fixture.claims()))
        assertEquals(before, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
        reopenDatabase() // Provider commit happened, but no Room completion survived.
        assertTrue(recoverCompanions(fixture))
        val resolved = resolver().resolve(fixture.claims()) as AndroidProviderIdentityResolution.Bound
        assertEquals(mapOf(AndroidLinkedValueRole.TITLE to "new-title"), resolved.bindingsByProviderRowId[801L]!!.linkedCanonicalValueIds)
        val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
        assertEquals("WRITE_PENDING", ledger.projectionState)
        assertEquals(fixture.baselineFingerprint, ledger.androidBaselineFingerprint)
        reopenDatabase()
        assertTrue(resolver().resolve(fixture.claims()) is AndroidProviderIdentityResolution.Bound)
    }

    @Test fun replacingAndRemovingRoleOrPhoneticCompanionsPreservesTheAttachedPrimary() = runBlocking {
        for ((kind, role) in listOf(AndroidRowKind.ORGANIZATION to AndroidLinkedValueRole.ROLE,
                AndroidRowKind.STRUCTURED_NAME to AndroidLinkedValueRole.PHONETIC_NAME)) {
            for (replacement in listOf("replacement", null)) {
                resetScope()
                val fixture = seedCompanionTransition(kind, mapOf(role to "old-companion"),
                    replacement?.let { mapOf(role to it) }.orEmpty())
                val beforePrimary = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
                    .single { it.role == "PRIMARY" && it.kind == kind.name }
                assertTrue(recoverCompanions(fixture))
                val rows = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
                assertEquals(beforePrimary, rows.single { it.canonicalValueId == beforePrimary.canonicalValueId })
                assertFalse(rows.any { it.canonicalValueId == "old-companion" })
                if (replacement != null) assertTrue(rows.any { it.canonicalValueId == replacement && it.role == role.name })
                assertTrue(resolver().resolve(fixture.claims()) is AndroidProviderIdentityResolution.Bound)
            }
        }
    }

    @Test fun aDetachedLegacyUpdateArmsANewIntentWithoutAdoptingACleanBaseline() = runBlocking {
        val fixture = seedCompanionTransition(AndroidRowKind.ORGANIZATION, emptyMap(),
            mapOf(AndroidLinkedValueRole.TITLE to "new-title"), state = "DETACHED")
        val before = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
        assertTrue(recoverCompanions(fixture, detached = true))
        val after = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
        assertEquals(before.revision + 1, after.revision)
        assertEquals("WRITE_PENDING", after.projectionState)
        assertEquals(CanonicalAndroidContactMapper().fingerprint(fixture.desired).sha256Hex, after.pendingProjectionFingerprint)
        assertEquals(before.androidBaselineFingerprint, after.androidBaselineFingerprint)
        assertEquals(fixture.baselineFingerprint, database.androidProjectionLedgerDao().getBaseline(ACCOUNT.value, CONTACT_ID)!!.fingerprint)
    }

    @Test fun companionRecoveryRejectsForgedStaleUnpreparedOrConcurrentEvidenceWithoutAnyMutation() = runBlocking {
        for (failure in listOf("clean", "wrong-pending", "baseline", "revision", "dirty", "payload", "claim", "foreign", "observation", "version",
                "detached-payload", "detached-claim", "detached-not-current")) {
            resetScope()
            var fixture = seedCompanionTransition(AndroidRowKind.ORGANIZATION, emptyMap(),
                mapOf(AndroidLinkedValueRole.TITLE to "new-title"),
                state = if (failure.startsWith("detached")) "DETACHED" else if (failure == "clean") "CLEAN" else "WRITE_PENDING")
            if (failure == "wrong-pending") {
                val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
                database.androidProjectionLedgerDao().update(ledger.copy(pendingProjectionFingerprint = "0".repeat(64)))
            }
            if (failure == "baseline") {
                val baseline = database.androidProjectionLedgerDao().getBaseline(ACCOUNT.value, CONTACT_ID)!!
                database.androidProjectionLedgerDao().upsertBaseline(baseline.copy(fingerprint = "1".repeat(64)))
            }
            if (failure == "dirty") fixture = fixture.copy(observation = fixture.observation.copy(
                rawContact = fixture.observation.rawContact.copy(dirty = true)))
            if (failure.endsWith("payload")) fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                if (it.dataRowId == 801L) it.copy(stringSlots = it.stringSlots.toMutableList().also { slots -> slots[3] = "Concurrent title" }) else it
            }))
            if (failure.endsWith("claim")) fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                if (it.dataRowId == 801L) it.copy(linkedValueIdsEncoding = encodeLinked(mapOf(AndroidLinkedValueRole.TITLE to "forged-title"))) else it
            }))
            if (failure == "foreign") {
                assertEquals(AndroidPendingBindingRegistration.Registered, resolver().registerPendingBindings(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID,
                    listOf(AndroidPendingProviderBinding(AndroidRowKind.NOTE, AndroidDurableValueBinding("new-title", emptyMap())))))
                assertTrue(resolver().resolve(listOf(claim(999, AndroidRowKind.NOTE, "new-title"))) is AndroidProviderIdentityResolution.Bound)
            }
            val rows = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
            val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)
            val owner = database.androidProviderIdentityDao().getOwner(ACCOUNT.value, CONTACT_ID, "new-title")
            assertFalse("RECOVERY_GUARD_$failure", recoverCompanions(fixture,
                detached = failure.startsWith("detached") && failure != "detached-not-current",
                revision = if (failure == "revision") fixture.revision + 1 else fixture.revision,
                current = failure != "observation", expectedVersion = if (failure == "version") 8 else 7))
            assertEquals(rows, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
            assertEquals(ledger, database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
            assertEquals(owner, database.androidProviderIdentityDao().getOwner(ACCOUNT.value, CONTACT_ID, "new-title"))
        }
    }

    @Test fun preparingCompanionsBeforeProviderWriteCannotConsumeTheOldObservation() = runBlocking {
        val fixture = seedCompanionTransition(AndroidRowKind.ORGANIZATION, emptyMap(), mapOf(AndroidLinkedValueRole.TITLE to "new-title"))
        val before = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
        assertFalse(recoverCompanions(fixture.copy(observation = fixture.oldObservation)))
        assertEquals(before, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
        assertTrue(resolver().resolve(fixture.copy(observation = fixture.oldObservation).claims()) is AndroidProviderIdentityResolution.Bound)
    }

    @Test fun anUnchangedNameDecompositionUsesOnlyItsCompletedBaselineReceipt() = runBlocking {
        for (failure in listOf("none", "name-changed", "name-locator-changed", "baseline-missing")) {
            resetScope()
            var fixture = seedCompanionTransition(AndroidRowKind.ORGANIZATION, emptyMap(),
                mapOf(AndroidLinkedValueRole.TITLE to "new-title"), displayOnly = true)
            if (failure == "name-changed") fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                if (it.dataRowId == 601L) it.copy(stringSlots = it.stringSlots.toMutableList().also { slots -> slots[1] = "Changed" }) else it
            }))
            if (failure == "name-locator-changed") fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                if (it.dataRowId == 601L) it.copy(dataRowId = 602L) else it
            }))
            if (failure == "baseline-missing") database.androidProjectionLedgerDao().deleteBaseline(ACCOUNT.value, CONTACT_ID)
            val rows = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
            val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)
            assertEquals(failure == "none", recoverCompanions(fixture, expectedVersion = null))
            assertEquals(ledger, database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
            if (failure != "none") assertEquals(rows, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
        }
    }

    @Test fun aFreshExactWriteReceiptAllowsOnlyGeneratedNameDecompositionWithCompanionChanges() = runBlocking {
        for ((kind, role) in listOf(AndroidRowKind.ORGANIZATION to AndroidLinkedValueRole.TITLE,
                AndroidRowKind.STRUCTURED_NAME to AndroidLinkedValueRole.PHONETIC_NAME)) {
            for (failure in listOf("none", "missing-receipt", "stale-receipt", "dirty", "claim", "other-payload")) {
                resetScope()
                val previous = if (kind == AndroidRowKind.STRUCTURED_NAME) mapOf(role to "old-phonetic") else emptyMap()
                var fixture = seedCompanionTransition(kind, previous, mapOf(role to "new-companion"),
                    displayOnly = true, changedDisplayName = "Newest Alias")
                val targetLocator = if (kind == AndroidRowKind.STRUCTURED_NAME) 601L else 801L
                if (failure == "dirty") fixture = fixture.copy(observation = fixture.observation.copy(
                    rawContact = fixture.observation.rawContact.copy(dirty = true)))
                if (failure == "claim") fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                    if (it.dataRowId == targetLocator) it.copy(linkedValueIdsEncoding = encodeLinked(mapOf(role to "forged"))) else it
                }))
                if (failure == "other-payload") fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                    if (it.dataRowId == 801L) it.copy(stringSlots = it.stringSlots.toMutableList().also { slots -> slots[0] = "Concurrent company" }) else it
                }))
                val rows = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
                val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)
                assertEquals("FRESH_NAME_RECEIPT_$kind.$failure", failure == "none", recoverCompanions(fixture,
                    expectedVersion = when (failure) { "missing-receipt" -> null; "stale-receipt" -> 8L; else -> 7L }))
                assertEquals(ledger, database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
                if (failure != "none") assertEquals(rows, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
                else assertTrue(resolver().resolve(fixture.claims()) is AndroidProviderIdentityResolution.Bound)
            }
        }
    }

    @Test fun anAcknowledgedCodecEchoCanRecoverOnlyTheHistoricallyCommittedProjectionAndPendingEmail() = runBlocking {
        for (compound in listOf(false, true)) {
            resetScope()
            val fixture = seedAcknowledgedHistoricalProjection(compound)
            val canonical = database.contactDao().get(ACCOUNT.value, CONTACT_ID)!!
            val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
            val androidEvidence = database.contactSyncEvidenceDao().get(ACCOUNT.value, CONTACT_ID, "ANDROID")
            assertTrue(recoverCompanions(fixture, detached = true))
            assertEquals(canonical, database.contactDao().get(ACCOUNT.value, CONTACT_ID))
            assertEquals(androidEvidence, database.contactSyncEvidenceDao().get(ACCOUNT.value, CONTACT_ID, "ANDROID"))
            assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
            val after = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
            assertEquals(ledger.revision + 1, after.revision)
            assertEquals("DETACHED", after.projectionState)
            assertEquals(null, after.pendingProjectionFingerprint)
            assertEquals(ledger.canonicalProjectionFingerprint, after.canonicalProjectionFingerprint)
            val actual = claimedSnapshot(fixture.observation)
            assertEquals(CanonicalAndroidContactMapper().fingerprint(actual).sha256Hex, after.androidBaselineFingerprint)
            val stored = database.androidProjectionLedgerDao().getBaseline(ACCOUNT.value, CONTACT_ID)!!
            assertEquals(actual, AndroidContactSnapshotBinaryCodec.decode(stored.encodedSnapshot))
            val nextPlan = CanonicalAndroidContactMapper().planProjection(actual, canonical.toDomain())
            val organizationUpdate = nextPlan.operations.filterIsInstance<com.patmanak.contako.data.android.mapping.AndroidRowOperation.Update>()
                .single { it.desired.kind == AndroidRowKind.ORGANIZATION }
            assertEquals(canonical.toDomain().values.single { it.kind == ContactValueKind.TITLE }.id,
                organizationUpdate.desired.linkedCanonicalValueIds[AndroidLinkedValueRole.TITLE])
            assertNotEquals("submitted-title-a", organizationUpdate.desired.linkedCanonicalValueIds[AndroidLinkedValueRole.TITLE])
            assertTrue(database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
                .any { it.role == "TITLE" && it.canonicalValueId == "submitted-title-a" })
            if (compound) assertTrue(database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
                .any { it.canonicalValueId == "submitted-email-a" && it.state == "ATTACHED" && it.dataRowLocator == 502L })
            reopenDatabase()
            assertTrue(resolver().resolve(fixture.claims()) is AndroidProviderIdentityResolution.Bound)
        }
    }

    @Test fun aHistoricalCommitmentNeverAuthorizesChangedClaimsPayloadScopeOrForeignBindings() = runBlocking {
        for (failure in listOf("missing-hash", "changed-hash", "payload", "claim", "locator", "source", "epoch", "revision",
                "foreign", "pending-claim", "pending-attached-elsewhere", "observation", "baseline")) {
            resetScope()
            var fixture = seedAcknowledgedHistoricalProjection(compound = true)
            if (failure.endsWith("hash")) {
                val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
                database.androidProjectionLedgerDao().update(ledger.copy(canonicalProjectionFingerprint =
                    if (failure == "missing-hash") null else "0".repeat(64)))
            }
            if (failure == "payload") fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                if (it.dataRowId == 801L) it.copy(stringSlots = it.stringSlots.toMutableList().also { slots -> slots[3] = "Changed title" }) else it
            }))
            if (failure == "claim") fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                if (it.dataRowId == 801L) it.copy(linkedValueIdsEncoding = encodeLinked(mapOf(AndroidLinkedValueRole.TITLE to "forged"))) else it
            }))
            if (failure == "locator") fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                if (it.dataRowId == 801L) it.copy(dataRowId = 802) else it
            }))
            if (failure == "source") fixture = fixture.copy(observation = fixture.observation.copy(
                rawContact = fixture.observation.rawContact.copy(sourceIdentity = "another-source")))
            if (failure == "pending-claim") fixture = fixture.copy(observation = fixture.observation.copy(dataRows = fixture.observation.dataRows.map {
                if (it.dataRowId == 502L) it.copy(canonicalValueId = "unregistered-email") else it
            }))
            if (failure == "pending-attached-elsewhere") assertTrue(resolver().resolve(listOf(
                claim(999, AndroidRowKind.EMAIL, "submitted-email-a"))) is AndroidProviderIdentityResolution.Bound)
            if (failure == "foreign") {
                assertEquals(AndroidPendingBindingRegistration.Registered, resolver().registerPendingBindings(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID,
                    listOf(AndroidPendingProviderBinding(AndroidRowKind.NOTE, AndroidDurableValueBinding("submitted-title-a", emptyMap())))))
                assertTrue(resolver().resolve(listOf(claim(999, AndroidRowKind.NOTE, "submitted-title-a"))) is AndroidProviderIdentityResolution.Bound)
            }
            if (failure == "baseline") {
                val baseline = database.androidProjectionLedgerDao().getBaseline(ACCOUNT.value, CONTACT_ID)!!
                database.androidProjectionLedgerDao().upsertBaseline(baseline.copy(fingerprint = "1".repeat(64)))
            }
            val rows = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
            val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)
            val canonical = database.contactDao().get(ACCOUNT.value, CONTACT_ID)
            val baseline = database.androidProjectionLedgerDao().getBaseline(ACCOUNT.value, CONTACT_ID)!!
            val owner = database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)!!
            assertFalse("RETAINED_COMMITMENT_$failure", resolver().recoverProjectionBindings(
                AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT.value, owner.revision,
                    if (failure == "epoch") PROVIDER_EPOCH + 1 else PROVIDER_EPOCH),
                CONTACT_ID, if (failure == "revision") fixture.revision + 1 else fixture.revision,
                fixture.observation, fixture.desired, noPhoto, { "missing" }, { failure != "observation" },
                allowDetachedPreparation = true))
            assertEquals(rows, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
            assertEquals(ledger, database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
            assertEquals(canonical, database.contactDao().get(ACCOUNT.value, CONTACT_ID))
            val afterBaseline = database.androidProjectionLedgerDao().getBaseline(ACCOUNT.value, CONTACT_ID)!!
            assertEquals(baseline.fingerprint, afterBaseline.fingerprint)
            assertTrue(baseline.encodedSnapshot.contentEquals(afterBaseline.encodedSnapshot))
        }
    }

    private suspend fun seedAcknowledgedHistoricalProjection(compound: Boolean): CompanionFixture {
        val codec = ProtonContactVCardCodec()
        val remote = Contact(UserId(ACCOUNT.value), ContactId("source-id"), "Display Alias", emptyList())
        fun decode(prepared: com.patmanak.contako.data.proton.ProtonPreparedVCard) = codec.decode(ACCOUNT.value, remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, prepared.signed),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, prepared.encryptedPrivate)))
        val initial = decode(codec.encode(CanonicalContact(ACCOUNT.value, CONTACT_ID, displayName = "Display Alias",
            values = listOf(ContactValue("new-org", ContactValueKind.ORGANIZATION, "Fixture company", order = 0),
                ContactValue("new-email", ContactValueKind.EMAIL, "duplicate@example.test", order = 0))))).copy(
            id = CONTACT_ID, revision = 0, remoteVersion = "verified-v1")
        database.contactDao().upsert(initial.toEntity())
        database.contactDao().upsertValues(initial.values.map { it.toEntity(initial) })
        database.androidProjectionLedgerDao().bindAndroidAccountName(ACCOUNT.value, 0, ANDROID_ACCOUNT.value)
        val mapper = CanonicalAndroidContactMapper()
        fun located(snapshot: AndroidContactSnapshot) = snapshot.copy(rows = snapshot.rows.map {
            it.copy(identity = it.identity.copy(providerRowId = when (it.kind) {
                AndroidRowKind.STRUCTURED_NAME -> 601L
                AndroidRowKind.ORGANIZATION -> 801L
                else -> 501L + it.order
            }))
        })
        val baseline = located(mapper.project(initial)).let { snapshot -> snapshot.copy(rows = snapshot.rows.map {
            if (it.kind == AndroidRowKind.STRUCTURED_NAME) it.copy(components = it.components + mapOf(
                AndroidComponent.GIVEN_NAME to "Display", AndroidComponent.FAMILY_NAME to "Alias")) else it
        }) }
        assertEquals(AndroidPendingBindingRegistration.Registered, resolver().registerPendingBindings(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID,
            baseline.rows.map { AndroidPendingProviderBinding(it.kind, AndroidDurableValueBinding(it.identity.canonicalValueId, it.linkedCanonicalValueIds)) }))
        assertTrue(resolver().resolve(baseline.rows.map { claim(it.identity.providerRowId!!, it.kind, it.identity.canonicalValueId,
            it.linkedCanonicalValueIds) }) is AndroidProviderIdentityResolution.Bound)
        val added = listOf(ContactValue("submitted-title-a", ContactValueKind.TITLE, "Engineer", order = 0)) +
            if (compound) listOf(ContactValue("submitted-email-a", ContactValueKind.EMAIL, "duplicate@example.test", order = 1)) else emptyList()
        val saved = (RoomContactRepository(database).saveContact(initial.copy(values = initial.values + added)) as SaveResult.Saved).value
        val submittedProjection = located(mapper.project(saved))
        if (compound) {
            val email = submittedProjection.rows.single { it.identity.canonicalValueId == "submitted-email-a" }
            assertEquals(AndroidPendingBindingRegistration.Registered, resolver().registerPendingBindings(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID,
                listOf(AndroidPendingProviderBinding(email.kind, AndroidDurableValueBinding(email.identity.canonicalValueId, emptyMap())))))
        }
        val baselineFingerprint = mapper.fingerprint(baseline).sha256Hex
        database.androidProjectionLedgerDao().upsertBaseline(AndroidProjectionBaselineEntity(ACCOUNT.value, CONTACT_ID,
            baselineFingerprint, AndroidContactSnapshotBinaryCodec.encode(baseline)))
        val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
        // Exact state persisted by projection preparation before the lost completion.
        database.androidProjectionLedgerDao().update(ledger.copy(projectionState = "WRITE_PENDING",
            canonicalProjectionFingerprint = mapper.fingerprint(submittedProjection).sha256Hex,
            pendingProjectionFingerprint = mapper.fingerprint(submittedProjection).sha256Hex,
            androidBaselineFingerprint = baselineFingerprint))
        val execution = RoomMutationExecutionStore(database)
        val command = execution.eligible(ACCOUNT.value, Long.MAX_VALUE, 10).single()
        assertTrue(execution.claim(command, Long.MAX_VALUE))
        assertTrue(execution.acknowledgeAndFinish(command, RemoteMutationAcknowledgement("source-id", "verified-v2")))
        val echo = decode(codec.encode(saved))
        assertNotEquals("submitted-title-a", echo.values.single { it.kind == ContactValueKind.TITLE }.id)
        assertEquals(initial.values.single { it.kind == ContactValueKind.ORGANIZATION }.id,
            echo.values.single { it.kind == ContactValueKind.ORGANIZATION }.id)
        RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT,
            VerifiedContactCard(RemoteContactId("source-id"), RemoteVersion("verified-v2"), echo))
        val current = database.contactDao().get(ACCOUNT.value, CONTACT_ID)!!.toDomain()
        val observed = submittedProjection.copy(rows = submittedProjection.rows.map {
            if (it.kind == AndroidRowKind.STRUCTURED_NAME) baseline.rows.single { previous -> previous.kind == it.kind } else it
        })
        fun observation(snapshot: AndroidContactSnapshot) = AndroidStableRawContactObservation(
            AndroidOwnedRawContact(RAW_CONTACT_ID, CONTACT_ID, "source-id", false, false, 7),
            snapshot.rows.map(::compositeProviderRow).sortedBy { it.dataRowId })
        return CompanionFixture(current.revision, mapper.project(current), observation(observed), observation(baseline), baselineFingerprint)
    }

    private suspend fun resetScope() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
        seedScope()
    }

    private data class CompanionFixture(val revision: Long, val desired: AndroidContactSnapshot,
        val observation: AndroidStableRawContactObservation, val oldObservation: AndroidStableRawContactObservation,
        val baselineFingerprint: String) {
        fun claims() = observation.dataRows.map { row ->
            val kind = when (row.mimeType) {
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> AndroidRowKind.STRUCTURED_NAME
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> AndroidRowKind.EMAIL
                else -> AndroidRowKind.ORGANIZATION
            }
            val linked = row.linkedValueIdsEncoding?.split(',')?.associate { encoded ->
                val parts = encoded.split(':')
                AndroidLinkedValueRole.valueOf(parts[0]) to
                    java.util.Base64.getUrlDecoder().decode(parts[2]).toString(Charsets.UTF_8)
            }.orEmpty()
            AndroidProviderIdentityClaim(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID, row.dataRowId, kind,
                row.canonicalValueId, linked)
        }
    }

    private suspend fun seedCompanionTransition(kind: AndroidRowKind, before: Map<AndroidLinkedValueRole, String>,
        after: Map<AndroidLinkedValueRole, String>, state: String = "WRITE_PENDING", displayOnly: Boolean = false,
        changedDisplayName: String? = null): CompanionFixture {
        fun companions(map: Map<AndroidLinkedValueRole, String>) = map.map { (role, id) ->
            val valueKind = when (role) {
                AndroidLinkedValueRole.TITLE -> ContactValueKind.TITLE
                AndroidLinkedValueRole.ROLE -> ContactValueKind.ROLE
                AndroidLinkedValueRole.PHONETIC_NAME -> ContactValueKind.PHONETIC_NAME
            }
            ContactValue(id, valueKind, "Value $id", order = 0,
                components = if (role == AndroidLinkedValueRole.PHONETIC_NAME) mapOf("given" to "Value $id") else emptyMap())
        }
        val base = listOf(ContactValue("name-id", ContactValueKind.STRUCTURED_NAME, "Contact;Synthetic;;;", order = 0,
                components = if (displayOnly) emptyMap() else mapOf("given" to "Synthetic", "family" to "Contact")),
            ContactValue("org-id", ContactValueKind.ORGANIZATION, "Fixture company", order = 0))
        val old = CanonicalContact(ACCOUNT.value, CONTACT_ID, firstName = if (displayOnly) "" else "Synthetic",
            lastName = if (displayOnly) "" else "Contact", displayName = if (displayOnly) "Display Alias" else "Synthetic Contact",
            values = base + companions(before), remoteContactId = "source-id")
        database.contactDao().upsert(old.toEntity())
        database.contactDao().upsertValues(old.values.map { it.toEntity(old) })
        database.androidProjectionLedgerDao().bindAndroidAccountName(ACCOUNT.value, 0, ANDROID_ACCOUNT.value)
        val mapper = CanonicalAndroidContactMapper()
        fun located(snapshot: AndroidContactSnapshot) = snapshot.copy(rows = snapshot.rows.map {
            it.copy(identity = it.identity.copy(providerRowId = if (it.kind == AndroidRowKind.STRUCTURED_NAME) 601 else 801))
        })
        val baseline = located(mapper.project(old)).let { snapshot ->
            if (!displayOnly) snapshot else snapshot.copy(rows = snapshot.rows.map {
                if (it.kind == AndroidRowKind.STRUCTURED_NAME) it.copy(components = it.components + mapOf(
                    AndroidComponent.GIVEN_NAME to "Display", AndroidComponent.FAMILY_NAME to "Alias")) else it
            })
        }
        assertEquals(AndroidPendingBindingRegistration.Registered, resolver().registerPendingBindings(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID,
            baseline.rows.map { AndroidPendingProviderBinding(it.kind, AndroidDurableValueBinding(it.identity.canonicalValueId, it.linkedCanonicalValueIds)) }))
        assertTrue(resolver().resolve(baseline.rows.map { claim(it.identity.providerRowId!!, it.kind, it.identity.canonicalValueId,
            it.linkedCanonicalValueIds) }) is AndroidProviderIdentityResolution.Bound)
        val canonical = old.copy(revision = 1, values = base + companions(after), displayName = changedDisplayName ?: old.displayName)
        database.contactDao().upsert(canonical.toEntity())
        database.contactDao().deleteValues(ACCOUNT.value, CONTACT_ID)
        database.contactDao().upsertValues(canonical.values.map { it.toEntity(canonical) })
        val desired = located(mapper.project(canonical))
        val oldFingerprint = mapper.fingerprint(baseline).sha256Hex
        database.androidProjectionLedgerDao().upsertBaseline(AndroidProjectionBaselineEntity(ACCOUNT.value, CONTACT_ID,
            oldFingerprint, AndroidContactSnapshotBinaryCodec.encode(baseline)))
        val ledger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)!!
        database.androidProjectionLedgerDao().update(ledger.copy(projectionState = state, androidBaselineFingerprint = oldFingerprint,
            pendingProjectionFingerprint = mapper.fingerprint(desired).sha256Hex.takeIf { state == "WRITE_PENDING" }))
        fun observation(snapshot: AndroidContactSnapshot) = AndroidStableRawContactObservation(
            AndroidOwnedRawContact(RAW_CONTACT_ID, CONTACT_ID, "source-id", false, false, 7), snapshot.rows.map(::compositeProviderRow))
        assertEquals(kind, desired.rows.single { it.linkedCanonicalValueIds == after && it.kind == kind }.kind)
        val observed = if (!displayOnly) desired else desired.copy(rows = desired.rows.map {
            if (it.kind != AndroidRowKind.STRUCTURED_NAME) it
            else if (changedDisplayName == null) baseline.rows.single { previous -> previous.kind == it.kind }
            else it.copy(components = it.components + mapOf(AndroidComponent.GIVEN_NAME to changedDisplayName.substringBefore(' '),
                AndroidComponent.FAMILY_NAME to changedDisplayName.substringAfter(' ')))
        })
        return CompanionFixture(canonical.revision, desired, observation(observed), observation(baseline), oldFingerprint)
    }

    private fun compositeProviderRow(row: AndroidContactRow): AndroidOwnedDataRow {
        val components = if (row.kind == AndroidRowKind.STRUCTURED_NAME) mapOf(0 to AndroidComponent.DISPLAY_NAME,
            1 to AndroidComponent.GIVEN_NAME, 2 to AndroidComponent.FAMILY_NAME, 3 to AndroidComponent.PREFIX,
            4 to AndroidComponent.MIDDLE_NAME, 5 to AndroidComponent.SUFFIX, 6 to AndroidComponent.PHONETIC_GIVEN_NAME,
            7 to AndroidComponent.PHONETIC_MIDDLE_NAME, 8 to AndroidComponent.PHONETIC_FAMILY_NAME)
        else mapOf(0 to AndroidComponent.COMPANY, 3 to AndroidComponent.TITLE, 4 to AndroidComponent.DEPARTMENT, 5 to AndroidComponent.ROLE)
        return AndroidOwnedDataRow(requireNotNull(row.identity.providerRowId), RAW_CONTACT_ID,
            when (row.kind) {
                AndroidRowKind.STRUCTURED_NAME -> ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                AndroidRowKind.EMAIL -> ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                else -> ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
            },
            row.identity.canonicalValueId, row.order, false, encodeLinked(row.linkedCanonicalValueIds), row.isPrimary, row.isSuperPrimary,
            List(14) { if (row.kind == AndroidRowKind.EMAIL) row.value.takeIf { _ -> it == 0 }
                else components[it]?.let(row.components::get)?.takeIf(String::isNotEmpty) }, null)
    }

    private fun encodeLinked(linked: Map<AndroidLinkedValueRole, String>): String? = linked.entries.joinToString(",") { (role, id) ->
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))
        "${role.name}:${encoded.length}:$encoded"
    }.takeIf(String::isNotEmpty)

    private suspend fun recoverCompanions(fixture: CompanionFixture, detached: Boolean = false, revision: Long = fixture.revision,
        current: Boolean = true, expectedVersion: Long? = null): Boolean {
        val owner = database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)!!
        return resolver().recoverProjectionBindings(AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT.value, owner.revision, PROVIDER_EPOCH),
            CONTACT_ID, revision, fixture.observation, fixture.desired, noPhoto, { "missing" }, { current },
            allowDetachedPreparation = detached, expectedProviderVersion = expectedVersion)
    }

    @Test
    fun failingDiagnosticObserverCannotAuthorizePhotoIdentityChangeOrAttachOtherRows() {
        val originalPhoto = AndroidPendingProviderBinding(AndroidRowKind.PHOTO, AndroidDurableValueBinding("photo-id", emptyMap()))
        val pendingEmail = AndroidPendingProviderBinding(AndroidRowKind.EMAIL, AndroidDurableValueBinding("email-id", emptyMap()))
        assertEquals(AndroidPendingBindingRegistration.Registered, resolver().registerPendingBindings(
            ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID, listOf(originalPhoto, pendingEmail),
        ))
        assertTrue(resolver().resolve(listOf(claim(302, AndroidRowKind.PHOTO, "photo-id"))) is AndroidProviderIdentityResolution.Bound)
        val before = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).toSet()
        val details = mutableListOf<Pair<AndroidProviderIdentityFailure, AndroidRowKind?>>()
        val diagnosticResolver = RoomAndroidProviderIdentityResolver(database, ACCOUNT, PROVIDER_EPOCH) { reason, kind ->
            details += reason to kind
            throw IllegalStateException("Observer failure must remain isolated")
        }
        assertEquals(AndroidProviderIdentityResolution.Divergence, diagnosticResolver.resolve(listOf(
            claim(301, AndroidRowKind.EMAIL, "email-id"),
            claim(302, AndroidRowKind.PHOTO, "untrusted-replacement"),
        )))
        assertEquals(listOf(AndroidProviderIdentityFailure.ATTACHED_PRIMARY_MISMATCH to AndroidRowKind.PHOTO), details)
        assertEquals(before, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).toSet())
    }

    @Test
    fun hostileExistingClaimRejectsWholeBatchWithoutAttachingValidPendingRow() = runBlocking {
        database.contactDao().upsertValues(
            listOf(
                ContactValueEntity(
                    accountId = ACCOUNT.value,
                    contactId = CONTACT_ID,
                    ownerKey = OWNER_KEY,
                    id = "hidden-existing-id",
                    kind = "NOTE",
                    value = "hidden",
                    label = null,
                    position = 0,
                    isPrimary = true,
                    componentsEncoding = "",
                    metadataEncoding = "",
                    binaryReference = null,
                    preservationKey = null,
                ),
            ),
        )
        assertEquals(
            AndroidPendingBindingRegistration.Registered,
            resolver().registerPendingBindings(
                ANDROID_ACCOUNT,
                CONTACT_ID,
                RAW_CONTACT_ID,
                listOf(
                    AndroidPendingProviderBinding(
                        AndroidRowKind.EMAIL,
                        AndroidDurableValueBinding("projected-email-id", emptyMap()),
                    ),
                ),
            ),
        )

        assertEquals(
            AndroidProviderIdentityResolution.Divergence,
            resolver().resolve(
                listOf(
                    claim(201, AndroidRowKind.EMAIL, "projected-email-id"),
                    claim(202, AndroidRowKind.NOTE, "hidden-existing-id"),
                ),
            ),
        )
        val afterDivergence = database.androidProviderIdentityDao()
            .getAllForContact(ACCOUNT.value, CONTACT_ID)
        assertEquals(1, afterDivergence.size)
        assertEquals("PENDING", afterDivergence.single().state)
        assertEquals(null, afterDivergence.single().dataRowLocator)

        val accepted = resolver().resolve(
            listOf(claim(201, AndroidRowKind.EMAIL, "projected-email-id")),
        ) as AndroidProviderIdentityResolution.Bound
        assertEquals("projected-email-id", accepted.bindingsByProviderRowId[201L]?.canonicalValueId)
    }

    @Test
    fun unclaimedNewRowGetsFreshNonLocatorIdentityThatSurvivesRestart() {
        val first = resolver().resolve(
            listOf(claim(303, AndroidRowKind.PHONE, claimedPrimary = null)),
        ) as AndroidProviderIdentityResolution.Bound
        val allocated = requireNotNull(first.bindingsByProviderRowId[303L]).canonicalValueId
        assertNotEquals("303", allocated)
        assertTrue(allocated.startsWith("android-value-"))

        reopenDatabase()
        val replay = resolver().resolve(
            listOf(claim(303, AndroidRowKind.PHONE, claimedPrimary = null)),
        ) as AndroidProviderIdentityResolution.Bound
        assertEquals(allocated, replay.bindingsByProviderRowId[303L]?.canonicalValueId)
    }

    @Test
    fun duplicatePrimaryOrLinkedIdentityAndWrongScopeDivergeWithoutPersistence() {
        val duplicated = listOf(
            AndroidPendingProviderBinding(
                AndroidRowKind.STRUCTURED_NAME,
                AndroidDurableValueBinding(
                    "name-primary",
                    mapOf(AndroidLinkedValueRole.PHONETIC_NAME to "shared-id"),
                ),
            ),
            AndroidPendingProviderBinding(
                AndroidRowKind.EMAIL,
                AndroidDurableValueBinding("shared-id", emptyMap()),
            ),
        )
        assertEquals(
            AndroidPendingBindingRegistration.Divergence,
            resolver().registerPendingBindings(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID, duplicated),
        )
        assertTrue(database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).isEmpty())

        assertEquals(
            AndroidProviderIdentityResolution.Divergence,
            resolver(epoch = PROVIDER_EPOCH + 1).resolve(
                listOf(claim(401, AndroidRowKind.EMAIL, claimedPrimary = null)),
            ),
        )
        assertEquals(
            AndroidProviderIdentityResolution.Divergence,
            resolver().resolve(
                listOf(
                    claim(402, AndroidRowKind.EMAIL, claimedPrimary = null).copy(
                        rawContactId = RAW_CONTACT_ID + 1,
                    ),
                ),
            ),
        )
        assertFalse(database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).isNotEmpty())
    }

    @Test
    fun attachedManagedEmailCannotBecomeUnknownOrProviderLocalAtSamePhysicalRowWithoutGuardFailure() = runBlocking {
        attachGuardedEmail()
        val beforeBindings = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
        val beforeContact = database.contactDao().get(ACCOUNT.value, CONTACT_ID)
        val beforeLedger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)
        val beforeIntent = database.outboxDao().getAll(ACCOUNT.value)
        reopenDatabase()

        for (mime in listOf("vnd.fixture/opaque", "vnd.sec.cursor.item/profile_card")) {
            val route = AndroidProviderMimeRouter().route(RAW_CONTACT_ID,
                listOf(opaqueGuardRow(901, mime, claimedId = "untrusted-replacement")))
            val failure = runCatching { resolver().requireUnmanagedRowsUnbound(CONTACT_ID, RAW_CONTACT_ID, route) }
                .exceptionOrNull()

            assertTrue(failure is AndroidProviderRowCodecException)
            assertEquals(AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE,
                (failure as AndroidProviderRowCodecException).category)
            assertEquals(beforeBindings, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
            assertEquals(beforeContact, database.contactDao().get(ACCOUNT.value, CONTACT_ID))
            assertEquals(beforeLedger, database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
            assertEquals(beforeIntent, database.outboxDao().getAll(ACCOUNT.value))
        }
    }

    @Test
    fun newOpaqueRowsCannotAttachSpoofedCanonicalClaimsOrChangeExistingManagedBinding() = runBlocking {
        attachGuardedEmail()
        val beforeBindings = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
        val beforeContact = database.contactDao().get(ACCOUNT.value, CONTACT_ID)
        val beforeLedger = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)
        val opaque = opaqueGuardRow(902, "vnd.fixture/opaque", claimedId = "guarded-email")
        val providerLocal = opaqueGuardRow(903, "vnd.android.cursor.item/rcs_data", claimedId = "guarded-email")
        val route = AndroidProviderMimeRouter().route(RAW_CONTACT_ID, listOf(opaque, providerLocal))

        resolver().requireUnmanagedRowsUnbound(CONTACT_ID, RAW_CONTACT_ID, route)
        reopenDatabase()

        assertEquals(beforeBindings, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
        assertEquals(beforeContact, database.contactDao().get(ACCOUNT.value, CONTACT_ID))
        assertEquals(beforeLedger, database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        assertEquals(listOf(opaque), route.unsupportedOwnedRows.rows)
        assertEquals(listOf(providerLocal), route.providerLocalRows.rows)
        assertTrue(route.contactRows.rows.isEmpty())
    }

    @Test
    fun unmanagedRowGuardDoesNotTreatOtherAccountCanonicalEpochOrRawScopeAsAttachedProof() = runBlocking {
        attachGuardedEmail()
        val beforeBindings = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
        val route = AndroidProviderMimeRouter().route(RAW_CONTACT_ID,
            listOf(opaqueGuardRow(901, "vnd.fixture/opaque", claimedId = "guarded-email")))

        resolver(epoch = PROVIDER_EPOCH + 1).requireUnmanagedRowsUnbound(CONTACT_ID, RAW_CONTACT_ID, route)
        RoomAndroidProviderIdentityResolver(database, AccountScope("foreign-guard-account"), PROVIDER_EPOCH)
            .requireUnmanagedRowsUnbound(CONTACT_ID, RAW_CONTACT_ID, route)
        resolver().requireUnmanagedRowsUnbound("other-canonical-contact", RAW_CONTACT_ID, route)
        val otherRaw = RAW_CONTACT_ID + 1
        val otherRawRoute = AndroidProviderMimeRouter().route(otherRaw,
            listOf(opaqueGuardRow(901, "vnd.fixture/opaque", rawContactId = otherRaw, claimedId = "guarded-email")))
        resolver().requireUnmanagedRowsUnbound(CONTACT_ID, otherRaw, otherRawRoute)

        assertEquals(beforeBindings, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    private fun attachGuardedEmail() {
        assertEquals(AndroidPendingBindingRegistration.Registered, resolver().registerPendingBindings(
            ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID,
            listOf(AndroidPendingProviderBinding(AndroidRowKind.EMAIL, AndroidDurableValueBinding("guarded-email", emptyMap()))),
        ))
        assertTrue(resolver().resolve(listOf(claim(901, AndroidRowKind.EMAIL, "guarded-email")))
            is AndroidProviderIdentityResolution.Bound)
    }

    private fun opaqueGuardRow(id: Long, mime: String, rawContactId: Long = RAW_CONTACT_ID, claimedId: String?) =
        AndroidOwnedDataRow(id, rawContactId, mime, claimedId, null, false, null, false, false,
            listOf("Opaque provider-only payload") + List(13) { null }, byteArrayOf(1, 3, 5))

    private fun resolver(epoch: Long = PROVIDER_EPOCH) =
        RoomAndroidProviderIdentityResolver(database, ACCOUNT, epoch)

    @Test
    fun pendingEmailReinsertionRecoversAndSurvivesRestart() = runBlocking {
        val fixture = seedReinsertedEmail()
        assertEquals(AndroidProviderIdentityResolution.Divergence,
            resolver().resolve(listOf(claim(502, AndroidRowKind.EMAIL, "reused-email"))))
        assertTrue(recoverEmail(fixture))
        reopenDatabase()
        assertTrue(resolver().resolve(listOf(claim(502, AndroidRowKind.EMAIL, "reused-email"))) is AndroidProviderIdentityResolution.Bound)
        assertFalse(recoverEmail(fixture)) // already attached; no relocation on replay
        assertEquals(502L, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).single().dataRowLocator)
    }

    @Test
    fun emailRecoveryRejectsUnprovenPayloadScopeAndLocatorWithoutWrites() = runBlocking {
        val fixture = seedReinsertedEmail()
        val raw = fixture.rawContact
        val row = fixture.dataRows.single()
        val invalid = listOf(
            fixture.copy(rawContact = raw.copy(dirty = true)),
            fixture.copy(rawContact = raw.copy(sourceIdentity = "different-source")),
            fixture.copy(dataRows = listOf(row.copy(dataRowId = 501), row)),
            fixture.copy(dataRows = listOf(row.copy(stringSlots = row.stringSlots.toMutableList().also { it[0] = "changed@example.test" }))),
            fixture.copy(dataRows = listOf(row.copy(canonicalValueId = "unregistered-email"))),
        )
        invalid.forEach { assertFalse(recoverEmail(it, desiredObservation = fixture)) }
        assertFalse(recoverEmail(fixture, current = false))
        assertFalse(recoverEmail(fixture, revision = 1))
        assertFalse(recoverEmail(fixture, epoch = 1))
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
        database.androidProjectionLedgerDao().update(ledger.copy(projectionState = "CLEAN", pendingProjectionFingerprint = null))
        assertFalse(recoverEmail(fixture))
        assertEquals(501L, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).single().dataRowLocator)
    }

    @Test
    fun reinsertedNameAndPhoneticsRecoverFromPendingProjectionAndSurviveRestart() = runBlocking {
        val fixture = seedReinsertedName(completed = false)
        assertEquals(AndroidProviderIdentityResolution.Divergence, resolver().resolve(listOf(
            claim(602, AndroidRowKind.STRUCTURED_NAME, "reused-name", nameLinked),
        )))
        assertTrue(recoverName(fixture, nameSnapshot(fixture)))
        reopenDatabase()
        assertTrue(resolver().resolve(listOf(claim(602, AndroidRowKind.STRUCTURED_NAME, "reused-name", nameLinked)))
            is AndroidProviderIdentityResolution.Bound)
        val bindings = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
        assertEquals(2, bindings.size)
        assertTrue(bindings.all { it.dataRowLocator == 602L && it.state == "ATTACHED" })
        assertFalse(recoverName(fixture, nameSnapshot(fixture)))
    }

    @Test
    fun completedNameBaselineCanRecoverBeforePlanningNewCanonicalContent() = runBlocking {
        val fixture = seedReinsertedName(completed = true)
        val observed = nameSnapshot(fixture)
        // Newly desired content is not proof that an old provider row is unchanged.
        val desired = observed.copy(rows = observed.rows.map { it.copy(value = "Next display name") })
        assertTrue(recoverName(fixture, desired))
        assertTrue(resolver().resolve(listOf(claim(602, AndroidRowKind.STRUCTURED_NAME, "reused-name", nameLinked)))
            is AndroidProviderIdentityResolution.Bound)
    }

    @Test
    fun nameRecoveryRejectsChangedContentClaimsScopeAndStaleObservationWithoutWrites() = runBlocking {
        val fixture = seedReinsertedName(completed = true)
        val row = fixture.dataRows.single()
        val desired = nameSnapshot(fixture)
        val before = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).toSet()
        val invalid = listOf(
            fixture.copy(rawContact = fixture.rawContact.copy(dirty = true)),
            fixture.copy(rawContact = fixture.rawContact.copy(deleted = true), dataRows = emptyList()),
            fixture.copy(rawContact = fixture.rawContact.copy(sourceIdentity = "another-source")),
            fixture.copy(dataRows = listOf(row.copy(dataRowId = 601), row)),
            fixture.copy(dataRows = listOf(row.copy(canonicalValueId = "unknown-name"))),
            fixture.copy(dataRows = listOf(row.copy(linkedValueIdsEncoding = null))),
            fixture.copy(dataRows = listOf(row.copy(stringSlots = row.stringSlots.toMutableList().also { it[1] = "Changed" }))),
            fixture.copy(dataRows = listOf(row.copy(stringSlots = row.stringSlots.toMutableList().also { it[6] = "Changed phonetic" }))),
        )
        invalid.forEach { assertFalse(recoverName(it, desired)) }
        assertFalse(recoverName(fixture, desired, current = false))
        assertFalse(recoverName(fixture, desired, revision = 1))
        assertFalse(recoverName(fixture, desired, epoch = PROVIDER_EPOCH + 1))
        val baseline = requireNotNull(database.androidProjectionLedgerDao().getBaseline(ACCOUNT.value, CONTACT_ID))
        database.androidProjectionLedgerDao().upsertBaseline(baseline.copy(fingerprint = "0".repeat(64)))
        assertFalse(recoverName(fixture, desired))
        assertEquals(before, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).toSet())
    }

    @Test
    fun pendingNameBirthdayAndOrganizationRelocateTogetherAndSurviveRestart() = runBlocking {
        val fixture = seedMixedReinsertedRows()
        assertTrue(recoverName(fixture, claimedSnapshot(fixture)))
        reopenDatabase()
        val bindings = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID)
        assertEquals(6, bindings.size)
        assertTrue(bindings.all {
            it.state == "ATTACHED" && it.dataRowLocator == when (it.kind) {
                "STRUCTURED_NAME" -> 602L
                "BIRTHDAY" -> 702L
                "ORGANIZATION" -> 802L
                else -> error("Unexpected binding kind")
            }
        })
        assertFalse(recoverName(fixture, claimedSnapshot(fixture)))
    }

    @Test
    fun changedBirthdayOrStaleObservationCannotPartiallyMoveNameAndOrganization() = runBlocking {
        val fixture = seedMixedReinsertedRows()
        val desired = claimedSnapshot(fixture)
        val before = database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).toSet()
        val changed = fixture.copy(dataRows = fixture.dataRows.map { row ->
            if (row.dataRowId == 702L) row.copy(stringSlots = row.stringSlots.toMutableList().also {
                it[0] = "2001-03-01"
            }) else row
        })
        assertFalse(recoverName(changed, desired))
        assertFalse(recoverName(fixture, desired, current = false))
        assertEquals(before, database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).toSet())
    }

    private suspend fun seedMixedReinsertedRows(): AndroidStableRawContactObservation {
        val name = seedReinsertedName(completed = false)
        val organizationLinked = mapOf(AndroidLinkedValueRole.TITLE to "job-title", AndroidLinkedValueRole.ROLE to "job-role")
        assertEquals(AndroidPendingBindingRegistration.Registered, resolver().registerPendingBindings(
            ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID, listOf(
                AndroidPendingProviderBinding(AndroidRowKind.BIRTHDAY, AndroidDurableValueBinding("birthday", emptyMap())),
                AndroidPendingProviderBinding(AndroidRowKind.ORGANIZATION, AndroidDurableValueBinding("organization", organizationLinked)),
            ),
        ))
        assertTrue(resolver().resolve(listOf(
            claim(701, AndroidRowKind.BIRTHDAY, "birthday"),
            claim(801, AndroidRowKind.ORGANIZATION, "organization", organizationLinked),
        )) is AndroidProviderIdentityResolution.Bound)
        val linkedEncoding = organizationLinked.entries.joinToString(",") { (role, id) ->
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))
            "${role.name}:${encoded.length}:$encoded"
        }
        val birthday = AndroidOwnedDataRow(702, RAW_CONTACT_ID, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            "birthday", 0, false, null, false, false,
            List(14) { when (it) { 0 -> "2000-02-29"; 1 -> "3"; else -> null } }, null)
        val organization = AndroidOwnedDataRow(802, RAW_CONTACT_ID, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            "organization", 0, false, linkedEncoding, false, false,
            List(14) { when (it) { 0 -> "Fixture company"; 1 -> "1"; 3 -> "Fixture title"; 5 -> "Fixture role"; else -> null } }, null)
        val observation = name.copy(dataRows = name.dataRows + birthday + organization)
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
        database.androidProjectionLedgerDao().update(ledger.copy(
            pendingProjectionFingerprint = CanonicalAndroidContactMapper().fingerprint(claimedSnapshot(observation)).sha256Hex,
        ))
        return observation
    }

    private fun claimedSnapshot(observation: AndroidStableRawContactObservation) = AndroidProviderRowCodec(
        AndroidProviderIdentityResolver { claims -> AndroidProviderIdentityResolution.Bound(claims.associate {
            it.providerRowId to AndroidDurableValueBinding(requireNotNull(it.claimedCanonicalValueId), it.claimedLinkedCanonicalValueIds)
        }) }, noPhoto,
    ).decode(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID, observation.dataRows)

    private val nameLinked = mapOf(AndroidLinkedValueRole.PHONETIC_NAME to "phonetic-name")

    private suspend fun seedReinsertedName(completed: Boolean): AndroidStableRawContactObservation {
        val contact = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact
        database.contactDao().upsert(contact.copy(remoteContactId = "source-id"))
        database.androidProjectionLedgerDao().bindAndroidAccountName(ACCOUNT.value, 0, ANDROID_ACCOUNT.value)
        resolver().registerPendingBindings(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID,
            listOf(AndroidPendingProviderBinding(AndroidRowKind.STRUCTURED_NAME, AndroidDurableValueBinding("reused-name", nameLinked))))
        resolver().resolve(listOf(claim(601, AndroidRowKind.STRUCTURED_NAME, "reused-name", nameLinked)))
        val linked = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("phonetic-name".toByteArray(Charsets.UTF_8))
        val row = AndroidOwnedDataRow(602, RAW_CONTACT_ID, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            "reused-name", 0, false, "PHONETIC_NAME:${linked.length}:$linked", false, false,
            List(14) { when (it) { 0 -> "Synthetic Contact"; 1 -> "Synthetic"; 2 -> "Contact"; 6 -> "Synthetic phonetic"; else -> null } }, null)
        val observation = AndroidStableRawContactObservation(
            AndroidOwnedRawContact(RAW_CONTACT_ID, CONTACT_ID, "source-id", false, false, 7), listOf(row))
        val snapshot = nameSnapshot(observation)
        val fingerprint = CanonicalAndroidContactMapper().fingerprint(snapshot).sha256Hex
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
        database.androidProjectionLedgerDao().update(ledger.copy(
            projectionState = if (completed) "CLEAN" else "WRITE_PENDING",
            pendingProjectionFingerprint = fingerprint.takeUnless { completed },
            androidBaselineFingerprint = fingerprint.takeIf { completed },
        ))
        if (completed) {
            val baseline = snapshot.copy(rows = snapshot.rows.map { it.copy(identity = it.identity.copy(providerRowId = 601)) })
            database.androidProjectionLedgerDao().upsertBaseline(AndroidProjectionBaselineEntity(
                ACCOUNT.value, CONTACT_ID, fingerprint, AndroidContactSnapshotBinaryCodec.encode(baseline),
            ))
        }
        return observation
    }

    private fun nameSnapshot(observation: AndroidStableRawContactObservation) = AndroidProviderRowCodec(
        AndroidProviderIdentityResolver { claims -> AndroidProviderIdentityResolution.Bound(claims.associate {
            it.providerRowId to AndroidDurableValueBinding("reused-name", nameLinked)
        }) }, noPhoto,
    ).decode(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID, observation.dataRows)

    private suspend fun recoverName(
        observation: AndroidStableRawContactObservation, desired: AndroidContactSnapshot,
        current: Boolean = true, revision: Long = 0, epoch: Long = PROVIDER_EPOCH,
    ): Boolean {
        val account = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        return resolver().recoverProjectionBindings(
            AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT.value, account.revision, epoch),
            CONTACT_ID, revision, observation, desired, noPhoto, { "missing" }, { current })
    }

    private suspend fun seedReinsertedEmail(): AndroidStableRawContactObservation {
        val contact = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact
        database.contactDao().upsert(contact.copy(remoteContactId = "source-id"))
        database.androidProjectionLedgerDao().bindAndroidAccountName(ACCOUNT.value, 0, ANDROID_ACCOUNT.value)
        resolver().registerPendingBindings(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID,
            listOf(AndroidPendingProviderBinding(AndroidRowKind.EMAIL, AndroidDurableValueBinding("reused-email", emptyMap()))))
        resolver().resolve(listOf(claim(501, AndroidRowKind.EMAIL, "reused-email")))
        val row = AndroidOwnedDataRow(502, RAW_CONTACT_ID, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            "reused-email", 0, false, null, false, false,
            List(14) { when (it) { 0 -> "owned@example.test"; 1 -> "3"; else -> null } }, null)
        val observation = AndroidStableRawContactObservation(
            AndroidOwnedRawContact(RAW_CONTACT_ID, CONTACT_ID, "source-id", false, false, 7), listOf(row))
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID))
        database.androidProjectionLedgerDao().update(ledger.copy(projectionState = "WRITE_PENDING",
            pendingProjectionFingerprint = CanonicalAndroidContactMapper().fingerprint(emailSnapshot(observation)).sha256Hex))
        return observation
    }

    private val noPhoto = AndroidDurablePhotoCapture { _, _, _, _ -> error("UNEXPECTED_PHOTO") }

    private fun emailSnapshot(observation: AndroidStableRawContactObservation) = AndroidProviderRowCodec(
        AndroidProviderIdentityResolver { claims -> AndroidProviderIdentityResolution.Bound(claims.associate {
            it.providerRowId to AndroidDurableValueBinding("reused-email", emptyMap())
        }) }, noPhoto,
    ).decode(ANDROID_ACCOUNT, CONTACT_ID, RAW_CONTACT_ID, observation.dataRows)

    private suspend fun recoverEmail(
        observation: AndroidStableRawContactObservation,
        desiredObservation: AndroidStableRawContactObservation = observation,
        current: Boolean = true,
        revision: Long = 0,
        epoch: Long = PROVIDER_EPOCH,
    ): Boolean {
        val account = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        return resolver().recoverProjectionBindings(
            AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT.value, account.revision, epoch),
            CONTACT_ID, revision, observation, emailSnapshot(desiredObservation), noPhoto, { "missing" }, { current })
    }

    private fun claim(
        providerRowId: Long,
        kind: AndroidRowKind,
        claimedPrimary: String?,
        linked: Map<AndroidLinkedValueRole, String> = emptyMap(),
    ) = AndroidProviderIdentityClaim(
        accountName = ANDROID_ACCOUNT,
        canonicalContactId = CONTACT_ID,
        rawContactId = RAW_CONTACT_ID,
        providerRowId = providerRowId,
        kind = kind,
        claimedCanonicalValueId = claimedPrimary,
        claimedLinkedCanonicalValueIds = linked,
    )

    private suspend fun seedScope() {
        database.contactDao().upsert(
            ContactEntity(
                accountId = ACCOUNT.value,
                id = CONTACT_ID,
                ownerKey = OWNER_KEY,
                firstName = "Synthetic",
                lastName = "Contact",
                displayName = "Synthetic Contact",
                sortName = "Synthetic Contact",
                revision = 0,
                updatedAtEpochMillis = 1,
                remoteContactId = null,
                remoteVCardUid = null,
                remoteVersion = null,
                actionRequiredReasonsEncoding = "",
                pendingMutationRevision = null,
                conflictState = null,
                isDeleted = false,
            ),
        )
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(ACCOUNT)
        val attached = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "source-id")
        val adoption = ledger.acknowledgeAdoption(
            ACCOUNT,
            CONTACT_ID,
            attached.revision,
            AndroidRawContactLocator(PROVIDER_EPOCH, RAW_CONTACT_ID),
        )
        assertTrue(adoption is AndroidLedgerCasResult.Updated)
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun openDatabase() {
        database = ContakoDatabase.create(context, DATABASE_NAME)
    }

    private companion object {
        const val DATABASE_NAME = "v04-provider-identity-resolver.db"
        const val CONTACT_ID = "canonical-contact"
        const val OWNER_KEY = "provider-identity-account:canonical-contact"
        const val PROVIDER_EPOCH = 0L
        const val RAW_CONTACT_ID = 41L
        val ACCOUNT = AccountScope("provider-identity-account")
        val ANDROID_ACCOUNT = AndroidProviderAccountName("android-account@example.test")
    }
}
