package com.patmanak.contako.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRow
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AggregateType
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.MutationOperation
import com.patmanak.contako.data.local.RoomContactRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupOnlyProductionAndroidObservationCoordinatorDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
        runBlocking {
            val ledger = RoomAndroidProjectionLedger(database)
            val account = ledger.ensureAccount(ACCOUNT)
            ledger.bindAndroidAccountName(ACCOUNT, account.revision, ANDROID_ACCOUNT)
        }
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DB)
    }

    @Test
    fun androidCreatedGroupUsesUuidAndIsDurableBeforeProviderFinalization() = runBlocking {
        var finalized = false
        val repository = RoomContactRepository(
            database,
            clock = { 1_000 },
            elapsedRealtimeClock = { 1_000 },
        )
        val coordinator = GroupOnlyProductionAndroidBoundedObservationCoordinator.compose(
            database,
            repository,
            groupIdFactory = { CREATED_ID },
            providerFinalizer = { _, row, plan ->
                assertEquals(AndroidGroupObservationProviderAction.ADOPT, plan.providerAction)
                val groupId = plan.command.canonicalGroupId
                assertEquals(CREATED_ID.toString(), groupId)
                assertEquals(
                    "Synthetic group",
                    requireNotNull(database.contactGroupDao().get(ACCOUNT.value, groupId)).group.name,
                )
                assertEquals(
                    MutationOperation.UPSERT.name,
                    requireNotNull(database.outboxDao().get(
                        ACCOUNT.value,
                        AggregateType.GROUP.name,
                        groupId,
                    )).operation,
                )
                assertEquals(
                    row.version,
                    requireNotNull(database.androidGroupProjectionDao().getGroup(
                        ACCOUNT.value,
                        groupId,
                    )).providerVersion,
                )
                finalized = true
                AndroidBoundedPageResult.Applied
            },
        )
        val row = AndroidOwnedGroupRow(
            groupRowId = 41,
            canonicalGroupIdClaim = null,
            sourceIdentity = null,
            title = "Synthetic group",
            dirty = true,
            deleted = false,
            visible = true,
            shouldSync = true,
            version = 7,
        )
        val page = AndroidOwnedGroupRowPage(
            AndroidProviderAccountName(ANDROID_ACCOUNT),
            requestedAfterGroupRowId = 0,
            groups = listOf(row),
            nextAfterGroupRowId = null,
        )

        assertEquals(
            AndroidBoundedPageResult.Applied,
            coordinator.ingestGroups(
                AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT, accountRevision = 1, providerEpoch = 0),
                listOf(page),
            ),
        )
        assertEquals(true, finalized)

        var generatedAgain = 0
        val replay = GroupOnlyProductionAndroidBoundedObservationCoordinator.compose(
            database,
            repository,
            groupIdFactory = {
                generatedAgain++
                UUID.randomUUID()
            },
            providerFinalizer = { _, _, replayPlan ->
                assertEquals(CREATED_ID.toString(), replayPlan.command.canonicalGroupId)
                AndroidBoundedPageResult.Applied
            },
        )
        assertEquals(
            AndroidBoundedPageResult.Applied,
            replay.ingestGroups(
                AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT, accountRevision = 2, providerEpoch = 0),
                listOf(page),
            ),
        )
        assertEquals(0, generatedAgain)
    }

    @Test
    fun androidCreatedContactIsDurableBeforeExactProviderAcknowledgement() = runBlocking {
        val repository = RoomContactRepository(database)
        var acknowledgements = 0
        val contacts = ExistingProductionAndroidContactObservationCoordinator(
            database,
            repository,
            acknowledger = { accountName, rawId, version, claim, source, claimAfter ->
                assertEquals(AndroidProviderAccountName(ANDROID_ACCOUNT), accountName)
                assertEquals(51L, rawId)
                assertEquals(1L, version)
                assertEquals(null, claim)
                assertEquals(null, source)
                assertEquals(CREATED_CONTACT_ID, claimAfter)
                assertEquals(
                    1L,
                    requireNotNull(database.contactDao().get(ACCOUNT.value, CREATED_CONTACT_ID)).contact.revision,
                )
                assertEquals(
                    1L,
                    requireNotNull(database.androidProjectionLedgerDao().getUnifiedObservationCommitReceipt(
                        ACCOUNT.value,
                        CREATED_CONTACT_ID,
                    )).rawContactVersion,
                )
                acknowledgements++
                com.patmanak.contako.data.android.provider.AndroidProviderAcknowledgementResult.Acknowledged
            },
            contactIdFactory = { CREATED_CONTACT_ID },
        )
        val interoperability = AndroidInteroperabilityContext(
            ACCOUNT,
            ANDROID_ACCOUNT,
            accountRevision = 1,
            providerEpoch = 0,
        )
        contacts.acceptGroupCatalog(
            interoperability,
            listOf(AndroidOwnedGroupRowPage(AndroidProviderAccountName(ANDROID_ACCOUNT), 0, emptyList(), null)),
        )

        assertEquals(
            AndroidBoundedPageResult.Applied,
            contacts.ingest(
                interoperability,
                AndroidStableRawContactObservationPage(
                    listOf(AndroidStableRawContactObservation(
                        AndroidOwnedRawContact(51, null, null, dirty = true, deleted = false, version = 1),
                        emptyList(),
                    )),
                    null,
                ),
            ),
        )
        assertEquals(1, acknowledgements)
    }

    private companion object {
        const val DB = "group-production-observation.db"
        val ACCOUNT = AccountScope("synthetic-account")
        const val ANDROID_ACCOUNT = "synthetic-android-account"
        val CREATED_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000041")
        const val CREATED_CONTACT_ID = "00000000-0000-0000-0000-000000000051"
    }
}
