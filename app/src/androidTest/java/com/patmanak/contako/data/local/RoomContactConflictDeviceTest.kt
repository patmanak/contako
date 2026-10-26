package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.sync.MutationPreparation
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.ContactConflictChoice
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Isolated Room only; never uses the connected account or Android contacts provider. */
@RunWith(AndroidJUnit4::class)
class RoomContactConflictDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ContakoDatabase
    @Before fun setup() { context.deleteDatabase(NAME); db = ContakoDatabase.create(context, NAME) }
    @After fun cleanup() { db.close(); context.deleteDatabase(NAME) }

    private suspend fun seed(): CanonicalContact {
        val result = RoomContactRepository(db).saveContact(CanonicalContact(ACCOUNT, "contact", displayName = "Éloïse",
            remoteContactId = "remote", remoteVersion = "v1", values = listOf(ContactValue("note", ContactValueKind.NOTE,
                "local note", order = 0)), preservationEnvelope = PreservationEnvelope(mapOf("X-UNKNOWN" to "keep"))))
        return (result as SaveResult.Saved).value
    }
    private fun remote(version: String = "v2") = VerifiedContactCard(RemoteContactId("remote"), RemoteVersion(version),
        CanonicalContact(ACCOUNT, "remote", displayName = "Éloïse", values = listOf(ContactValue("remote-note", ContactValueKind.NOTE,
            "Proton note $version", order = 0)), remoteContactId = "remote", remoteVersion = version,
            preservationEnvelope = PreservationEnvelope(mapOf("X-UNKNOWN" to "remote keep"))))

    @Test fun privateChangeBlocksAndBothSnapshotsAndChoiceSurviveRestart() = runBlocking {
        val local = seed()
        assertTrue(RoomContactConflictStore(db).prepare(ACCOUNT, local.id, local.revision, remote()) is MutationPreparation.ActionRequired)
        val detail = requireNotNull(RoomContactConflictStore(db).detail(ACCOUNT, local.id))
        assertEquals("local note", detail.local.values.single().value)
        assertEquals("Proton note v2", detail.proton.values.single().value)
        assertTrue(RoomContactConflictStore(db).choose(ACCOUNT, detail.summary, ContactConflictChoice.LOCAL))
        val queued = requireNotNull(RoomContactConflictStore(db).detail(ACCOUNT, local.id))
        assertFalse(RoomContactConflictStore(db).choose(ACCOUNT, queued.summary, ContactConflictChoice.PROTON))
        assertNull(RoomContactConflictStore(db).detail("another-account", local.id))
        db.close(); db = ContakoDatabase.create(context, NAME)
        assertEquals(ContactConflictChoice.LOCAL, RoomContactConflictStore(db).detail(ACCOUNT, local.id)?.summary?.choice)
        assertEquals(MutationPreparation.UploadAllowed, RoomContactConflictStore(db).prepare(ACCOUNT, local.id, local.revision, remote()))
        assertNotNull(RoomContactConflictStore(db).detail(ACCOUNT, local.id))
        assertEquals("local note", db.contactDao().get(ACCOUNT, local.id)?.toDomain()?.values?.single()?.value)
    }

    @Test fun newerRemoteInvalidatesChoiceWithoutAdoptingOrAdvancingBaseline() = runBlocking {
        val local = seed(); val store = RoomContactConflictStore(db)
        store.prepare(ACCOUNT, local.id, local.revision, remote())
        val detail = requireNotNull(store.detail(ACCOUNT, local.id))
        assertTrue(store.choose(ACCOUNT, detail.summary, ContactConflictChoice.PROTON))
        assertTrue(store.prepare(ACCOUNT, local.id, local.revision, remote("v3")) is MutationPreparation.ActionRequired)
        val newer = requireNotNull(store.detail(ACCOUNT, local.id))
        assertNull(newer.summary.choice)
        assertNotEquals(detail.summary.generation, newer.summary.generation)
        assertEquals("v1", db.outboxDao().getAll(ACCOUNT).single().remoteVersion)
        assertFalse(store.choose(ACCOUNT, detail.summary, ContactConflictChoice.LOCAL))
    }

    @Test fun newerLocalEditRejectsOldChoiceAndRefreshesSnapshot() = runBlocking {
        val local = seed(); val store = RoomContactConflictStore(db)
        store.prepare(ACCOUNT, local.id, local.revision, remote())
        val detail = requireNotNull(store.detail(ACCOUNT, local.id))
        val changed = (RoomContactRepository(db).saveContact(local.copy(displayName = "New local name")) as SaveResult.Saved).value
        val stale = requireNotNull(store.detail(ACCOUNT, local.id))
        assertFalse(stale.isCurrent)
        assertEquals(detail.local, stale.local)
        assertNull(stale.localPreview)
        assertNull(stale.protonPreview)
        assertFalse(stale.protonChoiceAvailable)
        assertFalse(store.choose(ACCOUNT, detail.summary, ContactConflictChoice.PROTON))
        assertTrue(store.prepare(ACCOUNT, local.id, changed.revision, remote()) is MutationPreparation.ActionRequired)
        assertEquals("New local name", store.detail(ACCOUNT, local.id)?.local?.displayName)
    }

    @Test fun protonChoiceAdoptsAtomicallyWithoutNewUploadAndAccountRemovalCascades() = runBlocking {
        val local = seed(); val store = RoomContactConflictStore(db)
        store.prepare(ACCOUNT, local.id, local.revision, remote())
        assertTrue(store.choose(ACCOUNT, requireNotNull(store.detail(ACCOUNT, local.id)).summary, ContactConflictChoice.PROTON))
        assertEquals(MutationPreparation.ResolvedWithoutUpload, store.prepare(ACCOUNT, local.id, local.revision, remote()))
        val adopted = requireNotNull(db.contactDao().get(ACCOUNT, local.id)).toDomain()
        assertEquals("Proton note v2", adopted.values.single().value)
        assertNull(adopted.pendingMutationRevision)
        assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
        assertNull(store.detail(ACCOUNT, local.id))
        val edited = (RoomContactRepository(db).saveContact(adopted.copy(displayName = "Changed")) as SaveResult.Saved).value
        store.prepare(ACCOUNT, local.id, edited.revision, remote("v3"))
        db.accountRemovalDao().deleteAccount(ACCOUNT)
        assertNull(store.detail(ACCOUNT, local.id))
    }

    @Test fun protonChoiceCannotDiscardPendingGroupAssignmentsToRemovedEmail() = runBlocking {
        val initial = seed()
        val repository = RoomContactRepository(db)
        val local = (repository.saveContact(initial.copy(values = initial.values +
            ContactValue("email", ContactValueKind.EMAIL, "fixture@example.test", order = 1))) as SaveResult.Saved).value
        assertTrue(repository.saveGroup(ContactGroup(ACCOUNT, "group", "Synthetic", memberships =
            listOf(GroupMembership(local.id, "email")))) is SaveResult.Saved)
        val store = RoomContactConflictStore(db)
        store.prepare(ACCOUNT, local.id, local.revision, remote())
        val detail = requireNotNull(store.detail(ACCOUNT, local.id))
        assertFalse(detail.protonChoiceAvailable)
        assertTrue(detail.isCurrent)
        assertNotNull(detail.protonPreview)
        assertEquals(listOf("group"), detail.groups.map { it.id })
        assertTrue(store.choose(ACCOUNT, detail.summary, ContactConflictChoice.PROTON))
        assertTrue(store.prepare(ACCOUNT, local.id, local.revision, remote()) is MutationPreparation.ActionRequired)
        assertNull(store.detail(ACCOUNT, local.id)?.summary?.choice)
        assertTrue(requireNotNull(db.contactGroupDao().get(ACCOUNT, "group")).memberships.any { it.emailValueId == "email" })
        assertTrue(db.outboxDao().getAll(ACCOUNT).any { it.aggregateType == "GROUP" })
    }

    @Test fun repeatedDetailReadsArePureAndProtonPreviewMatchesActualComplementAdoption() = runBlocking {
        val (local, verified) = richConflict()
        val store = RoomContactConflictStore(db)
        val canonicalBefore = db.contactDao().get(ACCOUNT, local.id)
        val payloadBefore = db.contactPayloadDao().get(local.toEntity().ownerKey)
        val outboxBefore = db.outboxDao().getAll(ACCOUNT)
        val foreignOutbox = db.outboxDao().getAll("synthetic-other-owner")
        val conflictBefore = db.contactConflictDao().get(ACCOUNT, local.id)
        val chunksBefore = db.contactConflictDao().chunks(local.toEntity().ownerKey, "LOCAL") +
            db.contactConflictDao().chunks(local.toEntity().ownerKey, "PROTON")
        val groupsBefore = db.contactGroupDao().getAll(ACCOUNT)
        repeat(3) {
            val detail = requireNotNull(store.detail(ACCOUNT, local.id))
            assertTrue(detail.isCurrent)
            assertTrue(detail.protonChoiceAvailable)
            assertEquals(local, detail.local)
            assertEquals(verified.contact, detail.proton)
            assertEquals(listOf("owned-group"), detail.groups.map { it.id })
            assertTrue(detail.groups.all { it.accountId == ACCOUNT && !it.isDeleted })
            val preview = requireNotNull(detail.protonPreview)
            assertEquals(ACCOUNT, preview.accountId)
            assertEquals(local.id, preview.id)
            assertEquals("Proton note v2", preview.valuesOf(ContactValueKind.NOTE).single().value)
            assertEquals(local.valuesOf(ContactValueKind.CUSTOM_DATE), preview.valuesOf(ContactValueKind.CUSTOM_DATE))
            assertEquals(local.valuesOf(ContactValueKind.PHONETIC_NAME), preview.valuesOf(ContactValueKind.PHONETIC_NAME))
            assertEquals("Profile", preview.valuesOf(ContactValueKind.URL).single().label)
            assertEquals("true", preview.valuesOf(ContactValueKind.URL).single().metadata["contakoLocalOnlyLabel"])
            assertEquals(verified.contact.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY),
                preview.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY))
            assertEquals(verified.contact.preservationEnvelope, preview.preservationEnvelope)
            assertNull(store.detail("synthetic-other-owner", local.id))
        }
        assertEquals(canonicalBefore, db.contactDao().get(ACCOUNT, local.id))
        assertEquals(payloadBefore, db.contactPayloadDao().get(local.toEntity().ownerKey))
        assertEquals(outboxBefore, db.outboxDao().getAll(ACCOUNT))
        assertEquals(foreignOutbox, db.outboxDao().getAll("synthetic-other-owner"))
        assertEquals(conflictBefore, db.contactConflictDao().get(ACCOUNT, local.id))
        assertEquals(groupsBefore, db.contactGroupDao().getAll(ACCOUNT))
        val chunksAfter = db.contactConflictDao().chunks(local.toEntity().ownerKey, "LOCAL") +
            db.contactConflictDao().chunks(local.toEntity().ownerKey, "PROTON")
        assertEquals(chunksBefore.size, chunksAfter.size)
        chunksBefore.zip(chunksAfter).forEach { (before, after) ->
            assertEquals(before.side to before.position, after.side to after.position)
            assertTrue("Read-only conflict snapshot changed", before.bytes.contentEquals(after.bytes))
        }
        val detail = requireNotNull(store.detail(ACCOUNT, local.id))
        val preview = requireNotNull(detail.protonPreview)
        assertTrue(store.choose(ACCOUNT, detail.summary, ContactConflictChoice.PROTON))
        val queued = requireNotNull(store.detail(ACCOUNT, local.id))
        assertTrue(queued.isCurrent)
        assertEquals(ContactConflictChoice.PROTON, queued.summary.choice)
        assertEquals(MutationPreparation.ResolvedWithoutUpload, store.prepare(ACCOUNT, local.id, local.revision, verified))
        val adopted = requireNotNull(db.contactDao().get(ACCOUNT, local.id)).toDomain()
        // Room groups different families by its storage order. Only occurrence order within
        // each family is semantic; compare every complete occurrence, not that container order.
        ContactValueKind.entries.forEach { kind -> assertEquals(preview.valuesOf(kind), adopted.valuesOf(kind)) }
        assertEquals(preview.displayName, adopted.displayName)
        assertEquals(preview.firstName, adopted.firstName)
        assertEquals(preview.lastName, adopted.lastName)
        assertEquals(preview.preservationEnvelope, db.contactPayloadDao().get(local.toEntity().ownerKey)?.toDomain())
    }

    @Test fun localPreviewMatchesActualPreparationAndKeepsPinnedSourceEnvelope() = runBlocking {
        val (local, verified) = richConflict()
        val store = RoomContactConflictStore(db)
        val detail = requireNotNull(store.detail(ACCOUNT, local.id))
        val preview = requireNotNull(detail.localPreview)
        assertEquals(local.values, preview.values)
        val raw = requireNotNull(preview.preservationEnvelope).rawProperties
        assertEquals("local source card", raw["pending-value-source/proton-card-1-0"])
        assertEquals("remote current card", raw["proton-card-1-0"])
        assertTrue(store.choose(ACCOUNT, detail.summary, ContactConflictChoice.LOCAL))
        db.close(); db = ContakoDatabase.create(context, NAME)
        val reopened = requireNotNull(RoomContactConflictStore(db).detail(ACCOUNT, local.id))
        assertTrue(reopened.isCurrent)
        assertEquals(preview, reopened.localPreview)
        assertEquals(MutationPreparation.UploadAllowed,
            RoomContactConflictStore(db).prepare(ACCOUNT, local.id, local.revision, verified))
        val prepared = requireNotNull(db.contactDao().get(ACCOUNT, local.id)).toDomain()
        assertEquals(preview.values, prepared.values)
        assertEquals(preview.displayName, prepared.displayName)
        assertEquals(preview.preservationEnvelope, db.contactPayloadDao().get(local.toEntity().ownerKey)?.toDomain())
    }

    @Test fun invalidComplementCollisionMakesProtonPreviewUnavailableWithoutMutation() = runBlocking {
        val (local, _) = richConflict()
        val store = RoomContactConflictStore(db)
        val impossible = remote("v3").let { card -> card.copy(contact = card.contact.copy(values = listOf(
            ContactValue("custom-date", ContactValueKind.NOTE, "Conflicting identity", order = 0)))) }
        store.prepare(ACCOUNT, local.id, local.revision, impossible)
        val intent = db.outboxDao().getAll(ACCOUNT)
        val source = db.contactDao().get(ACCOUNT, local.id)
        val detail = requireNotNull(store.detail(ACCOUNT, local.id))
        assertTrue(detail.isCurrent)
        assertNotNull(detail.localPreview)
        assertNull(detail.protonPreview)
        assertFalse(detail.protonChoiceAvailable)
        assertEquals(intent, db.outboxDao().getAll(ACCOUNT))
        assertEquals(source, db.contactDao().get(ACCOUNT, local.id))
    }

    private suspend fun richConflict(): Pair<CanonicalContact, VerifiedContactCard> {
        val initial = seed()
        val repository = RoomContactRepository(db)
        val local = (repository.saveContact(initial.copy(values = initial.values + listOf(
            ContactValue("custom-date", ContactValueKind.CUSTOM_DATE, "--10-23", "Milestone", 0,
                components = mapOf("month" to "10", "day" to "23")),
            ContactValue("phonetic", ContactValueKind.PHONETIC_NAME, "Ay-da", order = 0,
                components = mapOf("family" to "Demo")),
            ContactValue("url", ContactValueKind.URL, "https://example.test/profile", "Profile", 0,
                metadata = mapOf("androidSemanticType" to "PROFILE")),
            ContactValue("email", ContactValueKind.EMAIL, "fixture@example.test", order = 0),
            ContactValue("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-DEMO:local", order = 0),
        ), preservationEnvelope = PreservationEnvelope(mapOf("proton-card-1-0" to "local source card")))) as SaveResult.Saved).value
        assertTrue(repository.saveGroup(ContactGroup(ACCOUNT, "owned-group", "Owned group", remoteLabelId = "owned-label",
            memberships = listOf(GroupMembership(local.id, "email")))) is SaveResult.Saved)
        assertTrue(repository.saveGroup(ContactGroup("synthetic-other-owner", "foreign-group", "Foreign group")) is SaveResult.Saved)
        val deleted = ContactGroup(ACCOUNT, "deleted-group", "Deleted group", isDeleted = true)
        db.contactGroupDao().upsert(deleted.toEntity())
        val verified = remote().let { card -> card.copy(contact = card.contact.copy(values = card.contact.values + listOf(
            ContactValue("remote-url", ContactValueKind.URL, "https://example.test/profile", order = 0),
            ContactValue("remote-email", ContactValueKind.EMAIL, "fixture@example.test", order = 0),
            ContactValue("remote-opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-DEMO:remote", order = 0),
        ), preservationEnvelope = PreservationEnvelope(mapOf("proton-card-1-0" to "remote current card")))) }
        assertTrue(RoomContactConflictStore(db).prepare(ACCOUNT, local.id, local.revision, verified) is MutationPreparation.ActionRequired)
        return local to verified
    }
    private companion object { const val NAME = "contact-conflict-isolated.db"; const val ACCOUNT = "synthetic-conflict" }
}
