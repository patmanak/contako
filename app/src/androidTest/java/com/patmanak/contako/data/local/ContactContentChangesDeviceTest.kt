package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.data.proton.verifiedContentFingerprint
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.domain.entity.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactContentChangesDeviceTest {
    @Test fun acknowledgedRealCodecReadbackDoesNotRedateGeneratedNameOrganizationOrDate() = runBlocking {
        val created = save(fixture().copy(lastName = "Family", values = fixture().values + listOf(
            ContactValue("org", ContactValueKind.ORGANIZATION, "Synthetic organization", order = 0),
            ContactValue("birth", ContactValueKind.BIRTHDAY, "2000-02-29", order = 0),
        )))
        val creationTime = now
        val index = Contact(UserId("synthetic-user"), ContactId("remote-contact"),
            created.resolvedDisplayName, emptyList())
        val codec = ProtonContactVCardCodec()
        fun verified(contact: CanonicalContact): VerifiedContactCard {
            val wire = codec.encode(contact)
            val cards = listOf(ProtonPlainContactCard(ContactCardType.Signed, wire.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, wire.encryptedPrivate))
            return VerifiedContactCard(RemoteContactId(index.id.id), verifiedContentFingerprint(index, cards),
                codec.decode(OWNER, index, cards))
        }
        val readback = verified(created)
        val intent = database.outboxDao().getAll(OWNER).single()
        val outbox = RoomOutboxStore(database)
        assertTrue(outbox.claim(intent, now))
        assertTrue(outbox.acknowledge(intent, index.id.id, readback.version?.value))
        assertTrue(outbox.finishAcknowledged(intent))
        now += 8 * 24 * 60 * 60 * 1_000L
        applyVerified(readback)
        assertEquals(creationTime, change()?.observedAtEpochMillis)
        val canonical = requireNotNull(repository.getContact(OWNER, ID))
        assertTrue(canonical.values.any { it.kind == ContactValueKind.STRUCTURED_NAME })
        assertEquals("20000229", canonical.values.single { it.kind == ContactValueKind.BIRTHDAY }.value)
        val changed = verified(canonical.copy(values = canonical.values +
            ContactValue("new-note", ContactValueKind.NOTE, "A real remote field change", order = 0)))
        applyVerified(changed)
        assertEquals(now, change()?.observedAtEpochMillis)
    }

    @Test fun unknownLegacyBaselineEstablishesProofWithoutInventingAnObservation() = runBlocking {
        val original = fixture().copy(remoteContactId = "remote-contact", remoteVersion = "legacy-index", revision = 1)
        database.contactDao().upsert(original.toEntity())
        database.contactDao().upsertValues(original.values.map { it.toEntity(original) })
        receive(original.copy(values = original.values + ContactValue("note", ContactValueKind.NOTE, "Legacy read", order = 0)))
        assertNull(change())
        now += 1_000
        receive(requireNotNull(repository.getContact(OWNER, ID)).copy(firstName = "A subsequent received change"))
        assertEquals(now, change()?.observedAtEpochMillis)
    }

    @Test fun explicitProtonConflictChoiceCanChangeFieldsEvenWhenRemoteBaselineMatches() = runBlocking {
        receive(fixture().copy(remoteContactId = "remote-contact"))
        val remote = requireNotNull(repository.getContact(OWNER, ID))
        val edited = save(remote.copy(firstName = "Local pending name"))
        now += 1_000
        RoomRemoteCanonicalReconciliationStore(database, evidenceClock = { now }).adoptRemote(
            edited, remote, VerifiedContactCard(RemoteContactId("remote-contact"),
                RemoteVersion(requireNotNull(remote.remoteVersion)), remote), null)
        assertEquals(now, change()?.observedAtEpochMillis)
    }
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private var now = 10_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { now }, calibrationProvider = { null })
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun firstImportAndIdentityOnlyHydrationStayUndatedThenNoteAndPhotoChangesAreDated() = runBlocking {
        val remote = fixture().copy(remoteContactId = "remote-contact")
        receive(remote)
        assertTrue(repository.observeContentChanges(OWNER).first().isEmpty())
        now = 20_000
        val rebound = remote.copy(values = remote.values.map { it.copy(id = "rebound-email",
            preservationKey = "new-wire-line", metadata = mapOf("protonEmailId" to "new-binding",
                "protonGroupIds" to "new-group")) }, remoteVersion = "new-version",
            preservationEnvelope = PreservationEnvelope(mapOf("wire" to "different-wire")))
        receive(rebound)
        assertTrue(repository.observeContentChanges(OWNER).first().isEmpty())
        val withNote = rebound.copy(values = rebound.values +
            ContactValue("note", ContactValueKind.NOTE, "A received note", order = 0))
        now = 30_000
        receive(withNote)
        assertEquals(mapOf(ID to now), repository.observeContentChanges(OWNER).first())
        val withPhoto = withNote.copy(values = withNote.values +
            ContactValue("photo", ContactValueKind.PHOTO, "", order = 0, binaryReference = "photo://synthetic-a"))
        now = 40_000
        receive(withPhoto)
        assertEquals(now, change()?.observedAtEpochMillis)
        now = 50_000
        receive(withPhoto.copy(values = withPhoto.values.map { if (it.kind == ContactValueKind.PHOTO)
            it.copy(binaryReference = "photo://synthetic-b") else it }))
        assertEquals(now, change()?.observedAtEpochMillis)
        now = 60_000
        receive(requireNotNull(repository.getContact(OWNER, ID)))
        assertEquals(50_000L, change()?.observedAtEpochMillis)
    }

    @Test fun localCreationEditAndIdenticalSaveKeepOnlyRealFieldChanges() = runBlocking {
        val created = save(fixture())
        assertEquals(now, change()?.observedAtEpochMillis)
        now = 20_000
        val unchanged = save(created)
        assertEquals(10_000L, change()?.observedAtEpochMillis)
        val edited = save(unchanged.copy(firstName = "Edited"))
        assertEquals(now, change()?.observedAtEpochMillis)
        now = 30_000
        assertTrue(repository.saveGroup(ContactGroup(OWNER, "group", "Synthetic group",
            memberships = listOf(GroupMembership(ID, edited.values.single().id)))) is SaveResult.Saved)
        assertEquals(20_000L, change()?.observedAtEpochMillis)
        val snapshot = repository.observeDirectory(OWNER).first()
        assertEquals("Edited", snapshot.contacts.single().firstName)
        assertEquals(mapOf(ID to 20_000L), snapshot.contentChanges)
    }

    @Test fun remoteHydrationComparesAfterRetainingExistingLocalComplements() = runBlocking {
        val remote = fixture().copy(remoteContactId = "remote-contact")
        receive(remote)
        val imported = requireNotNull(repository.getContact(OWNER, ID))
        val localDate = ContactValue("local-date", ContactValueKind.CUSTOM_DATE, "--10-25", order = 0)
        // Existing local-only data can predate this metadata table; hydration MUST retain it
        // without inventing a new field change merely because Proton cannot return it.
        database.contactDao().upsertValues(listOf(localDate.toEntity(imported)))
        now = 20_000
        receive(remote)
        assertNull(change())
        assertEquals(localDate, requireNotNull(repository.getContact(OWNER, ID))
            .valuesOf(ContactValueKind.CUSTOM_DATE).single())
    }

    @Test fun delayedUploadRoundTripDoesNotRedateOldCreationButRealLabelChangesDo() = runBlocking {
        val initial = fixture().let { contact -> contact.copy(values = contact.values.map {
            it.copy(label = " WORK ", metadata = mapOf("androidSemanticType" to "WORK"))
        }) }
        val created = save(initial)
        val creationTime = now
        val acknowledged = created.copy(remoteContactId = "remote-contact", remoteVersion = "acknowledged",
            pendingMutationRevision = null)
        // Simulate the upload acknowledgement independently from the verified read below.
        // The content/date were already committed more than seven days earlier.
        database.withTransaction {
            database.contactDao().upsert(acknowledged.toEntity())
            database.outboxDao().deleteRevision(OWNER, AggregateType.CONTACT.name, ID, created.revision)
        }
        now += 8 * 24 * 60 * 60 * 1_000L
        val decoded = acknowledged.copy(values = acknowledged.values.map {
            it.copy(id = "wire-email-id", label = "work", isPrimary = true,
                preservationKey = "wire-line", metadata = mapOf("vcardTypeTokens" to "work", "vcardPref" to "1"))
        })
        receive(decoded)
        assertEquals(creationTime, change()?.observedAtEpochMillis)
        assertTrue(requireNotNull(change()).observedAtEpochMillis < now - 7 * 24 * 60 * 60 * 1_000L)
        val home = decoded.copy(values = decoded.values.map {
            it.copy(label = "HOME", metadata = mapOf("vcardTypeTokens" to "home", "vcardPref" to "1"))
        })
        receive(home)
        assertEquals(now, change()?.observedAtEpochMillis)
        now += 1_000L
        receive(decoded)
        assertEquals(now, change()?.observedAtEpochMillis)
    }

    @Test fun identicalSaveOfImportedContactDoesNotInventChangeAndAndroidShellCreationIsDated() = runBlocking {
        receive(fixture().copy(remoteContactId = "remote-contact"))
        save(requireNotNull(repository.getContact(OWNER, ID)))
        assertNull(change())
        val shell = fixture().copy(id = "android-shell", revision = 0, isDeleted = true)
        database.contactDao().upsert(shell.toEntity())
        database.withTransaction {
            assertTrue(repository.applyContactDeltaInCurrentTransaction(0, shell.copy(isDeleted = false))
                is RoomCanonicalContactMutationResult.Applied)
        }
        assertEquals(now, database.contactContentChangesDao().get(OWNER, shell.id)?.observedAtEpochMillis)
    }

    @Test fun ownerIsolationDeletionVisibilityAndForeignKeyCleanup() = runBlocking {
        save(fixture())
        now = 20_000
        save(fixture().copy(accountId = OTHER_OWNER, displayName = "Other owner"))
        assertEquals(mapOf(ID to 10_000L), repository.observeContentChanges(OWNER).first())
        assertEquals(mapOf(ID to 20_000L), repository.observeContentChanges(OTHER_OWNER).first())
        assertEquals("Other owner", repository.observeDirectory(OTHER_OWNER).first().contacts.single().displayName)
        val orphan = runCatching { database.contactContentChangesDao().upsert(
            ContactContentChangeEntity(OWNER, "missing-contact", now)) }
        assertTrue(orphan.isFailure)
        repository.deleteContact(OWNER, ID)
        assertTrue(repository.observeContentChanges(OWNER).first().isEmpty())
        assertTrue(repository.observeDirectory(OWNER).first().contacts.isEmpty())
        database.accountRemovalDao().deleteAccount(OWNER)
        assertNull(change())
        assertEquals(20_000L, database.contactContentChangesDao().get(OTHER_OWNER, ID)?.observedAtEpochMillis)
        database.accountRemovalDao().deleteContacts(OTHER_OWNER)
        assertNull(database.contactContentChangesDao().get(OTHER_OWNER, ID))
    }

    @Test fun outboxFailureRollsBackContentAndObservationTimeTogether() = runBlocking {
        val original = save(fixture())
        val originalOutbox = database.outboxDao().getAll(OWNER)
        now = 20_000
        val failing = RoomContactRepository(database, clock = { now }, calibrationProvider = { null },
            checkpointHook = LocalMutationCheckpointHook { checkpoint ->
                if (checkpoint == LocalMutationCheckpoint.CONTACT_SAVE_AFTER_OUTBOX) throw SyntheticFailure()
            })
        assertTrue(runCatching { failing.saveContact(original.copy(displayName = "Must roll back")) }.isFailure)
        assertEquals(original, repository.getContact(OWNER, ID))
        assertEquals(originalOutbox, database.outboxDao().getAll(OWNER))
        assertEquals(10_000L, change()?.observedAtEpochMillis)
    }

    @Test fun contentComparisonRetainsMultiplicityAndSemanticMetadataWithoutTransportNoise() {
        val contact = fixture()
        val value = contact.values.single()
        assertTrue(sameContactContent(contact, contact.copy(id = "other-local-id", revision = 8,
            values = listOf(value.copy(id = "other-value-id", preservationKey = "wire-key",
                metadata = mapOf("protonEmailId" to "transport", "protonGroupIds" to "group",
                    "contakoLocalOnlyLabel" to "true"))))))
        assertFalse(sameContactContent(contact, contact.copy(values = listOf(value, value.copy(id = "duplicate")))))
        listOf(value.copy(label = "work"), value.copy(order = 1),
            value.copy(metadata = mapOf("vcardTypeTokens" to "work"))).forEach { edited ->
                assertFalse(sameContactContent(contact, contact.copy(values = listOf(edited))))
            }
        assertTrue(sameContactContent(contact, contact.copy(values = listOf(value.copy(isPrimary = true,
            metadata = mapOf("vcardPref" to "2", "androidSemanticType" to "WORK"))))))
        val secondary = value.copy(id = "secondary", value = "secondary@example.test", order = 1)
        val pair = contact.copy(values = listOf(value, secondary))
        assertFalse(sameContactContent(pair, pair.copy(values = listOf(value,
            secondary.copy(metadata = mapOf("vcardPref" to "1"))))))
        assertFalse(sameContactContent(contact.copy(values = listOf(value.copy(label = "home"))),
            contact.copy(values = listOf(value.copy(label = "HOME", metadata =
                mapOf("vcardTypeTokens" to "HOME\u001fvoice"))))))
        assertFalse(sameContactContent(contact.copy(values = listOf(value.copy(label = "Personal label"))),
            contact.copy(values = listOf(value.copy(label = "personal label")))))
        val postal = ContactValue("address", ContactValueKind.POSTAL_ADDRESS, "Synthetic street", order = 0)
        assertTrue(sameContactContent(contact.copy(values = listOf(postal)), contact.copy(values = listOf(
            postal.copy(value = "", components = mapOf("street" to "Synthetic street"))))))
    }

    private suspend fun receive(contact: CanonicalContact) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(contact.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val verified = VerifiedContactCard(RemoteContactId("remote-contact"), RemoteVersion("content-v3:$digest"), contact)
        applyVerified(verified)
    }

    private suspend fun applyVerified(verified: VerifiedContactCard) = database.withTransaction {
        RoomRemoteCanonicalReconciliationStore(database, evidenceClock = { now })
            .reconcileTargetedVerified(AccountScope(OWNER), verified)
    }

    private suspend fun save(contact: CanonicalContact): CanonicalContact =
        (repository.saveContact(contact) as SaveResult.Saved).value

    private suspend fun change(): ContactContentChangeEntity? = database.contactContentChangesDao().get(OWNER, ID)

    private fun fixture() = CanonicalContact(OWNER, ID, firstName = "Synthetic", displayName = "Synthetic contact",
        values = listOf(ContactValue("email", ContactValueKind.EMAIL, "synthetic@example.test", order = 0)))

    private class SyntheticFailure : RuntimeException()

    private companion object {
        const val DATABASE_NAME = "contact-content-changes.db"
        const val OWNER = "synthetic-owner"
        const val OTHER_OWNER = "synthetic-other-owner"
        const val ID = "synthetic-contact"
    }
}
