package com.patmanak.contako.data.local

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.repository.ContactDirectorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real SQLite/Room only. Never constructs accounts, providers or remote gateways. */
@RunWith(AndroidJUnit4::class)
class DirectorySnapshotReadDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "directory-snapshot-${SystemClock.elapsedRealtimeNanos()}.db"
    private var database: ContakoDatabase? = null

    @After fun close() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test fun flatReadMatchesRelationOracleAcrossBatchBoundaryWithAllFieldsAndOwners() = runBlocking {
        val db = open()
        val contacts = (0 until 1_005).map { index ->
            fixture("row-$index").let { if (index % 11 == 0) it.copy(values = emptyList()) else it }
        }
        val foreign = fixture(contacts[1].id).copy(accountId = FOREIGN, displayName = "Foreign canary")
        val deleted = fixture("deleted").copy(isDeleted = true)
        db.withTransaction {
            (contacts + foreign + deleted).forEach { insert(db, it) }
            db.contactContentChangesDao().upsert(ContactContentChangeEntity(ACCOUNT, contacts[1].id, 123_456L))
        }
        val dao = db.contactContentChangesDao()
        assertEquals(dao.observeDirectory(ACCOUNT).first(), dao.readDirectory(ACCOUNT))
        assertEquals(dao.observeDirectory(FOREIGN).first(), dao.readDirectory(FOREIGN))
        assertTrue(dao.readDirectory("empty-owner").isEmpty())
        val repository = RoomContactRepository(db, calibrationProvider = { null })
        assertEquals(repository.observeContacts(ACCOUNT).first(), repository.observeDirectory(ACCOUNT).first().contacts)
        assertEquals(setOf(contacts[1].id), repository.observeDirectory(ACCOUNT).first().contentChanges.keys)
        assertEquals(contacts[1], repository.getContact(ACCOUNT, contacts[1].id))
        assertEquals(foreign, repository.getContact(FOREIGN, foreign.id))
        assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
    }

    @Test fun valueOnlyAndTimestampWritesInvalidateAtomicallyWithoutAContactRevisionChange() = runBlocking {
        val db = open()
        val initial = fixture("changed")
        val foreign = initial.copy(accountId = FOREIGN)
        db.withTransaction { insert(db, initial); insert(db, foreign) }
        val repository = RoomContactRepository(db, calibrationProvider = { null })
        val emissions = Channel<ContactDirectorySnapshot>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { repository.observeDirectory(ACCOUNT).collect(emissions::send) }
        try {
            val before = withTimeout(TIMEOUT) { emissions.receive() }
            assertEquals(initial.values, before.contacts.single().values)
            val changed = initial.copy(values = initial.values.map {
                if (it.id == "second-email") it.copy(value = "changed@example.test", metadata = it.metadata + ("marker" to "new")) else it
            })
            db.withTransaction {
                db.contactDao().upsertValues(changed.values.map { it.toEntity(changed) })
                db.contactContentChangesDao().upsert(ContactContentChangeEntity(ACCOUNT, initial.id, 456_789L))
            }
            val after = withTimeout(TIMEOUT) {
                var next = emissions.receive()
                while (next.contentChanges[initial.id] != 456_789L) {
                    // Invalidation timing may replay the old complete snapshot, never half of
                    // the value/date transaction. Do not silently skip a mixed emission.
                    assertEquals(before, next)
                    next = emissions.receive()
                }
                next
            }
            assertEquals(changed.values, after.contacts.single().values)
            assertEquals(initial.revision, after.contacts.single().revision)
            assertEquals(changed, repository.getContact(ACCOUNT, initial.id))
            assertEquals(foreign, repository.getContact(FOREIGN, foreign.id))
            assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
        } finally {
            collector.cancelAndJoin()
            emissions.cancel()
        }
    }

    @Test fun groupOnlyWriteAndReopenKeepCanonicalMetadataAndNoStaleSnapshot() = runBlocking {
        var db = open()
        val contact = fixture("reopened")
        val group = ContactGroup(ACCOUNT, "group", "Before", memberships =
            listOf(GroupMembership(contact.id, "second-email")))
        db.withTransaction {
            insert(db, contact)
            db.contactGroupDao().upsert(group.toEntity())
            db.contactGroupDao().insertMemberships(group.memberships.map { it.toEntity(group) })
        }
        var repository = RoomContactRepository(db, calibrationProvider = { null })
        val before = repository.observeDirectory(ACCOUNT).first()
        db.contactGroupDao().upsert(group.copy(name = "After").toEntity())
        assertEquals("After", repository.observeGroups(ACCOUNT).first().single().name)
        assertEquals(before, repository.observeDirectory(ACCOUNT).first())
        assertEquals(contact, repository.getContact(ACCOUNT, contact.id))
        db.close()
        db = ContakoDatabase.create(context, name).also { database = it }
        repository = RoomContactRepository(db, calibrationProvider = { null })
        assertEquals(before, repository.observeDirectory(ACCOUNT).first())
        val changed = contact.copy(displayName = "Fresh after reopen", revision = contact.revision + 1)
        db.contactDao().upsert(changed.toEntity())
        assertEquals(changed.displayName, repository.observeDirectory(ACCOUNT).first().contacts.single().displayName)
        assertEquals(changed, repository.getContact(ACCOUNT, contact.id))
        assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
    }

    @Test fun malformedValueIdentitiesAreExcludedWithoutChangingOrRemovingStoredRows() = runBlocking {
        val db = open()
        val first = fixture("owned-first")
        val second = fixture("owned-second")
        val foreign = first.copy(accountId = FOREIGN)
        val deleted = fixture("deleted-owner").copy(isDeleted = true)
        val contacts = listOf(first, second, foreign, deleted)
        db.withTransaction { contacts.forEach { insert(db, it) } }
        val dao = db.contactContentChangesDao()
        val beforeOwned = dao.readDirectory(ACCOUNT)
        val beforeForeign = dao.readDirectory(FOREIGN)
        val headers = contacts.associate { (it.accountId to it.id) to requireNotNull(db.contactDao().getEntity(it.accountId, it.id)) }
        val payloads = contacts.associate { it.toEntity().ownerKey to db.contactPayloadDao().get(it.toEntity().ownerKey) }
        val legitimateValues = contacts.flatMap { contact -> contact.values.map { it.toEntity(contact) } }
        val template = first.values.first().toEntity(first)
        // The database FK constrains owner_key alone. These inconsistent rows are legal SQLite
        // fixtures; account/contact/owner agreement must still be proved by the directory reader.
        val forged = listOf(
            template.copy(id = "forged-account", accountId = FOREIGN),
            template.copy(id = "forged-contact", contactId = second.id),
            template.copy(id = "forged-owner", ownerKey = second.toEntity().ownerKey),
            template.copy(id = "forged-foreign-owner", ownerKey = foreign.toEntity().ownerKey),
            template.copy(id = "deleted-owner-value", contactId = deleted.id, ownerKey = deleted.toEntity().ownerKey),
        )
        db.contactDao().upsertValues(forged)
        val rawOwnedBatch = dao.readDirectoryValues(ACCOUNT, listOf(first.toEntity().ownerKey, second.toEntity().ownerKey))
        assertTrue("Fixture must reach the in-memory identity guards", rawOwnedBatch.map { it.id }
            .containsAll(listOf("forged-contact", "forged-owner")))
        assertEquals(beforeOwned, dao.readDirectory(ACCOUNT))
        assertEquals(beforeForeign, dao.readDirectory(FOREIGN))
        val repository = RoomContactRepository(db, calibrationProvider = { null })
        assertEquals(beforeOwned.map { ContactWithValues(it.contact, it.values).toDomain() }.toSet(),
            repository.observeDirectory(ACCOUNT).first().contacts.toSet())
        assertEquals(beforeForeign.map { ContactWithValues(it.contact, it.values).toDomain() },
            repository.observeDirectory(FOREIGN).first().contacts)
        // Reading is not cleanup: malformed and legitimate rows, metadata and source envelopes
        // are retained byte-for-byte, including data owned by the foreign account.
        (legitimateValues + forged).forEach { value ->
            assertEquals(value, db.contactDao().getValue(value.accountId, value.contactId, value.id))
        }
        headers.forEach { (identity, expected) -> assertEquals(expected, db.contactDao().getEntity(identity.first, identity.second)) }
        payloads.forEach { (key, expected) -> assertEquals(expected, db.contactPayloadDao().get(key)) }
        assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
        assertTrue(db.outboxDao().getAll(FOREIGN).isEmpty())
    }

    private fun open(): ContakoDatabase = ContakoDatabase.create(context, name).also { database = it }

    private suspend fun insert(db: ContakoDatabase, contact: CanonicalContact) {
        db.contactDao().upsert(contact.toEntity())
        db.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
        contact.preservationEnvelope?.let { db.contactPayloadDao().upsert(it.toEntity(contact.accountId, contact.id)) }
    }

    private fun fixture(id: String) = CanonicalContact(ACCOUNT, id, displayName = "Zoë Groß $id", revision = 4,
        values = listOf(
            ContactValue("first-email", ContactValueKind.EMAIL, "same@example.test", order = 0,
                metadata = mapOf("protonContactEmailId" to "synthetic-one", "vcardPref" to "1")),
            ContactValue("second-email", ContactValueKind.EMAIL, "same@example.test", order = 1,
                metadata = mapOf("protonContactEmailId" to "synthetic-two", "vcardTypeTokens" to "WORK")),
            ContactValue("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "Opaque retained canary", order = 1,
                components = mapOf("first" to "ß;ë"), metadata = mapOf("property" to "X-SYNTHETIC"),
                preservationKey = "opaque-preserved"),
            ContactValue("photo", ContactValueKind.PHOTO, "Synthetic media canary", order = 3,
                binaryReference = "synthetic-photo-reference", preservationKey = "photo-preserved"),
        ), preservationEnvelope = PreservationEnvelope(mapOf("X-SYNTHETIC" to "Retained source"), "Synthetic baseline"))

    companion object {
        private const val ACCOUNT = "directory-snapshot-owner"
        private const val FOREIGN = "directory-snapshot-foreign"
        private const val TIMEOUT = 15_000L
    }
}
