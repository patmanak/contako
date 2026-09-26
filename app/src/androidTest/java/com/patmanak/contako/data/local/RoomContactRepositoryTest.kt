package com.patmanak.contako.data.local

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.ProductionServerClock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.repository.SaveValidationIssue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomContactRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun defaultUiAndNativeRepositoriesPersistVerifiedClockEvidence() = runBlocking {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        ProductionServerClock.calibration.observe(
            true, "api.protonmail.ch",
            DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(wall).atZone(ZoneOffset.UTC)),
            null, wall, elapsed, wall, elapsed,
        )
        val productionRepository = RoomContactRepository(database)
        val saved = productionRepository.saveContact(contact("clock-contact", "Clock")).requireSavedForTest()
        val uiEvidence = database.outboxDao().getAll(ACCOUNT).single()
        assertNotNull(uiEvidence.intervalEarliestEpochMillis)
        assertEquals(1_000L, uiEvidence.serverPrecisionMillis)
        database.withTransaction {
            val mutation = RoomAndroidCanonicalMutationStore(database, AccountScope(ACCOUNT))
            assertTrue(mutation.applyContactDelta(saved.revision, saved.copy(firstName = "Native clock"))
                is AndroidCanonicalMutationResult.Applied)
        }
        val nativeEvidence = database.outboxDao().getAll(ACCOUNT).single()
        assertNotNull(nativeEvidence.intervalEarliestEpochMillis)
        assertEquals(uiEvidence.serverOffsetMillis, nativeEvidence.serverOffsetMillis)
        assertFalse(nativeEvidence.clockJumpDetected)
    }

    @Test
    fun committedContactAndOutboxSurviveDatabaseRestart() = runBlocking {
        val committed = repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "contact-1",
                firstName = "Élodie",
                values = listOf(
                    ContactValue(
                        id = "email-1",
                        kind = ContactValueKind.EMAIL,
                        value = "elodie@example.test",
                        order = 0,
                        isPrimary = true,
                    ),
                ),
            ),
        ).requireSavedForTest()
        assertEquals(1L, committed.revision)

        reopenDatabase()

        val restored = repository.getContact(ACCOUNT, "contact-1")
        assertEquals("Élodie", restored?.firstName)
        assertEquals("elodie@example.test", restored?.values?.single()?.value)
        val mutation = database.outboxDao().getAll(ACCOUNT).single()
        assertEquals(MutationOperation.UPSERT.name, mutation.operation)
        assertEquals(1L, mutation.revision)
    }

    @Test
    fun invalidDraftSurvivesRestartAndIsBlockedFromPeer() = runBlocking {
        val draft = repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "draft-1",
                values = listOf(
                    ContactValue(
                        id = "email-1",
                        kind = ContactValueKind.EMAIL,
                        value = "invalid-email",
                        order = 0,
                    ),
                ),
            ),
        ).requireSavedForTest()
        assertEquals("MISSING_NAME", draft.actionRequiredReason)
        assertFalse(draft.isUploadEligible)

        reopenDatabase()

        val restored = repository.getContact(ACCOUNT, "draft-1")
        assertEquals("MISSING_NAME", restored?.actionRequiredReason)
        assertFalse(requireNotNull(restored).isUploadEligible)
        assertEquals(
            "INVALID_EMAIL,MISSING_NAME",
            database.outboxDao().getAll(ACCOUNT).single().blockedReason,
        )
    }

    @Test
    fun updateCoalescesAndDeleteDominatesOlderOutboxIntent() = runBlocking {
        val initial = repository.saveContact(contact("contact-1", "Ada")).requireSavedForTest()
        now++
        repository.saveContact(initial.copy(firstName = "Augusta"))

        val update = database.outboxDao().getAll(ACCOUNT).single()
        assertEquals(2L, update.revision)
        assertEquals(MutationOperation.UPSERT.name, update.operation)

        now++
        repository.deleteContact(ACCOUNT, "contact-1")

        assertEquals(emptyList<CanonicalContact>(), repository.observeContacts(ACCOUNT).first())
        val deletion = database.outboxDao().getAll(ACCOUNT).single()
        assertEquals(3L, deletion.revision)
        assertEquals(MutationOperation.DELETE.name, deletion.operation)
    }

    @Test
    fun sameBusinessIdRemainsAccountScoped() = runBlocking {
        repository.saveContact(contact("shared-id", "Ada", accountId = "account-a"))
        repository.saveContact(contact("shared-id", "Grace", accountId = "account-b"))

        assertEquals("Ada", repository.getContact("account-a", "shared-id")?.firstName)
        assertEquals("Grace", repository.getContact("account-b", "shared-id")?.firstName)
        assertNotNull(database.outboxDao().getAll("account-a").single())
        assertNotNull(database.outboxDao().getAll("account-b").single())
    }

    @Test
    fun localCreateRemainsSubjectToNameValidationUntilRemoteIdentityExists() = runBlocking {
        val created = repository.saveContact(contact("pending", "Ada")).requireSavedForTest()
        assertEquals(null, created.actionRequiredReason)

        val cleared = repository.saveContact(created.copy(firstName = "")).requireSavedForTest()

        assertEquals("MISSING_NAME", cleared.actionRequiredReason)
        assertFalse(cleared.isUploadEligible)
        assertEquals("MISSING_NAME", database.outboxDao().getAll(ACCOUNT).single().blockedReason)
    }

    @Test
    fun allCanonicalFamiliesAndPreservationEnvelopeRoundTrip() = runBlocking {
        val values = ContactValueKind.entries.mapIndexed { index, kind ->
            ContactValue(
                id = "value-$index",
                kind = kind,
                value = "value-$kind",
                label = "Custom $index",
                order = index,
                isPrimary = index == 0,
                components = if (kind == ContactValueKind.POSTAL_ADDRESS) {
                    // A structured address remains authoritative across persistence; legacy
                    // scalar-only addresses are normalized separately by PostalAddressPolicy.
                    mapOf("street" to "value-$kind", "country" to "")
                } else mapOf("year" to "", "month" to "03", "day" to "14"),
                metadata = mapOf("vcardType" to "X-${kind.name}", "pref" to index.toString()),
                binaryReference = if (kind == ContactValueKind.PHOTO) "private://photo/1" else null,
                preservationKey = if (kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY) "raw-1" else null,
            )
        }
        repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "complete-shape",
                displayName = "Complete Shape",
                values = values,
                preservationEnvelope = PreservationEnvelope(
                    rawProperties = mapOf("raw-1" to "X-UNKNOWN;TYPE=test:value"),
                    remoteBaseline = "BEGIN:VCARD\nVERSION:4.0\nEND:VCARD",
                ),
            ),
        )

        reopenDatabase()

        val restored = requireNotNull(repository.getContact(ACCOUNT, "complete-shape"))
        assertEquals(ContactValueKind.entries.toSet(), restored.values.map { it.kind }.toSet())
        assertEquals(values.map { it.components }, restored.values.map { it.components })
        assertEquals(values.map { it.metadata }, restored.values.map { it.metadata })
        assertEquals("private://photo/1", restored.values.first { it.kind == ContactValueKind.PHOTO }.binaryReference)
        assertEquals("X-UNKNOWN;TYPE=test:value", restored.preservationEnvelope?.rawProperties?.get("raw-1"))
    }

    @Test
    fun v05FieldFamilyMatrixSurvivesRoomReopenWithDraftExistingAndZeroDeltaStates() = runBlocking {
        val contacts = (1..13).map { index ->
            val id = "fx-c-${index.toString().padStart(3, '0')}"
            CanonicalContact(
                accountId = ACCOUNT,
                id = id,
                firstName = when (index) {
                    2 -> "Given-only"
                    4 -> ""
                    else -> "Fixture"
                },
                lastName = if (index == 3) "Family-only" else "",
                displayName = if (index == 1) "Display-only" else "",
                values = buildList {
                    if (index == 4) add(ContactValue(
                        id = "$id-email",
                        kind = ContactValueKind.EMAIL,
                        value = "email-only@example.test",
                        order = 0,
                    ))
                    if (index in setOf(7, 8, 9, 10, 11, 12)) add(ContactValue(
                        id = "$id-canary",
                        kind = ContactValueKind.UNKNOWN_VCARD_PROPERTY,
                        value = "opaque-$index",
                        label = "X-CONTAKO-$index",
                        order = 0,
                        preservationKey = "$id-canary-key",
                    ))
                },
                remoteContactId = if (index == 13) "synthetic-existing" else null,
                preservationEnvelope = if (index in setOf(7, 8, 9, 10, 11, 12)) PreservationEnvelope(
                    rawProperties = mapOf("$id-canary-key" to "X-CONTAKO-$index:opaque"),
                ) else null,
            )
        }
        val committed = contacts.map { repository.saveContact(it).requireSavedForTest() }
        assertFalse(committed.single { it.id == "fx-c-004" }.isUploadEligible)
        assertFalse(committed.single { it.id == "fx-c-013" }.actionRequiredReasons.contains("MISSING_NAME"))

        reopenDatabase()

        val restored = (1..13).map { index ->
            requireNotNull(repository.getContact(ACCOUNT, "fx-c-${index.toString().padStart(3, '0')}"))
        }
        assertEquals(committed.map { it.id }, restored.map { it.id })
        assertEquals(
            committed.associate { it.id to it.values },
            restored.associate { it.id to it.values },
        )
        assertEquals(
            committed.associate { it.id to it.preservationEnvelope },
            restored.associate { it.id to it.preservationEnvelope },
        )
        assertEquals(13, database.outboxDao().getAll(ACCOUNT).size)
    }

    @Test
    fun primaryNoteFixtureSurvivesEditDeleteAndDatabaseReopenWithoutHiddenDelta() = runBlocking {
        val archive = note("note-archive", "archive", 0, "7", "archive")
        val primary = note("note-primary", "projected", 1, "2", "android")
        val plain = note("note-plain", "plain", 2, null, "plain")
        val canary = ContactValue(
            id = "note-canary",
            kind = ContactValueKind.UNKNOWN_VCARD_PROPERTY,
            value = "preserve-opaque-note-marker",
            label = "X-CONTAKO-NOTE-CANARY",
            order = 0,
            preservationKey = "canary-key",
        )
        val envelope = PreservationEnvelope(
            rawProperties = mapOf("canary-key" to "X-CONTAKO-NOTE-CANARY;X-KEEP=yes:marker"),
            remoteBaseline = "synthetic-baseline",
        )
        val created = repository.saveContact(CanonicalContact(
            accountId = ACCOUNT,
            id = "fx-c-008",
            displayName = "Primary Note Fixture",
            values = listOf(archive, primary, plain, canary),
            preservationEnvelope = envelope,
        )).requireSavedForTest()
        val edited = repository.saveContact(created.copy(values = created.values.map {
            if (it.id == primary.id) it.copy(value = "edited") else it
        })).requireSavedForTest()

        reopenDatabase()

        val restored = requireNotNull(repository.getContact(ACCOUNT, "fx-c-008"))
        assertEquals(edited.values.associateBy(ContactValue::id), restored.values.associateBy(ContactValue::id))
        assertEquals(envelope, restored.preservationEnvelope)
        assertEquals(2L, restored.revision)

        val deleted = repository.saveContact(restored.copy(
            values = restored.values.filterNot { it.id == primary.id },
        )).requireSavedForTest()
        reopenDatabase()

        val afterDelete = requireNotNull(repository.getContact(ACCOUNT, "fx-c-008"))
        assertEquals(
            listOf(archive, plain, canary).associateBy(ContactValue::id),
            afterDelete.values.associateBy(ContactValue::id),
        )
        assertEquals(envelope, afterDelete.preservationEnvelope)
        assertEquals(3L, afterDelete.revision)
        assertEquals(deleted.values.associateBy(ContactValue::id), afterDelete.values.associateBy(ContactValue::id))
        assertEquals(3L, database.outboxDao().getAll(ACCOUNT).single().revision)
    }

    @Test
    fun dateFixtureSurvivesCustomEditDeleteAndDatabaseReopenWithoutRelationOrCanaryLoss() = runBlocking {
        val birthday = ContactValue("birthday", ContactValueKind.BIRTHDAY, "--02-29", order = 0)
        val anniversary = ContactValue("anniversary", ContactValueKind.ANNIVERSARY, "2000-02-29", order = 0)
        val custom = ContactValue(
            "custom-date",
            ContactValueKind.CUSTOM_DATE,
            "--07-14",
            label = "Jour spécial",
            order = 0,
            metadata = mapOf("syncDisposition" to "LOCAL_ONLY"),
        )
        val relation = ContactValue(
            "relation",
            ContactValueKind.RELATIONSHIP,
            "Synthetic relation",
            label = "X-REMOTE-REL",
            order = 0,
            metadata = mapOf("vcardTypeTokens" to "X-REMOTE-REL"),
        )
        val canary = ContactValue(
            "date-canary",
            ContactValueKind.UNKNOWN_VCARD_PROPERTY,
            "opaque-date-marker",
            label = "X-CONTAKO-DATE-CANARY",
            order = 0,
            preservationKey = "date-canary-key",
        )
        val envelope = PreservationEnvelope(
            rawProperties = mapOf("date-canary-key" to "X-CONTAKO-DATE-CANARY;X-KEEP=yes:marker"),
            remoteBaseline = "synthetic-date-baseline",
        )
        val created = repository.saveContact(CanonicalContact(
            accountId = ACCOUNT,
            id = "fx-c-009-010",
            displayName = "Date Fixture",
            values = listOf(birthday, anniversary, custom, relation, canary),
            preservationEnvelope = envelope,
        )).requireSavedForTest()
        val edited = repository.saveContact(created.copy(values = created.values.map { value ->
            if (value.id == custom.id) value.copy(value = "--12-31") else value
        })).requireSavedForTest()

        reopenDatabase()

        val restored = requireNotNull(repository.getContact(ACCOUNT, "fx-c-009-010"))
        assertEquals(edited.values.associateBy(ContactValue::id), restored.values.associateBy(ContactValue::id))
        assertEquals("--12-31", restored.values.single { it.id == custom.id }.value)
        assertEquals("LOCAL_ONLY", restored.values.single { it.id == custom.id }.metadata["syncDisposition"])
        assertEquals(relation, restored.values.single { it.id == relation.id })
        assertEquals(canary, restored.values.single { it.id == canary.id })
        assertEquals(envelope, restored.preservationEnvelope)

        repository.saveContact(restored.copy(values = restored.values.filterNot { it.id == custom.id }))
            .requireSavedForTest()
        reopenDatabase()

        val afterDelete = requireNotNull(repository.getContact(ACCOUNT, "fx-c-009-010"))
        assertEquals(listOf(birthday, anniversary, relation, canary).associateBy(ContactValue::id),
            afterDelete.values.associateBy(ContactValue::id))
        assertEquals(envelope, afterDelete.preservationEnvelope)
        assertEquals(3L, afterDelete.revision)
        assertEquals(3L, database.outboxDao().getAll(ACCOUNT).single().revision)
    }

    @Test
    fun imageFixtureSurvivesReorderEditDeleteAndDatabaseReopenWithoutLogoOrCanaryLoss() = runBlocking {
        val photoA = ContactValue(
            "photo-a", ContactValueKind.PHOTO, "https://img.example.test/a.png", order = 0,
            metadata = mapOf("vcardPref" to "3", "mediaType" to "image/png"),
            binaryReference = "private://photo-a",
        )
        val photoB = ContactValue(
            "photo-b", ContactValueKind.PHOTO, "https://img.example.test/b.jpg", order = 1,
            isPrimary = true, metadata = mapOf("vcardPref" to "1", "mediaType" to "image/jpeg"),
            binaryReference = "private://photo-b",
        )
        val logoA = ContactValue(
            "logo-a", ContactValueKind.LOGO, "https://img.example.test/logo-a.png", order = 0,
            metadata = mapOf("vcardPref" to "2"),
        )
        val logoB = ContactValue(
            "logo-b", ContactValueKind.LOGO, "https://img.example.test/logo-b.png", order = 1,
            isPrimary = true, metadata = mapOf("vcardPref" to "1"),
        )
        val canary = ContactValue(
            "image-canary", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "opaque-image-marker", order = 0,
            label = "X-CONTAKO-IMAGE-CANARY", preservationKey = "image-canary-key",
        )
        val envelope = PreservationEnvelope(
            rawProperties = mapOf("image-canary-key" to "X-CONTAKO-IMAGE-CANARY;X-KEEP=yes:marker"),
        )
        val created = repository.saveContact(CanonicalContact(
            accountId = ACCOUNT,
            id = "fx-c-011",
            displayName = "Image Fixture",
            values = listOf(photoA, photoB, logoA, logoB, canary),
            preservationEnvelope = envelope,
        )).requireSavedForTest()
        val edited = repository.saveContact(created.copy(values = created.values.map { value ->
            when (value.id) {
                photoA.id -> value.copy(order = 1, isPrimary = true, metadata = value.metadata + ("vcardPref" to "1"))
                photoB.id -> value.copy(order = 0, value = "https://img.example.test/edited.jpg", isPrimary = false,
                    metadata = value.metadata + ("vcardPref" to "2"))
                else -> value
            }
        })).requireSavedForTest()

        reopenDatabase()

        val restored = requireNotNull(repository.getContact(ACCOUNT, "fx-c-011"))
        assertEquals(edited.values.associateBy(ContactValue::id), restored.values.associateBy(ContactValue::id))
        assertEquals(envelope, restored.preservationEnvelope)
        val afterDelete = repository.saveContact(restored.copy(
            values = restored.values.filterNot { it.id == photoB.id },
        )).requireSavedForTest()
        reopenDatabase()

        val final = requireNotNull(repository.getContact(ACCOUNT, "fx-c-011"))
        assertEquals(afterDelete.values.associateBy(ContactValue::id), final.values.associateBy(ContactValue::id))
        assertEquals(setOf(logoA.id, logoB.id), final.valuesOf(ContactValueKind.LOGO).map(ContactValue::id).toSet())
        assertEquals(canary, final.values.single { it.id == canary.id })
        assertEquals(envelope, final.preservationEnvelope)
        assertEquals(3L, database.outboxDao().getAll(ACCOUNT).single().revision)
    }

    @Test
    fun emailOccurrenceMembershipRoundTripsAndGroupDeletionKeepsContact() = runBlocking {
        repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "member-contact",
                firstName = "Ada",
                values = listOf(
                    ContactValue(
                        id = "email-work",
                        kind = ContactValueKind.EMAIL,
                        value = "ada@example.test",
                        order = 0,
                    ),
                ),
            ),
        )
        repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT,
                id = "group-1",
                name = "Friends",
                memberships = listOf(GroupMembership("member-contact", "email-work")),
            ),
        )

        reopenDatabase()

        val group = repository.observeGroups(ACCOUNT).first().single()
        assertEquals(listOf(GroupMembership("member-contact", "email-work")), group.memberships)

        repository.deleteGroup(ACCOUNT, "group-1")

        assertEquals(emptyList<ContactGroup>(), repository.observeGroups(ACCOUNT).first())
        assertEquals("Ada", repository.getContact(ACCOUNT, "member-contact")?.firstName)
    }

    @Test
    fun groupDeleteContractCountsDistinctContactsAndSurvivesRestartWithOutbox() = runBlocking {
        val contacts = listOf(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "member-a",
                firstName = "Member A",
                values = listOf(
                    ContactValue("a-preferred", ContactValueKind.EMAIL, "a1@example.test", order = 0),
                    ContactValue("a-secondary", ContactValueKind.EMAIL, "a2@example.test", order = 1),
                ),
            ),
            CanonicalContact(
                accountId = ACCOUNT,
                id = "member-b",
                firstName = "Member B",
                values = listOf(ContactValue("b-email", ContactValueKind.EMAIL, "b@example.test", order = 0)),
            ),
            CanonicalContact(accountId = ACCOUNT, id = "no-email", firstName = "No Email"),
        )
        contacts.forEach { repository.saveContact(it).requireSavedForTest() }
        val savedGroup = repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT,
                id = "fx-g-003-many",
                name = "Same name",
                memberships = listOf(
                    GroupMembership("member-a", "a-preferred"),
                    GroupMembership("member-a", "a-secondary"),
                    GroupMembership("member-b", "b-email"),
                ),
            ),
        ).requireSavedForTest()
        assertEquals(2, savedGroup.memberCount)

        repository.deleteGroup(ACCOUNT, savedGroup.id)
        reopenDatabase()

        val tombstone = requireNotNull(database.contactGroupDao().get(ACCOUNT, savedGroup.id)).toDomain()
        assertEquals(2L, tombstone.revision)
        assertEquals(0, tombstone.memberCount)
        assertEquals(true, tombstone.isDeleted)
        assertEquals(3, contacts.count { repository.getContact(ACCOUNT, it.id) != null })
        val outbox = requireNotNull(database.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, savedGroup.id))
        assertEquals(MutationOperation.DELETE.name, outbox.operation)
        assertEquals(2L, outbox.revision)
    }

    @Test
    fun summaryEditPreservesPayloadAndUnchangedMembership() = runBlocking {
        repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "preserved",
                firstName = "Ada",
                values = listOf(
                    ContactValue("email", ContactValueKind.EMAIL, "ada@example.test", order = 0),
                ),
                preservationEnvelope = PreservationEnvelope(
                    rawProperties = mapOf("unknown" to "X-CUSTOM:value"),
                    remoteBaseline = "baseline",
                ),
            ),
        )
        repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT,
                id = "group",
                name = "Friends",
                memberships = listOf(GroupMembership("preserved", "email")),
            ),
        )

        val summaryWithoutHeavyPayload = repository.observeContacts(ACCOUNT).first().single()
        assertEquals(null, summaryWithoutHeavyPayload.preservationEnvelope)
        repository.saveContact(summaryWithoutHeavyPayload.copy(firstName = "Augusta"))

        val restored = requireNotNull(repository.getContact(ACCOUNT, "preserved"))
        assertEquals("baseline", restored.preservationEnvelope?.remoteBaseline)
        assertEquals("X-CUSTOM:value", restored.preservationEnvelope?.rawProperties?.get("unknown"))
        assertEquals(
            listOf(GroupMembership("preserved", "email")),
            repository.observeGroups(ACCOUNT).first().single().memberships,
        )
    }

    @Test
    fun removingAssignedEmailKeepsPendingGroupCreationIntent() = runBlocking {
        val saved = repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "contact",
                firstName = "Ada",
                values = listOf(
                    ContactValue("email", ContactValueKind.EMAIL, "ada@example.test", order = 0),
                ),
            ),
        ).requireSavedForTest()
        repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT,
                id = "group",
                name = "Friends",
                memberships = listOf(GroupMembership("contact", "email")),
            ),
        )

        repository.saveContact(saved.copy(values = emptyList()))

        assertEquals(emptyList<GroupMembership>(), repository.observeGroups(ACCOUNT).first().single().memberships)
        val groupIntent = database.outboxDao().getAll(ACCOUNT)
            .single { it.aggregateType == AggregateType.GROUP.name }
        assertEquals(MutationOperation.UPSERT.name, groupIntent.operation)
    }

    @Test
    fun deletingContactRemovesMembershipButKeepsGroupAndPayloadTombstone() = runBlocking {
        repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "contact",
                firstName = "Ada",
                values = listOf(
                    ContactValue("email", ContactValueKind.EMAIL, "ada@example.test", order = 0),
                ),
                preservationEnvelope = PreservationEnvelope(remoteBaseline = "baseline"),
            ),
        )
        repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT,
                id = "group",
                name = "Friends",
                memberships = listOf(GroupMembership("contact", "email")),
            ),
        )

        repository.deleteContact(ACCOUNT, "contact")

        assertEquals(emptyList<GroupMembership>(), repository.observeGroups(ACCOUNT).first().single().memberships)
        val tombstone = requireNotNull(repository.getContact(ACCOUNT, "contact"))
        assertEquals(true, tombstone.isDeleted)
        assertEquals("baseline", tombstone.preservationEnvelope?.remoteBaseline)
    }

    @Test
    fun groupMembershipRejectsNonEmailOccurrence() = runBlocking {
        repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT,
                id = "phone-contact",
                firstName = "Ada",
                values = listOf(
                    ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0),
                ),
            ),
        )

        val result = repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT,
                id = "invalid-group",
                name = "Invalid",
                memberships = listOf(GroupMembership("phone-contact", "phone")),
            ),
        )

        assertEquals(
            setOf(SaveValidationIssue.MEMBERSHIP_NOT_EMAIL),
            (result as SaveResult.Rejected).issues,
        )
    }

    @Test
    fun contactEditorSaveCommitsContactAndCompletePerEmailGroupVectorTogether() = runBlocking {
        val original = CanonicalContact(
            accountId = ACCOUNT,
            id = "contact-editor",
            firstName = "Before",
            values = listOf(
                ContactValue("email-a", ContactValueKind.EMAIL, "a@example.test", order = 0),
                ContactValue("email-b", ContactValueKind.EMAIL, "b@example.test", order = 1),
            ),
        )
        repository.saveContact(original)
        repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT,
                id = "friends",
                name = "Friends",
                memberships = listOf(GroupMembership(original.id, "email-a")),
            ),
        )
        repository.saveGroup(ContactGroup(accountId = ACCOUNT, id = "work", name = "Work"))
        repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT,
                id = "unmanaged",
                name = "Unmanaged",
                memberships = listOf(GroupMembership(original.id, "email-a")),
            ),
        )

        val saved = repository.saveContactWithGroupAssignments(
            contact = original.copy(firstName = "After"),
            assignments = setOf(
                ContactGroupAssignment("friends", "email-b"),
                ContactGroupAssignment("work", "email-a"),
            ),
            managedGroupIds = setOf("friends", "work"),
        ).requireSavedForTest()

        assertEquals("After", saved.firstName)
        reopenDatabase()
        assertEquals("After", repository.getContact(ACCOUNT, original.id)?.firstName)
        val assignments = repository.observeGroups(ACCOUNT).first().associate { group ->
            group.id to group.memberships
        }
        assertEquals(listOf(GroupMembership(original.id, "email-b")), assignments.getValue("friends"))
        assertEquals(listOf(GroupMembership(original.id, "email-a")), assignments.getValue("work"))
        assertEquals(listOf(GroupMembership(original.id, "email-a")), assignments.getValue("unmanaged"))
    }

    @Test
    fun groupOnlyEditorSaveDurablyReconcilesUnchangedSecondaryMembership() = runBlocking {
        val original = repository.saveContact(CanonicalContact(
            accountId = ACCOUNT,
            id = "assignment-replay",
            displayName = "Assignment replay",
            remoteContactId = "remote-assignment-replay",
            values = listOf(
                ContactValue("primary", ContactValueKind.EMAIL, "primary@example.test", order = 0),
                ContactValue("secondary", ContactValueKind.EMAIL, "secondary@example.test", order = 1),
            ),
        )).requireSavedForTest()
        listOf("old", "secondary", "new").forEach { groupId ->
            repository.saveGroup(ContactGroup(
                accountId = ACCOUNT, id = groupId, name = groupId, remoteLabelId = "remote-$groupId",
                memberships = when (groupId) {
                    "old" -> listOf(GroupMembership(original.id, "primary"))
                    "secondary" -> listOf(GroupMembership(original.id, "secondary"))
                    else -> emptyList()
                },
            )).requireSavedForTest()
        }
        // Model previously acknowledged intents in this isolated test database.
        database.outboxDao().getAll(ACCOUNT).forEach {
            assertEquals(1, database.outboxDao().deleteRevision(ACCOUNT, it.aggregateType, it.aggregateId, it.revision))
        }
        repository.saveContactWithGroupAssignments(
            original,
            setOf(ContactGroupAssignment("new", "primary"), ContactGroupAssignment("secondary", "secondary")),
            setOf("old", "secondary", "new"),
        ).requireSavedForTest()
        reopenDatabase()
        val groups = repository.observeGroups(ACCOUNT).first().associateBy(ContactGroup::id)
        assertTrue(groups.getValue("old").memberships.isEmpty())
        assertEquals(listOf(GroupMembership(original.id, "primary")), groups.getValue("new").memberships)
        assertEquals(listOf(GroupMembership(original.id, "secondary")), groups.getValue("secondary").memberships)
        val intents = database.outboxDao().getAll(ACCOUNT)
        assertEquals(1, intents.count { it.aggregateType == AggregateType.CONTACT.name })
        val groupIntents = intents.filter { it.aggregateType == AggregateType.GROUP.name }
        assertEquals(setOf("old", "secondary", "new"), groupIntents.map { it.aggregateId }.toSet())
        assertTrue(groupIntents.all { it.operation == MutationOperation.ASSIGNMENTS.name &&
            it.state == DurableMutationState.PENDING.name &&
            it.revision == groups.getValue(it.aggregateId).pendingMutationRevision })
    }

    @Test
    fun invalidContactEditorGroupVectorIsRejectedBeforeContactPersistence() = runBlocking {
        val result = repository.saveContactWithGroupAssignments(
            contact = CanonicalContact(
                accountId = ACCOUNT,
                id = "rejected-editor-contact",
                firstName = "Rejected",
                values = listOf(
                    ContactValue("email", ContactValueKind.EMAIL, "rejected@example.test", order = 0),
                ),
            ),
            assignments = setOf(ContactGroupAssignment("missing-group", "email")),
            managedGroupIds = setOf("missing-group"),
        )

        assertEquals(
            setOf(SaveValidationIssue.UNKNOWN_GROUP_ASSIGNMENT),
            (result as SaveResult.Rejected).issues,
        )
        assertEquals(null, repository.getContact(ACCOUNT, "rejected-editor-contact"))
        assertTrue(database.outboxDao().getAll(ACCOUNT).isEmpty())
    }

    @Test
    fun duplicateCanonicalValueIdentityIsRejectedBeforePersistence() = runBlocking {
        val duplicate = CanonicalContact(
            accountId = ACCOUNT,
            id = "duplicate",
            firstName = "Ada",
            values = listOf(
                ContactValue("same", ContactValueKind.EMAIL, "one@example.test", order = 0),
                ContactValue("same", ContactValueKind.PHONE, "+33123456789", order = 0),
            ),
        )

        val result = repository.saveContact(duplicate)

        assertEquals(
            setOf(SaveValidationIssue.DUPLICATE_VALUE_ID),
            (result as SaveResult.Rejected).issues,
        )
        assertEquals(null, repository.getContact(ACCOUNT, "duplicate"))
    }

    private fun contact(id: String, firstName: String, accountId: String = ACCOUNT) =
        CanonicalContact(accountId = accountId, id = id, firstName = firstName)

    private fun note(id: String, value: String, order: Int, pref: String?, origin: String) = ContactValue(
        id = id,
        kind = ContactValueKind.NOTE,
        value = value,
        order = order,
        metadata = buildMap {
            pref?.let { put("vcardPref", it) }
            put("unknownParam", origin)
        },
    )

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun openDatabase() {
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(
            database = database,
            clock = { now },
            idFactory = { "generated-id" },
        )
    }

    private companion object {
        const val ACCOUNT = "account@example.test"
        const val DATABASE_NAME = "contako-v01-test.db"
    }
}
