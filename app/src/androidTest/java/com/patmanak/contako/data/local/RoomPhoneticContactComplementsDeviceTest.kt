package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidLinkedValueRole
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.data.sync.IncrementalRemoteContactStage
import com.patmanak.contako.data.sync.MutationPreparation
import com.patmanak.contako.data.sync.RemoteContactStageResult
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.ContactConflictChoice
import kotlinx.coroutines.runBlocking
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room integration only: provider snapshots and verified Proton cards are controlled fixtures. */
@RunWith(AndroidJUnit4::class)
class RoomPhoneticContactComplementsDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private val mapper = CanonicalAndroidContactMapper()
    private var remoteVersion = 1
    private var hydrationCount = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { 20_000L })
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun nativePhoneticsSurviveOrdinaryRemoteAdoption() = runBlocking {
        verifyAdoption(familyOnly = false, chooseProton = false)
    }

    @Test fun familyOnlyPhoneticsSurviveOrdinaryRemoteAdoption() = runBlocking {
        verifyAdoption(familyOnly = true, chooseProton = false)
    }

    @Test fun nativePhoneticsSurviveExplicitProtonConflictChoice() = runBlocking {
        verifyAdoption(familyOnly = false, chooseProton = true)
    }

    @Test fun familyOnlyPhoneticsSurviveExplicitProtonConflictChoice() = runBlocking {
        verifyAdoption(familyOnly = true, chooseProton = true)
    }

    private suspend fun verifyAdoption(familyOnly: Boolean, chooseProton: Boolean) {
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        val imported = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))
        val baseline = mapper.project(imported)
        val observed = baseline.copy(rows = baseline.rows.map { row ->
            if (row.kind != AndroidRowKind.STRUCTURED_NAME) row else row.copy(components = row.components + mapOf(
                AndroidComponent.PHONETIC_GIVEN_NAME to if (familyOnly) "" else "Ay-da",
                AndroidComponent.PHONETIC_MIDDLE_NAME to if (familyOnly) "" else "Aw-gus-ta",
                AndroidComponent.PHONETIC_FAMILY_NAME to "Luv-lays",
            ))
        })
        val delta = mapper.applyControlledDelta(imported, baseline, observed)
        assertTrue(delta.changedValueIds.isNotEmpty())
        val mutation = database.withTransaction {
            RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)
                .applyContactDelta(imported.revision, delta.contact)
        }
        assertTrue(mutation is AndroidCanonicalMutationResult.Applied)
        val local = (mutation as AndroidCanonicalMutationResult.Applied).contact
        val phonetic = local.valuesOf(ContactValueKind.PHONETIC_NAME).single()
        val name = mapper.project(local).rows.single { it.kind == AndroidRowKind.STRUCTURED_NAME }
        assertEquals(phonetic.id, name.linkedCanonicalValueIds[AndroidLinkedValueRole.PHONETIC_NAME])
        val identityDao = database.androidProviderIdentityDao()
        val epoch = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT_ID)).providerEpoch
        val bindings = listOf("PRIMARY" to name.identity.canonicalValueId, "PHONETIC_NAME" to phonetic.id).map { (role, id) ->
            AndroidProviderRowBindingEntity(ACCOUNT.value, CONTACT_ID, epoch, 41L, name.identity.canonicalValueId,
                role, id, "fixture-account", AndroidRowKind.STRUCTURED_NAME.name, 42L, "ATTACHED")
        }
        database.withTransaction {
            identityDao.insertOwners(bindings.map { AndroidProviderIdentityOwnerEntity(ACCOUNT.value, CONTACT_ID, it.canonicalValueId) })
            identityDao.insertBindings(bindings)
        }
        // Complete the durable native intent using the real acknowledgement transaction.
        val executions = RoomMutationExecutionStore(database)
        val command = executions.eligible(ACCOUNT.value, 30_000L, 10).single()
        assertTrue(executions.claim(command, 30_000L))
        assertTrue(executions.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(CONTACT_ID, "version-1")))
        if (chooseProton) {
            val current = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))
            assertTrue(repository.saveContact(current.copy(displayName = "Concurrent local name")) is SaveResult.Saved)
        }

        remoteVersion = 2
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        if (chooseProton) {
            val conflicts = RoomContactConflictStore(database)
            val detail = requireNotNull(conflicts.detail(ACCOUNT.value, CONTACT_ID))
            assertTrue(conflicts.choose(ACCOUNT.value, detail.summary, ContactConflictChoice.PROTON))
            assertEquals(MutationPreparation.ResolvedWithoutUpload,
                conflicts.prepare(ACCOUNT.value, CONTACT_ID, detail.summary.localRevision, remoteCard()))
        }
        val adopted = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))
        assertEquals(phonetic, adopted.valuesOf(ContactValueKind.PHONETIC_NAME).single())
        assertEquals("Remote note 2", adopted.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals("Opaque remote", adopted.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY)
            .single { it.label == "X-PHONETIC-FIRST-NAME" }.value)
        assertNull(adopted.pendingMutationRevision)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        assertEquals(bindings.toSet(), identityDao.getAllForContact(ACCOUNT.value, CONTACT_ID).toSet())
        val projection = mapper.project(adopted)
        val projectedName = projection.rows.single { it.kind == AndroidRowKind.STRUCTURED_NAME }
        assertEquals(name.components.filterKeys { it.name.startsWith("PHONETIC_") },
            projectedName.components.filterKeys { it.name.startsWith("PHONETIC_") })
        assertEquals(name.linkedCanonicalValueIds, projectedName.linkedCanonicalValueIds)
        val providerReadBack = projection.copy(rows = projection.rows.mapIndexed { index, row ->
            row.copy(identity = row.identity.copy(providerRowId = 100L + index))
        })
        assertTrue(mapper.planProjection(providerReadBack, adopted).operations.isEmpty())
        assertTrue(mapper.applyControlledDelta(adopted, projection, providerReadBack).changedValueIds.isEmpty())

        database.close()
        database = ContakoDatabase.create(context, DATABASE_NAME)
        val readsBefore = hydrationCount
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(readsBefore, hydrationCount)
        assertEquals(phonetic, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID))
            .toDomain().valuesOf(ContactValueKind.PHONETIC_NAME).single())
        assertEquals(bindings.toSet(), database.androidProviderIdentityDao().getAllForContact(ACCOUNT.value, CONTACT_ID).toSet())
    }

    private fun remoteCard(): VerifiedContactCard {
        val remote = Contact(userId = UserId("fixture-user"), id = ContactId(CONTACT_ID),
            name = "Phonetic fixture", contactEmails = emptyList())
        val decoded = ProtonContactVCardCodec().decode(ACCOUNT.value, remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, card("FN:Phonetic fixture")),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned,
                card("N:Lovelace;Ada;;;\r\nNOTE:Remote note $remoteVersion\r\nX-PHONETIC-FIRST-NAME;X-KEEP=opaque:Opaque remote")),
        ))
        return VerifiedContactCard(RemoteContactId(CONTACT_ID), RemoteVersion("version-$remoteVersion"), decoded)
    }

    private fun stage() = IncrementalRemoteContactStage(
        ProtonContactInventoryGateway { _, _ -> GatewayOutcome.Success(ContactInventoryPage(
            contacts = listOf(ContactInventoryMetadata(RemoteContactId(CONTACT_ID), "Phonetic fixture",
                RemoteVersion("version-$remoteVersion"), 200, 10L + remoteVersion, emptyList(), emptyList(),
                ContactInventoryVersionProvenance.REMOTE_SERVER, ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION)),
            requestedCursor = null, nextCursor = null, totalCount = 1,
            snapshotAuthority = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
        )) },
        ProtonVerifiedContactCardGateway { _, _ -> hydrationCount++; GatewayOutcome.Success(remoteCard()) },
        PersistentContactInventoryPlanner(RoomContactInventoryCheckpointStore(database)),
        RoomRemoteCanonicalReconciliationStore(database),
    )

    private fun card(properties: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:phonetic-fixture\r\n$properties\r\nEND:VCARD\r\n"

    private companion object {
        const val DATABASE_NAME = "phonetic-complements.db"
        const val CONTACT_ID = "phonetic-contact"
        val ACCOUNT = AccountScope("phonetic-account")
    }
}
