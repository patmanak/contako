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
            fixture.copy(rawContact = fixture.rawContact.copy(deleted = true)),
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
