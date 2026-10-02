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
        assertTrue(store.choose(ACCOUNT, detail.summary, ContactConflictChoice.PROTON))
        assertTrue(store.prepare(ACCOUNT, local.id, local.revision, remote()) is MutationPreparation.ActionRequired)
        assertNull(store.detail(ACCOUNT, local.id)?.summary?.choice)
        assertTrue(requireNotNull(db.contactGroupDao().get(ACCOUNT, "group")).memberships.any { it.emailValueId == "email" })
        assertTrue(db.outboxDao().getAll(ACCOUNT).any { it.aggregateType == "GROUP" })
    }
    private companion object { const val NAME = "contact-conflict-isolated.db"; const val ACCOUNT = "synthetic-conflict" }
}
