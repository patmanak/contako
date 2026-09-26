package com.patmanak.contako.data.local

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.policy.ContactSearch
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
class RoomRepositoryContractTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private var now = 10_000L

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
    fun contactUpdateAndOutboxWriteRollbackTogetherWhenOutboxInsertFails() = runBlocking {
        val initial = repository.saveContact(
            contact(id = "atomic-contact", name = "Before").copy(
                remoteContactId = "synthetic-remote-id",
                remoteVersion = "synthetic-remote-version",
            ),
        ).requireSavedForTest()
        installFailingOutboxTrigger()

        val failed = runCatching {
            now++
            repository.saveContact(initial.copy(firstName = "After"))
        }

        assertTrue("The synthetic outbox failure must escape the repository", failed.isFailure)
        val restored = requireNotNull(repository.getContact(ACCOUNT_A, "atomic-contact"))
        assertEquals("Before", restored.firstName)
        assertEquals(1L, restored.revision)
        val outbox = database.outboxDao().getAll(ACCOUNT_A).single()
        assertEquals(1L, outbox.revision)
        assertEquals(MutationOperation.UPSERT.name, outbox.operation)
        assertEquals("synthetic-remote-id", outbox.remoteIdentity)
        assertEquals("synthetic-remote-version", outbox.remoteVersion)
        assertEquals("$ACCOUNT_A:CONTACT:atomic-contact:1:UPSERT", outbox.idempotencyKey)
    }

    @Test
    fun groupAndMembershipWritesRollbackWhenOutboxInsertFails() = runBlocking {
        repository.saveContact(
            CanonicalContact(
                accountId = ACCOUNT_A,
                id = "atomic-member",
                firstName = "Synthetic member",
                values = listOf(
                    ContactValue(
                        id = "atomic-email",
                        kind = ContactValueKind.EMAIL,
                        value = "atomic@example.test",
                        order = 0,
                    ),
                ),
            ),
        )
        installFailingOutboxTrigger()
        val failed = runCatching {
            repository.saveGroup(
                ContactGroup(
                    accountId = ACCOUNT_A,
                    id = "atomic-group",
                    name = "Synthetic group",
                    memberships = listOf(GroupMembership("atomic-member", "atomic-email")),
                ),
            )
        }

        assertTrue("The synthetic outbox failure must escape the repository", failed.isFailure)
        assertTrue(repository.observeGroups(ACCOUNT_A).first().isEmpty())
        val outbox = database.outboxDao().getAll(ACCOUNT_A)
        assertEquals(1, outbox.size)
        assertEquals("atomic-member", outbox.single().aggregateId)
        val membershipCount = database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM group_memberships")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        assertEquals(0, membershipCount)
    }

    @Test
    fun canonicalFamiliesRemainDeterministicAcrossReorderSaveAndRepeatedReopen() = runBlocking {
        val originalValues = ContactValueKind.entries.mapIndexed { index, kind ->
            ContactValue(
                id = "canonical-$index",
                kind = kind,
                value = "synthetic-${kind.name}",
                label = "Label $index",
                order = index,
                isPrimary = index == 0,
                components = linkedMapOf("year" to "", "month" to "03", "day" to "14"),
                metadata = linkedMapOf("z" to "last", "a" to "first"),
                binaryReference = if (kind in setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO)) {
                    "private://synthetic/$index"
                } else {
                    null
                },
                preservationKey = if (kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY) "unknown-$index" else null,
            )
        }
        val original = CanonicalContact(
            accountId = ACCOUNT_A,
            id = "canonical-contact",
            firstName = "Élodie",
            lastName = "Example",
            displayName = "Élodie Example",
            values = originalValues,
            remoteContactId = "synthetic-remote-contact",
            remoteVCardUid = "synthetic-vcard-uid",
            remoteVersion = "synthetic-remote-version",
            conflictState = "SYNTHETIC_RESOLVED",
            preservationEnvelope = PreservationEnvelope(
                rawProperties = linkedMapOf("unknown-23" to "X-SYNTHETIC:value", "a" to "first"),
                remoteBaseline = "BEGIN:VCARD\nVERSION:4.0\nFN:Synthetic\nEND:VCARD",
            ),
        )

        repository.saveContact(original)
        reopenDatabase()
        val firstRead = requireNotNull(repository.getContact(ACCOUNT_A, original.id))
        now++
        repository.saveContact(firstRead.copy(values = firstRead.values.reversed()))
        reopenDatabase()
        val secondRead = requireNotNull(repository.getContact(ACCOUNT_A, original.id))
        reopenDatabase()
        val thirdRead = requireNotNull(repository.getContact(ACCOUNT_A, original.id))

        assertEquals(originalValues, secondRead.values)
        assertEquals(secondRead, thirdRead)
        assertEquals(original.preservationEnvelope, secondRead.preservationEnvelope)
        assertEquals(original.remoteContactId, secondRead.remoteContactId)
        assertEquals(original.remoteVCardUid, secondRead.remoteVCardUid)
        assertEquals(original.remoteVersion, secondRead.remoteVersion)
        assertEquals(original.conflictState, secondRead.conflictState)
        assertEquals(secondRead.revision, secondRead.pendingMutationRevision)
        assertEquals(ContactValueKind.entries.toSet(), secondRead.values.map { it.kind }.toSet())
    }

    @Test
    fun contactGroupAndEmailMembershipOwnershipRemainAccountScoped() = runBlocking {
        listOf(ACCOUNT_A, ACCOUNT_B).forEach { account ->
            repository.saveContact(
                CanonicalContact(
                    accountId = account,
                    id = "shared-contact-id",
                    firstName = if (account == ACCOUNT_A) "Alpha" else "Beta",
                    values = listOf(
                        ContactValue(
                            id = "shared-email-id",
                            kind = ContactValueKind.EMAIL,
                            value = if (account == ACCOUNT_A) "alpha@example.test" else "beta@example.test",
                            order = 0,
                        ),
                    ),
                ),
            )
            repository.saveGroup(
                ContactGroup(
                    accountId = account,
                    id = "shared-group-id",
                    name = if (account == ACCOUNT_A) "Alpha group" else "Beta group",
                    order = if (account == ACCOUNT_A) 7 else 9,
                    isVisible = account == ACCOUNT_A,
                    remoteVersion = "synthetic-version-$account",
                    conflictState = if (account == ACCOUNT_A) "SYNTHETIC_RESOLVED" else null,
                    memberships = listOf(GroupMembership("shared-contact-id", "shared-email-id")),
                ),
            )
        }

        reopenDatabase()

        assertEquals("Alpha", repository.observeContacts(ACCOUNT_A).first().single().firstName)
        assertEquals("Beta", repository.observeContacts(ACCOUNT_B).first().single().firstName)
        assertEquals("Alpha group", repository.observeGroups(ACCOUNT_A).first().single().name)
        assertEquals("Beta group", repository.observeGroups(ACCOUNT_B).first().single().name)
        assertEquals(7, repository.observeGroups(ACCOUNT_A).first().single().order)
        assertTrue(repository.observeGroups(ACCOUNT_A).first().single().isVisible)
        assertFalse(repository.observeGroups(ACCOUNT_B).first().single().isVisible)
        assertEquals("SYNTHETIC_RESOLVED", repository.observeGroups(ACCOUNT_A).first().single().conflictState)
        assertEquals(
            repository.observeGroups(ACCOUNT_A).first().single().revision,
            repository.observeGroups(ACCOUNT_A).first().single().pendingMutationRevision,
        )
        assertEquals(
            listOf(GroupMembership("shared-contact-id", "shared-email-id")),
            repository.observeGroups(ACCOUNT_A).first().single().memberships,
        )

        repository.deleteGroup(ACCOUNT_A, "shared-group-id")

        assertTrue(repository.observeGroups(ACCOUNT_A).first().isEmpty())
        assertEquals("Beta group", repository.observeGroups(ACCOUNT_B).first().single().name)
        assertNotNull(repository.getContact(ACCOUNT_A, "shared-contact-id"))
        assertNotNull(repository.getContact(ACCOUNT_B, "shared-contact-id"))
    }

    @Test
    fun effectivePendingIntentIsCoalescedAcrossLifecycleReopens() = runBlocking {
        var saved = repository.saveContact(contact(id = "lifecycle-contact", name = "Version 1"))
            .requireSavedForTest()
        repeat(2) { index ->
            now++
            saved = repository.saveContact(saved.copy(firstName = "Version ${index + 2}"))
                .requireSavedForTest()
        }
        var group = repository.saveGroup(
            ContactGroup(accountId = ACCOUNT_A, id = "lifecycle-group", name = "Group 1"),
        ).requireSavedForTest()
        now++
        group = repository.saveGroup(group.copy(name = "Group 2")).requireSavedForTest()

        repeat(2) {
            reopenDatabase()
            assertEquals(2, repository.observePendingMutationCount(ACCOUNT_A).first())
            assertEquals("Version 3", repository.observeContacts(ACCOUNT_A).first().single().firstName)
            assertEquals(3L, repository.observeContacts(ACCOUNT_A).first().single().revision)
            assertEquals("Group 2", repository.observeGroups(ACCOUNT_A).first().single().name)
            assertEquals(2L, repository.observeGroups(ACCOUNT_A).first().single().revision)
            assertEquals(2, database.outboxDao().getAll(ACCOUNT_A).size)
        }
    }

    @Test
    fun currentSchemaReopensEmptyAndPopulatedFxP300WithoutLoss() = runBlocking {
        assertEquals(15, database.openHelper.readableDatabase.version)
        reopenDatabase()
        assertTrue(repository.observeContacts(ACCOUNT_A).first().isEmpty())

        repeat(FX_P_300_SIZE) { index ->
            repository.saveContact(contact(id = "fx-p300-$index", name = "Synthetic $index"))
        }
        reopenDatabase()

        val contacts = repository.observeContacts(ACCOUNT_A).first()
        assertEquals(FX_P_300_SIZE, contacts.size)
        assertEquals(FX_P_300_SIZE, contacts.map { it.id }.toSet().size)
        assertEquals(FX_P_300_SIZE, database.outboxDao().getAll(ACCOUNT_A).size)
        assertEquals("ok", database.openHelper.readableDatabase.query("PRAGMA quick_check").use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        })

        reopenDatabase()
        assertEquals(FX_P_300_SIZE, repository.observeContacts(ACCOUNT_A).first().size)
    }

    @Test
    fun fxP300BenchmarkProducesDeterministicDiagnosticSamplesOnly() = runBlocking {
        repeat(FX_P_300_SIZE) { index ->
            repository.saveContact(
                CanonicalContact(
                    accountId = ACCOUNT_A,
                    id = "benchmark-$index",
                    displayName = "Synthetic Contact ${index.toString().padStart(3, '0')}",
                    values = listOf(
                        ContactValue(
                            id = "organization-$index",
                            kind = ContactValueKind.ORGANIZATION,
                            value = "Synthetic Organization ${index % 20}",
                            order = 0,
                        ),
                    ),
                ),
            )
        }

        repeat(WARM_UP_COUNT) {
            repository.observeContacts(ACCOUNT_A).first()
            repository.observeContacts(ACCOUNT_A).first().filter { ContactSearch.matches(it, "Contact 12") }
            saveBenchmarkMutation(it)
        }

        val loadSamples = measureSamples {
            assertEquals(FX_P_300_SIZE, repository.observeContacts(ACCOUNT_A).first().size)
        }
        val searchSamples = measureSamples {
            val matches = repository.observeContacts(ACCOUNT_A).first()
                .filter { ContactSearch.matches(it, "organization 12") }
            assertEquals(15, matches.size)
        }
        var mutationIndex = WARM_UP_COUNT
        val saveSamples = measureSamples {
            saveBenchmarkMutation(mutationIndex++)
        }

        assertEquals(SAMPLE_COUNT, loadSamples.size)
        assertEquals(SAMPLE_COUNT, searchSamples.size)
        assertEquals(SAMPLE_COUNT, saveSamples.size)
        assertTrue(loadSamples.all { it >= 0L })
        assertTrue(searchSamples.all { it >= 0L })
        assertTrue(saveSamples.all { it >= 0L })
        val loadP95 = percentile95(loadSamples)
        val searchP95 = percentile95(searchSamples)
        val saveP95 = percentile95(saveSamples)
        assertTrue("Cached load P95 exceeds the 500 ms gate", loadP95 <= 500_000L)
        assertTrue("Local search P95 exceeds the 100 ms gate", searchP95 <= 100_000L)
        assertTrue("Local save P95 exceeds the 300 ms gate", saveP95 <= 300_000L)
        println(
            "01-LOCAL-PERF L2_GATE count=$SAMPLE_COUNT " +
                "loadP50Us=${percentile50(loadSamples)} loadP95Us=$loadP95 loadMaxUs=${loadSamples.max()} " +
                "searchP50Us=${percentile50(searchSamples)} searchP95Us=$searchP95 " +
                "searchMaxUs=${searchSamples.max()} saveP50Us=${percentile50(saveSamples)} " +
                "saveP95Us=$saveP95 saveMaxUs=${saveSamples.max()}",
        )
    }

    private suspend fun saveBenchmarkMutation(index: Int) {
        val stored = requireNotNull(repository.getContact(ACCOUNT_A, "benchmark-0"))
        now++
        repository.saveContact(stored.copy(displayName = "Synthetic Updated $index"))
    }

    private suspend fun measureSamples(block: suspend () -> Unit): List<Long> =
        List(SAMPLE_COUNT) {
            val started = SystemClock.elapsedRealtimeNanos()
            block()
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        }

    private fun percentile50(samples: List<Long>): Long = samples.sorted()[(samples.size + 1) / 2 - 1]

    private fun percentile95(samples: List<Long>): Long =
        samples.sorted()[(samples.size * 95 + 99) / 100 - 1]

    private fun installFailingOutboxTrigger() {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_synthetic_outbox_insert
            BEFORE INSERT ON outbox_mutations
            BEGIN
                SELECT RAISE(ABORT, 'synthetic outbox failure');
            END
            """.trimIndent(),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_synthetic_outbox_update
            BEFORE UPDATE ON outbox_mutations
            BEGIN
                SELECT RAISE(ABORT, 'synthetic outbox failure');
            END
            """.trimIndent(),
        )
    }

    private fun contact(id: String, name: String) = CanonicalContact(
        accountId = ACCOUNT_A,
        id = id,
        firstName = name,
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
            idFactory = { "generated-synthetic-id" },
        )
    }

    private companion object {
        const val ACCOUNT_A = "synthetic-account-a"
        const val ACCOUNT_B = "synthetic-account-b"
        const val DATABASE_NAME = "contako-v01-contract-test.db"
        const val FX_P_300_SIZE = 300
        const val WARM_UP_COUNT = 5
        const val SAMPLE_COUNT = 30
    }
}
