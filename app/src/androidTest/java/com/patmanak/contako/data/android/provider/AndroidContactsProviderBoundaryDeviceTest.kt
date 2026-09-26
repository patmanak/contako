package com.patmanak.contako.data.android.provider

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidLinkedValueRole
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipRowOperation
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipWritePlan
import com.patmanak.contako.data.android.mapping.AndroidWritableGroupBinding
import com.patmanak.contako.data.android.mapping.AndroidProjectionPlan
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidRowOperation
import com.patmanak.contako.data.android.mapping.AndroidValueIdentity
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidContactsProviderBoundaryDeviceTest {
    private lateinit var context: Context
    private lateinit var accountManager: AccountManager
    private lateinit var primaryAccount: Account
    private lateinit var foreignAccount: Account
    private val createdRawContacts = mutableListOf<Pair<Long, Account>>()
    private val createdGroups = mutableListOf<Pair<Long, Account>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CONTACTS)
        accountManager = AccountManager.get(context)
        val suffix = System.nanoTime().toString(36)
        primaryAccount = Account("contako-provider-primary-$suffix", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        foreignAccount = Account("contako-provider-foreign-$suffix", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        check(accountManager.addAccountExplicitly(primaryAccount, null, null))
        check(accountManager.addAccountExplicitly(foreignAccount, null, null))
    }

    @After
    fun tearDown() {
        createdRawContacts.forEach { (rawContactId, account) ->
            context.contentResolver.delete(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, account),
                "${ContactsContract.RawContacts._ID} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(rawContactId.toString(), account.name, account.type),
            )
        }
        createdGroups.forEach { (groupRowId, account) ->
            context.contentResolver.delete(
                syncAdapterUri(ContactsContract.Groups.CONTENT_URI, account),
                "${ContactsContract.Groups._ID} = ? AND " +
                    "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.Groups.ACCOUNT_TYPE} = ?",
                arrayOf(groupRowId.toString(), account.name, account.type),
            )
        }
        if (::primaryAccount.isInitialized) accountManager.removeAccountExplicitly(primaryAccount)
        if (::foreignAccount.isInitialized) accountManager.removeAccountExplicitly(foreignAccount)
    }

    @Test
    fun stableObservationPageIncludesOnlyExactAccountRows() {
        val primaryRawId = insertRawContact(primaryAccount, SOURCE_ID)
        val foreignRawId = insertRawContact(foreignAccount, FOREIGN_SOURCE_ID)
        val primaryDataId = insertStructuredName(primaryAccount, primaryRawId, "Primary")
        insertStructuredName(foreignAccount, foreignRawId, "Foreign")

        val result = AndroidContactsProviderReader(context.contentResolver).readStableObservationPage(
            AndroidProviderAccountName(primaryAccount.name),
        )

        val page = (result as AndroidStableRawContactPageResult.Stable).page
        assertEquals(listOf(primaryRawId), page.observations.map { it.rawContact.rawContactId })
        assertEquals(listOf(primaryDataId), page.observations.single().dataRows.map { it.dataRowId })
        assertEquals(null, page.nextAfterRawContactId)
    }

    @Test
    fun stableObservationPageRejectsDataChangedBetweenRawVersions() {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val dataRowId = insertStructuredName(primaryAccount, rawContactId, "Before")
        val before = AndroidContactsProviderReader(context.contentResolver)
            .readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single()
        var mutationCount = 0
        val reader = AndroidContactsProviderReader(context.contentResolver) {
            mutationCount++
            assertEquals(
                1,
                context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    ContentValues().apply {
                        put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, "After")
                    },
                    "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                    arrayOf(dataRowId.toString(), rawContactId.toString()),
                ),
            )
        }

        assertEquals(
            AndroidStableRawContactPageResult.ReplanRequired,
            reader.readStableObservationPage(AndroidProviderAccountName(primaryAccount.name)),
        )
        assertEquals(1, mutationCount)
        val after = AndroidContactsProviderReader(context.contentResolver)
            .readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single()
        assertTrue(after.version > before.version)
        assertTrue(after.dirty)
    }

    @Test
    fun projectionPreservesOpaqueRcsRowsAndRejectsConcurrentMetadataChanges() = runBlocking {
        val rawId = insertRawContact(primaryAccount, SOURCE_ID)
        val account = AndroidProviderAccountName(primaryAccount.name)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val writer = AndroidContactsProviderWriter(context.contentResolver)
        val mapper = CanonicalAndroidContactMapper()
        val rcsUri = requireNotNull(context.contentResolver.insert(
            syncAdapterUri(ContactsContract.Data.CONTENT_URI, primaryAccount),
            ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, "vnd.android.cursor.item/rcs_data")
                put(ContactsContract.Data.DATA1, "synthetic-opaque-rcs")
                put(ContactsContract.Data.DATA2, "7")
            },
        ))
        val retained = reader.readDataRows(account, setOf(rawId)).single()
        val original = fixtureContact()
        val edited = original.copy(values = original.values.map {
            if (it.id == EMAIL_VALUE_ID) it.copy(value = "changed@example.test") else it
        })
        val removed = edited.copy(values = edited.values.filterNot { it.id == EMAIL_VALUE_ID })
        var current = AndroidContactSnapshot(original.id, emptyList())
        for (canonical in listOf(original, edited, removed)) {
            val plan = mapper.planProjection(current, canonical)
            val version = reader.readRawContactPage(account).contacts.single().version
            assertTrue(writer.applyProjectionPlan(account, rawId, version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID), SOURCE_ID, plan)
                is AndroidProviderProjectionResult.Applied)
            val route = AndroidProviderMimeRouter().route(rawId, reader.readDataRows(account, setOf(rawId)))
            assertEquals(listOf(retained), route.providerLocalRows.rows)
            assertTrue(route.unsupportedOwnedRows.rows.isEmpty())
            current = plan.desired.copy(rows = plan.desired.rows.map { row ->
                row.copy(identity = row.identity.copy(providerRowId = route.contactRows.rows.single {
                    it.canonicalValueId == row.identity.canonicalValueId
                }.dataRowId))
            })
        }
        val noChange = mapper.planProjection(current, removed)
        val before = reader.readRawContactPage(account).contacts.single()
        assertEquals(AndroidProviderProjectionResult.NoChangeValidated,
            writer.applyProjectionPlan(account, rawId, before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID), SOURCE_ID, noChange))
        assertEquals(before, reader.readRawContactPage(account).contacts.single())
        // Even an opaque metadata change must invalidate a stale observation/acknowledgement.
        context.contentResolver.update(ContactsContract.Data.CONTENT_URI,
            ContentValues().apply { put(ContactsContract.Data.DATA2, "8") },
            "${ContactsContract.Data._ID} = ?", arrayOf(ContentUris.parseId(rcsUri).toString()))
        assertEquals(AndroidProviderProjectionResult.ReplanRequired,
            writer.applyProjectionPlan(account, rawId, before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID), SOURCE_ID, noChange))
        assertTrue(reader.readRawContactPage(account).contacts.single().dirty)
    }

    @Test
    fun distinctAliasesSurviveAggregationAndPhotoWhileNativeRenameRemainsVisible() = runBlocking {
        val ownedRaw = insertRawContact(primaryAccount, SOURCE_ID)
        val foreignRaw = insertRawContact(foreignAccount, FOREIGN_SOURCE_ID)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val writer = AndroidContactsProviderWriter(context.contentResolver)
        val mapper = CanonicalAndroidContactMapper()
        val canonical = fixtureContact().copy(displayName = "Fixture alias")
        data class Fixture(val account: Account, val rawId: Long, val source: String, val alias: String)
        for ((account, rawId, source, alias) in listOf(
            Fixture(primaryAccount, ownedRaw, SOURCE_ID, canonical.displayName),
            Fixture(foreignAccount, foreignRaw, FOREIGN_SOURCE_ID, "Other fixture alias"),
        )) {
            val scope = AndroidProviderAccountName(account.name)
            val contact = canonical.copy(displayName = alias)
            val raw = reader.readRawContactPage(scope).contacts.single()
            assertTrue(writer.applyProjectionPlan(scope, rawId, raw.version,
                AndroidExpectedSourceIdentity.Present(source), source,
                mapper.planProjection(AndroidContactSnapshot(contact.id, emptyList()), contact))
                is AndroidProviderProjectionResult.Applied)
        }
        val scope = AndroidProviderAccountName(primaryAccount.name)
        val foreignScope = AndroidProviderAccountName(foreignAccount.name)
        fun name(account: AndroidProviderAccountName, raw: Long) = reader.readDataRows(account, setOf(raw))
            .single { it.mimeType == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }
        val ownedName = name(scope, ownedRaw)
        val foreignName = name(foreignScope, foreignRaw)
        assertEquals("Fixture alias", ownedName.stringSlots[0])
        assertEquals("Ada", ownedName.stringSlots[1])
        assertEquals("Lovelace", ownedName.stringSlots[2])
        assertEquals("Other fixture alias", foreignName.stringSlots[0])

        context.contentResolver.update(ContactsContract.AggregationExceptions.CONTENT_URI,
            ContentValues().apply {
                put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, ownedRaw)
                put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, foreignRaw)
            }, null, null)
        fun aggregate(raw: Long) = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?", arrayOf(raw.toString()), null,
        )!!.use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        assertEquals(aggregate(ownedRaw), aggregate(foreignRaw))
        val photoUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, ownedRaw)
            .buildUpon().appendPath(ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build()
        context.contentResolver.openAssetFileDescriptor(photoUri, "rw")!!.use { descriptor ->
            descriptor.createOutputStream().use { it.write(MINIMAL_PNG) }
        }
        assertEquals(ownedName.stringSlots, name(scope, ownedRaw).stringSlots)
        assertEquals(foreignName.stringSlots, name(foreignScope, foreignRaw).stringSlots)

        // A real display-name edit MUST remain observable even with an identical aggregate label.
        assertEquals(1, context.contentResolver.update(ContactsContract.Data.CONTENT_URI,
            ContentValues().apply { put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Edited fixture alias") },
            "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(ownedName.dataRowId.toString(), ownedRaw.toString())))
        assertEquals("Edited fixture alias", name(scope, ownedRaw).stringSlots[0])
        assertTrue(reader.readRawContactPage(scope).contacts.single().dirty)
        assertEquals(foreignName.stringSlots, name(foreignScope, foreignRaw).stringSlots)
    }

    @Test
    fun boundedProjectionUpdatesOnlyTheOwnedRawContactAndNoChangePerformsNoWrite() = runBlocking {
        val primaryRawId = insertRawContact(primaryAccount, SOURCE_ID)
        val foreignRawId = insertRawContact(foreignAccount, FOREIGN_SOURCE_ID)
        val mapper = CanonicalAndroidContactMapper()
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val writer = AndroidContactsProviderWriter(context.contentResolver)
        val canonical = fixtureContact()
        val initial = mapper.planProjection(AndroidContactSnapshot(canonical.id, emptyList()), canonical)
        val initialRawVersion = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == primaryRawId }.version

        val initialResult = writer.applyProjectionPlan(
            accountName = AndroidProviderAccountName(primaryAccount.name),
            rawContactId = primaryRawId,
            expectedRawContactVersion = initialRawVersion,
            expectedSourceIdentity = AndroidExpectedSourceIdentity.Present(SOURCE_ID),
            sourceIdentityAfterWrite = SOURCE_ID,
            plan = initial,
        )
        assertEquals(AndroidProviderProjectionResult.Applied(2), initialResult)

        val rawPage = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
        assertTrue(rawPage.contacts.any { it.rawContactId == primaryRawId && !it.dirty && !it.deleted })
        val insertedRows = reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(primaryRawId))
        assertEquals(setOf(NAME_VALUE_ID, EMAIL_VALUE_ID), insertedRows.mapNotNull { it.canonicalValueId }.toSet())

        val current = initial.desired.copy(
            rows = initial.desired.rows.map { row ->
                row.copy(
                    identity = row.identity.copy(
                        providerRowId = insertedRows.single { it.canonicalValueId == row.identity.canonicalValueId }.dataRowId,
                    ),
                )
            },
        )
        val edited = canonical.copy(
            values = canonical.values.map { value ->
                if (value.id == EMAIL_VALUE_ID) value.copy(value = "edited@example.test") else value
            },
        )
        val editPlan = mapper.planProjection(current, edited)
        val beforeEditVersion = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == primaryRawId }.version
        assertEquals(1, editPlan.operations.size)
        val emailProviderRowId = insertedRows.single { it.canonicalValueId == EMAIL_VALUE_ID }.dataRowId
        assertEquals(
            1,
            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.Email.ADDRESS, "concurrent@example.test")
                },
                "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                arrayOf(emailProviderRowId.toString(), primaryRawId.toString()),
            ),
        )
        val beforeStaleProjection = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == primaryRawId }
        val rowsBeforeStaleProjection = reader.readDataRows(
            AndroidProviderAccountName(primaryAccount.name),
            setOf(primaryRawId),
        )
        assertEquals(
            AndroidProviderProjectionResult.ReplanRequired,
            writer.applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                primaryRawId,
                beforeEditVersion,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                editPlan,
            ),
        )
        assertEquals(
            beforeStaleProjection,
            reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
                .contacts.single { it.rawContactId == primaryRawId },
        )
        assertEquals(
            rowsBeforeStaleProjection,
            reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(primaryRawId)),
        )
        assertEquals(
            AndroidProviderProjectionResult.Applied(1),
            writer.applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                primaryRawId,
                beforeStaleProjection.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                editPlan,
            ),
        )
        val editedRows = reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(primaryRawId))
        assertEquals(
            "edited@example.test",
            editedRows.single { it.canonicalValueId == EMAIL_VALUE_ID }.stringSlots.first(),
        )

        val currentAfterEdit = editPlan.desired.copy(
            rows = editPlan.desired.rows.map { row ->
                row.copy(
                    identity = row.identity.copy(
                        providerRowId = editedRows.single { it.canonicalValueId == row.identity.canonicalValueId }.dataRowId,
                    ),
                )
            },
        )
        val noChange = mapper.planProjection(currentAfterEdit, edited)
        val beforeNoChange = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == primaryRawId }
        val rowsBeforeNoChange = reader.readDataRows(
            AndroidProviderAccountName(primaryAccount.name),
            setOf(primaryRawId),
        )
        assertTrue(noChange.operations.isEmpty())
        assertEquals(
            AndroidProviderProjectionResult.NoChangeValidated,
            writer.applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                primaryRawId,
                beforeNoChange.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                noChange,
            ),
        )
        assertEquals(
            beforeNoChange,
            reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
                .contacts.single { it.rawContactId == primaryRawId },
        )
        assertEquals(
            rowsBeforeNoChange,
            reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(primaryRawId)),
        )

        assertEquals(
            1,
            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.Email.ADDRESS, "stale-noop@example.test")
                },
                "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                arrayOf(emailProviderRowId.toString(), primaryRawId.toString()),
            ),
        )
        val beforeStaleNoChange = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == primaryRawId }
        val rowsBeforeStaleNoChange = reader.readDataRows(
            AndroidProviderAccountName(primaryAccount.name),
            setOf(primaryRawId),
        )
        assertEquals(
            AndroidProviderProjectionResult.ReplanRequired,
            writer.applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                primaryRawId,
                beforeNoChange.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                noChange,
            ),
        )
        assertEquals(
            beforeStaleNoChange,
            reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
                .contacts.single { it.rawContactId == primaryRawId },
        )
        assertEquals(
            rowsBeforeStaleNoChange,
            reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(primaryRawId)),
        )

        val withoutEmail = edited.copy(values = edited.values.filterNot { it.id == EMAIL_VALUE_ID })
        val deletePlan = mapper.planProjection(currentAfterEdit, withoutEmail)
        val beforeDeleteVersion = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == primaryRawId }.version
        assertEquals(1, deletePlan.operations.size)
        assertEquals(
            AndroidProviderProjectionResult.Applied(1),
            writer.applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                primaryRawId,
                beforeDeleteVersion,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                deletePlan,
            ),
        )
        assertEquals(
            setOf(NAME_VALUE_ID),
            reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(primaryRawId))
                .mapNotNull { it.canonicalValueId }
                .toSet(),
        )

        val beforeExternalEdit = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == primaryRawId }.version
        val remainingNameRow = reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(primaryRawId))
            .single()
        assertEquals(
            1,
            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Externally edited")
                },
                "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                arrayOf(remainingNameRow.dataRowId.toString(), primaryRawId.toString()),
            ),
        )
        val afterExternalEdit = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == primaryRawId }
        assertTrue(afterExternalEdit.dirty)
        assertTrue(afterExternalEdit.version > beforeExternalEdit)
        assertEquals(
            AndroidProviderAcknowledgementResult.Stale,
            writer.acknowledgeObservation(
                AndroidProviderAccountName(primaryAccount.name),
                primaryRawId,
                beforeExternalEdit,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
            ),
        )
        assertEquals(
            AndroidProviderAcknowledgementResult.Acknowledged,
            writer.acknowledgeObservation(
                AndroidProviderAccountName(primaryAccount.name),
                primaryRawId,
                afterExternalEdit.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
            ),
        )

        val readScopeFailure = runCatching {
            reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(foreignRawId))
        }.exceptionOrNull() as AndroidProviderBoundaryException
        assertEquals(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH, readScopeFailure.category)

        val writeScopeFailure = runCatching {
            writer.applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                foreignRawId,
                0,
                AndroidExpectedSourceIdentity.Present(FOREIGN_SOURCE_ID),
                FOREIGN_SOURCE_ID,
                initial,
            )
        }.exceptionOrNull() as AndroidProviderBoundaryException
        assertEquals(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH, writeScopeFailure.category)
        assertEquals(
            0,
            reader.readDataRows(AndroidProviderAccountName(foreignAccount.name), setOf(foreignRawId)).size,
        )
    }

    @Test
    fun staleVersionClaimInsideBatchRequestsReplanAndRollsBackEveryProjectionOperation() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val mapper = CanonicalAndroidContactMapper()
        val plan = mapper.planProjection(AndroidContactSnapshot(fixtureContact().id, emptyList()), fixtureContact())
        val observedVersion = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == rawContactId }.version
        var injected = false
        val writer = AndroidContactsProviderWriter(
            contentResolver = context.contentResolver,
            beforeApplyBatch = {
                check(!injected)
                injected = true
                checkNotNull(
                    context.contentResolver.insert(
                        ContactsContract.Data.CONTENT_URI,
                        ContentValues().apply {
                            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                            put(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                            )
                            put(ContactsContract.CommonDataKinds.Note.NOTE, "concurrent edit")
                        },
                    ),
                )
            },
        )

        assertEquals(
            AndroidProviderProjectionResult.ReplanRequired,
            writer.applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                rawContactId,
                observedVersion,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                plan,
            ),
        )
        assertTrue(injected)
        val rows = reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(rawContactId))
        assertEquals(1, rows.size)
        assertTrue(rows.single().canonicalValueId == null)
        assertTrue(
            reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
                .contacts.single { it.rawContactId == rawContactId }.dirty,
        )
    }

    @Test
    fun adoptedSourceIdentityAllowsUnchangedNullSentinelButRejectsReplacement() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val plan = projectionPlan(emptyList())
        val before = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == rawContactId }

        assertEquals(
            AndroidProviderProjectionResult.NoChangeValidated,
            writer().applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                null,
                plan,
            ),
        )

        val failure = runCatching {
            writer().applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                "replacement-source",
                plan,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            before,
            reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
                .contacts.single { it.rawContactId == rawContactId },
        )
        assertTrue(reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(rawContactId)).isEmpty())
    }

    @Test
    fun missingSourceIdentityCanBeAdoptedWithoutDataRowMutation() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, null)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == rawContactId }

        assertEquals(
            AndroidProviderProjectionResult.Applied(changedDataRows = 0, sourceIdentityChanged = true),
            writer().applyProjectionPlan(
                AndroidProviderAccountName(primaryAccount.name),
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Missing,
                SOURCE_ID,
                projectionPlan(emptyList()),
            ),
        )
        val adopted = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == rawContactId }
        assertEquals(SOURCE_ID, adopted.sourceIdentity)
        assertTrue(reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(rawContactId)).isEmpty())
    }

    @Test
    fun missingSourceIdentityAdoptsOnlyAnExactUnclaimedDataRow() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, null)
        val dataRowId = insertStructuredName(primaryAccount, rawContactId, "Ada")
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(accountName)
            .contacts.single { it.rawContactId == rawContactId }
        val desiredRow = AndroidContactRow(
            identity = AndroidValueIdentity(NAME_VALUE_ID),
            kind = AndroidRowKind.STRUCTURED_NAME,
            value = "Ada",
            components = mapOf(AndroidComponent.GIVEN_NAME to "Ada"),
        )
        val snapshot = AndroidContactSnapshot("bounded-payload-contact", listOf(desiredRow))
        val plan = AndroidProjectionPlan(
            desired = snapshot,
            fingerprint = CanonicalAndroidContactMapper().fingerprint(snapshot),
            operations = listOf(
                AndroidRowOperation.Update(
                    currentIdentity = AndroidValueIdentity(NAME_VALUE_ID, dataRowId),
                    desired = desiredRow,
                ),
            ),
        )

        assertEquals(
            AndroidProviderProjectionResult.Applied(changedDataRows = 1, sourceIdentityChanged = true),
            writer().applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Missing,
                SOURCE_ID,
                plan,
            ),
        )
        assertEquals(
            SOURCE_ID,
            reader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId }.sourceIdentity,
        )
        assertEquals(
            NAME_VALUE_ID,
            reader.readDataRows(accountName, setOf(rawContactId)).single { it.dataRowId == dataRowId }.canonicalValueId,
        )
    }

    @Test
    fun missingSourceIdentityDoesNotReplaceForeignDataRowIdentity() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, null)
        val dataRowId = insertStructuredName(primaryAccount, rawContactId, "Ada", "foreign-value")
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(accountName)
            .contacts.single { it.rawContactId == rawContactId }
        val desiredRow = AndroidContactRow(
            identity = AndroidValueIdentity(NAME_VALUE_ID),
            kind = AndroidRowKind.STRUCTURED_NAME,
            value = "Ada",
            components = mapOf(AndroidComponent.GIVEN_NAME to "Ada"),
        )
        val snapshot = AndroidContactSnapshot("bounded-payload-contact", listOf(desiredRow))
        val plan = AndroidProjectionPlan(
            desired = snapshot,
            fingerprint = CanonicalAndroidContactMapper().fingerprint(snapshot),
            operations = listOf(
                AndroidRowOperation.Update(
                    currentIdentity = AndroidValueIdentity(NAME_VALUE_ID, dataRowId),
                    desired = desiredRow,
                ),
            ),
        )

        assertEquals(
            AndroidProviderProjectionResult.ReplanRequired,
            writer().applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Missing,
                SOURCE_ID,
                plan,
            ),
        )
        assertEquals(
            null,
            reader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId }.sourceIdentity,
        )
        assertEquals(
            "foreign-value",
            reader.readDataRows(accountName, setOf(rawContactId)).single { it.dataRowId == dataRowId }.canonicalValueId,
        )
    }

    @Test
    fun contactAndMembershipMutationsShareOneVersionAndGroupGuardedBatch() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val groupA = insertGroup(primaryAccount, "canonical-group-a", "remote-group-a")
        val groupB = insertGroup(primaryAccount, "canonical-group-b", "remote-group-b")
        val initialMembershipId = insertMembership(rawContactId, groupA)
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val contactReader = AndroidContactsProviderReader(context.contentResolver)
        val groupReader = AndroidGroupsProviderReader(context.contentResolver)
        val writer = writer()
        val mapper = CanonicalAndroidContactMapper()
        val canonical = fixtureContact()
        val contactPlan = mapper.planProjection(AndroidContactSnapshot(canonical.id, emptyList()), canonical)
        val before = contactReader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGroupMembershipWritePlan(
                account = AccountScope(canonical.accountId),
                androidAccountName = accountName,
                providerEpoch = 0,
                canonicalContactId = canonical.id,
                preferredEmailValueId = EMAIL_VALUE_ID,
                availability = AndroidGroupMembershipAvailability.AVAILABLE,
                desiredCanonicalGroupIds = setOf("canonical-group-b"),
                operations = listOf(
                    AndroidGroupMembershipRowOperation.Insert("canonical-group-b", groupA),
                ),
                assertedGroupBindings = listOf(
                    writableGroup("canonical-group-b", groupB, "remote-group-b"),
                ),
            )
        }
        assertEquals(listOf(groupA), groupReader.readMembershipRows(accountName, rawContactId).map { it.groupRowId })
        assertTrue(contactReader.readDataRows(accountName, setOf(rawContactId)).none { it.canonicalValueId != null })
        val membershipPlan = AndroidGroupMembershipWritePlan(
            account = AccountScope(canonical.accountId),
            androidAccountName = accountName,
            providerEpoch = 0,
            canonicalContactId = canonical.id,
            preferredEmailValueId = EMAIL_VALUE_ID,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
            desiredCanonicalGroupIds = setOf("canonical-group-b"),
            operations = listOf(
                AndroidGroupMembershipRowOperation.Delete("canonical-group-a", groupA, initialMembershipId),
                AndroidGroupMembershipRowOperation.Insert("canonical-group-b", groupB),
            ),
            assertedGroupBindings = listOf(
                writableGroup("canonical-group-a", groupA, "remote-group-a"),
                writableGroup("canonical-group-b", groupB, "remote-group-b"),
            ),
        )

        assertEquals(
            AndroidProviderProjectionResult.Applied(4),
            writer.applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                contactPlan,
                membershipPlan,
            ),
        )
        val membershipsAfterWrite = groupReader.readMembershipRows(accountName, rawContactId)
        assertEquals(listOf(groupB), membershipsAfterWrite.map { it.groupRowId })
        val insertedContactRows = contactReader.readDataRows(accountName, setOf(rawContactId))
            .filter { it.mimeType != ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE }
        assertEquals(setOf(NAME_VALUE_ID, EMAIL_VALUE_ID), insertedContactRows.mapNotNull { it.canonicalValueId }.toSet())

        val currentContact = contactPlan.desired.copy(
            rows = contactPlan.desired.rows.map { row ->
                row.copy(
                    identity = row.identity.copy(
                        providerRowId = insertedContactRows.single {
                            it.canonicalValueId == row.identity.canonicalValueId
                        }.dataRowId,
                    ),
                )
            },
        )
        val editedCanonical = canonical.copy(
            values = canonical.values.map { value ->
                if (value.id == EMAIL_VALUE_ID) value.copy(value = "guarded@example.test") else value
            },
        )
        val editPlan = mapper.planProjection(currentContact, editedCanonical)
        val membershipRow = membershipsAfterWrite.single()
        val staleGroupVersion = groupVersion(groupB)
        val noChangeMembershipPlan = AndroidGroupMembershipWritePlan(
            account = AccountScope(canonical.accountId),
            androidAccountName = accountName,
            providerEpoch = 0,
            canonicalContactId = canonical.id,
            preferredEmailValueId = EMAIL_VALUE_ID,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
            desiredCanonicalGroupIds = setOf("canonical-group-b"),
            operations = emptyList(),
            assertedGroupBindings = listOf(
                AndroidWritableGroupBinding(
                    "canonical-group-b",
                    groupB,
                    "remote-group-b",
                    staleGroupVersion,
                ),
            ),
        )
        val deleteMembershipPlan = AndroidGroupMembershipWritePlan(
            account = AccountScope(canonical.accountId),
            androidAccountName = accountName,
            providerEpoch = 0,
            canonicalContactId = canonical.id,
            preferredEmailValueId = EMAIL_VALUE_ID,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
            desiredCanonicalGroupIds = emptySet(),
            operations = listOf(
                AndroidGroupMembershipRowOperation.Delete(
                    "canonical-group-b",
                    groupB,
                    membershipRow.dataRowId,
                ),
            ),
            assertedGroupBindings = listOf(
                AndroidWritableGroupBinding(
                    "canonical-group-b",
                    groupB,
                    "remote-group-b",
                    staleGroupVersion,
                ),
            ),
        )
        val beforeStaleBatch = contactReader.readRawContactPage(accountName)
            .contacts.single { it.rawContactId == rawContactId }
        assertEquals(
            1,
            context.contentResolver.update(
                syncAdapterUri(ContactsContract.Groups.CONTENT_URI, primaryAccount),
                ContentValues().apply { put(ContactsContract.Groups.TITLE, "Concurrent title") },
                "${ContactsContract.Groups._ID} = ?",
                arrayOf(groupB.toString()),
            ),
        )

        assertEquals(
            AndroidProviderProjectionResult.ReplanRequired,
            writer.applyProjectionPlan(
                accountName,
                rawContactId,
                beforeStaleBatch.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                mapper.planProjection(currentContact, canonical),
                noChangeMembershipPlan,
            ),
        )

        assertEquals(
            AndroidProviderProjectionResult.ReplanRequired,
            writer.applyProjectionPlan(
                accountName,
                rawContactId,
                beforeStaleBatch.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                editPlan,
                deleteMembershipPlan,
            ),
        )
        assertEquals(
            "ada@example.test",
            contactReader.readDataRows(accountName, setOf(rawContactId))
                .single { it.canonicalValueId == EMAIL_VALUE_ID }.stringSlots.first(),
        )
        assertEquals(listOf(groupB), groupReader.readMembershipRows(accountName, rawContactId).map { it.groupRowId })
    }

    @Test
    fun membershipMutationFailsClosedWithoutCurrentRoomAuthorization() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val groupRowId = insertGroup(primaryAccount, "canonical-denied", "remote-denied")
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId }
        val deniedPlan = AndroidGroupMembershipWritePlan(
            account = AccountScope("synthetic-room-account"),
            androidAccountName = accountName,
            providerEpoch = 0,
            canonicalContactId = "bounded-payload-contact",
            preferredEmailValueId = EMAIL_VALUE_ID,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
            desiredCanonicalGroupIds = setOf("canonical-denied"),
            operations = listOf(
                AndroidGroupMembershipRowOperation.Insert("canonical-denied", groupRowId),
            ),
            assertedGroupBindings = listOf(
                writableGroup("canonical-denied", groupRowId, "remote-denied"),
            ),
        )

        assertEquals(
            AndroidProviderProjectionResult.ReplanRequired,
            AndroidContactsProviderWriter(context.contentResolver).applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                projectionPlan(emptyList()),
                deniedPlan,
            ),
        )
        assertTrue(
            AndroidGroupsProviderReader(context.contentResolver)
                .readMembershipRows(accountName, rawContactId)
                .isEmpty(),
        )
    }

    @Test
    fun suspendingMembershipAuthorizerReceivesProviderClaimAndCanDenyWithoutMutation() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val groupRowId = insertGroup(primaryAccount, "canonical-suspend-denied", "remote-suspend-denied")
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val contactReader = AndroidContactsProviderReader(context.contentResolver)
        val groupReader = AndroidGroupsProviderReader(context.contentResolver)
        val before = contactReader.readRawContactPage(accountName)
            .contacts.single { it.rawContactId == rawContactId }
        val expectedSource = AndroidExpectedSourceIdentity.Present(SOURCE_ID)
        val membershipPlan = AndroidGroupMembershipWritePlan(
            account = AccountScope("synthetic-room-account"),
            androidAccountName = accountName,
            providerEpoch = 0,
            canonicalContactId = "bounded-payload-contact",
            preferredEmailValueId = EMAIL_VALUE_ID,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
            desiredCanonicalGroupIds = setOf("canonical-suspend-denied"),
            operations = listOf(
                AndroidGroupMembershipRowOperation.Insert("canonical-suspend-denied", groupRowId),
            ),
            assertedGroupBindings = listOf(
                writableGroup("canonical-suspend-denied", groupRowId, "remote-suspend-denied"),
            ),
        )
        var receivedPlan: AndroidGroupMembershipWritePlan? = null
        var receivedRawContactId: Long? = null
        var receivedVersion: Long? = null
        var receivedExpectedSource: AndroidExpectedSourceIdentity? = null
        var receivedSourceAfterWrite: String? = null
        val writer = AndroidContactsProviderWriter(
            contentResolver = context.contentResolver,
            membershipWriteAuthorizer = AndroidGroupMembershipWriteAuthorizer {
                    plan,
                    claimedRawContactId,
                    claimedVersion,
                    claimedExpectedSource,
                    claimedSourceAfterWrite,
                ->
                yield()
                receivedPlan = plan
                receivedRawContactId = claimedRawContactId
                receivedVersion = claimedVersion
                receivedExpectedSource = claimedExpectedSource
                receivedSourceAfterWrite = claimedSourceAfterWrite
                false
            },
        )

        assertEquals(
            AndroidProviderProjectionResult.ReplanRequired,
            writer.applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                expectedSource,
                SOURCE_ID,
                projectionPlan(emptyList()),
                membershipPlan,
            ),
        )
        assertTrue(receivedPlan === membershipPlan)
        assertEquals(rawContactId, receivedRawContactId)
        assertEquals(before.version, receivedVersion)
        assertEquals(expectedSource, receivedExpectedSource)
        assertEquals(SOURCE_ID, receivedSourceAfterWrite)
        assertEquals(
            before,
            contactReader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId },
        )
        assertTrue(groupReader.readMembershipRows(accountName, rawContactId).isEmpty())

        val cancellation = CancellationException("SYNTHETIC_CANCELLATION")
        val cancellationWriter = AndroidContactsProviderWriter(
            contentResolver = context.contentResolver,
            membershipWriteAuthorizer = AndroidGroupMembershipWriteAuthorizer { _, _, _, _, _ ->
                throw cancellation
            },
        )
        assertTrue(
            runCatching {
                cancellationWriter.applyProjectionPlan(
                    accountName,
                    rawContactId,
                    before.version,
                    expectedSource,
                    SOURCE_ID,
                    projectionPlan(emptyList()),
                    membershipPlan,
                )
            }.exceptionOrNull() === cancellation,
        )
        assertTrue(groupReader.readMembershipRows(accountName, rawContactId).isEmpty())
    }

    @Test
    fun lostBatchReturnLeavesOneObservableMembershipForDurableReconciliation() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val groupRowId = insertGroup(primaryAccount, "canonical-lost-ack", "remote-lost-ack")
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId }
        val membershipPlan = AndroidGroupMembershipWritePlan(
            account = AccountScope("synthetic-room-account"),
            androidAccountName = accountName,
            providerEpoch = 0,
            canonicalContactId = "bounded-payload-contact",
            preferredEmailValueId = EMAIL_VALUE_ID,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
            desiredCanonicalGroupIds = setOf("canonical-lost-ack"),
            operations = listOf(
                AndroidGroupMembershipRowOperation.Insert("canonical-lost-ack", groupRowId),
            ),
            assertedGroupBindings = listOf(
                writableGroup("canonical-lost-ack", groupRowId, "remote-lost-ack"),
            ),
        )
        val writer = AndroidContactsProviderWriter(
            contentResolver = context.contentResolver,
            afterApplyBatch = { error("SIMULATED_LOST_BATCH_RETURN") },
            membershipWriteAuthorizer = AndroidGroupMembershipWriteAuthorizer { _, _, _, _, _ -> true },
        )

        val failure = runCatching {
            writer.applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                projectionPlan(emptyList()),
                membershipPlan,
            )
        }.exceptionOrNull() as AndroidProviderBoundaryException

        assertEquals(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA, failure.category)
        assertEquals(
            listOf(groupRowId),
            AndroidGroupsProviderReader(context.contentResolver)
                .readMembershipRows(accountName, rawContactId)
                .map { it.groupRowId },
        )
    }

    @Test
    fun membershipAssertionPayloadBudgetIncludesEveryRepeatedBindingValue() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId }
        val ids = (1..128).map { index -> "group-$index-" + "x".repeat(4_000) }
        val operations = ids.mapIndexed { index, id ->
            AndroidGroupMembershipRowOperation.Insert(id, 10_000L + index)
        }
        val plan = AndroidGroupMembershipWritePlan(
            account = AccountScope("synthetic-room-account"),
            androidAccountName = accountName,
            providerEpoch = 0,
            canonicalContactId = "bounded-payload-contact",
            preferredEmailValueId = EMAIL_VALUE_ID,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
            desiredCanonicalGroupIds = ids.toSet(),
            operations = operations,
            assertedGroupBindings = ids.mapIndexed { index, id ->
                AndroidWritableGroupBinding(
                    canonicalGroupId = id,
                    groupRowLocator = 10_000L + index,
                    sourceIdentity = "source-$index-" + "y".repeat(4_000),
                    expectedVersion = 1,
                )
            },
        )

        val failure = runCatching {
            writer().applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                projectionPlan(emptyList()),
                plan,
            )
        }.exceptionOrNull() as AndroidProviderBoundaryException

        assertEquals(AndroidProviderFailureCategory.BOUND_EXCEEDED, failure.category)
        assertEquals(
            before,
            reader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId },
        )
        assertTrue(reader.readDataRows(accountName, setOf(rawContactId)).isEmpty())
    }

    @Test
    fun maximumMembershipReplacementChunksGroupAssertionsInsideOneProviderBatch() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val groupRows = (1..192).associateWith { index ->
            insertGroup(primaryAccount, "canonical-max-$index", "remote-max-$index")
        }
        val currentMembershipRows = (1..128).associateWith { index ->
            insertMembership(rawContactId, groupRows.getValue(index))
        }
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId }
        val desiredIds = (65..192).mapTo(linkedSetOf()) { "canonical-max-$it" }
        val operations = buildList {
            (1..64).forEach { index ->
                add(
                    AndroidGroupMembershipRowOperation.Delete(
                        "canonical-max-$index",
                        groupRows.getValue(index),
                        currentMembershipRows.getValue(index),
                    ),
                )
            }
            (129..192).forEach { index ->
                add(AndroidGroupMembershipRowOperation.Insert("canonical-max-$index", groupRows.getValue(index)))
            }
        }
        val membershipPlan = AndroidGroupMembershipWritePlan(
            account = AccountScope("synthetic-room-account"),
            androidAccountName = accountName,
            providerEpoch = 0,
            canonicalContactId = "bounded-payload-contact",
            preferredEmailValueId = EMAIL_VALUE_ID,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
            desiredCanonicalGroupIds = desiredIds,
            operations = operations,
            assertedGroupBindings = (1..192).map { index ->
                writableGroup("canonical-max-$index", groupRows.getValue(index), "remote-max-$index")
            },
        )

        assertEquals(
            AndroidProviderProjectionResult.Applied(128),
            writer().applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                projectionPlan(emptyList()),
                membershipPlan,
            ),
        )
        assertEquals(
            (65..192).map { groupRows.getValue(it) },
            AndroidGroupsProviderReader(context.contentResolver)
                .readMembershipRows(accountName, rawContactId)
                .map { it.groupRowId },
        )
    }

    @Test
    fun textLinkedBinaryAndTotalPayloadBudgetsFailBeforeProviderWrite() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
            .contacts.single { it.rawContactId == rawContactId }
        val oversizedUtf8 = projectionPlan(
            listOf(
                AndroidContactRow(
                    AndroidValueIdentity("oversized-text"),
                    AndroidRowKind.NOTE,
                    value = "é".repeat(8_193),
                ),
            ),
        )
        val oversizedLinked = projectionPlan(
            listOf(
                AndroidContactRow(
                    AndroidValueIdentity("oversized-linked"),
                    AndroidRowKind.STRUCTURED_NAME,
                    value = "Name",
                    linkedCanonicalValueIds = mapOf(
                        AndroidLinkedValueRole.PHONETIC_NAME to "p".repeat(4_096),
                        AndroidLinkedValueRole.TITLE to "t".repeat(4_096),
                        AndroidLinkedValueRole.ROLE to "r".repeat(4_096),
                    ),
                ),
            ),
        )
        val payload = "x".repeat(16 * 1_024)
        val postalComponents = mapOf(
            AndroidComponent.PO_BOX to payload,
            AndroidComponent.EXTENDED_ADDRESS to payload,
            AndroidComponent.STREET to payload,
            AndroidComponent.LOCALITY to payload,
            AndroidComponent.REGION to payload,
            AndroidComponent.POSTCODE to payload,
            AndroidComponent.COUNTRY to payload,
            AndroidComponent.FORMATTED_ADDRESS to payload,
        )
        val oversizedBatch = projectionPlan(
            List(90) { index ->
                AndroidContactRow(
                    AndroidValueIdentity("postal-$index"),
                    AndroidRowKind.POSTAL_ADDRESS,
                    value = payload,
                    components = postalComponents,
                )
            },
        )
        val photo = AndroidContactRow(
            AndroidValueIdentity("oversized-photo"),
            AndroidRowKind.PHOTO,
            binaryReference = "cached-photo",
        )
        val oversizedBinary = projectionPlan(listOf(photo))
        val ordinaryWriter = writer()
        val oversizedBinaryWriter = AndroidContactsProviderWriter(
            context.contentResolver,
            AndroidProjectionBinaryLoader { ByteArray(256 * 1_024 + 1) },
        )

        listOf(
            ordinaryWriter to oversizedUtf8,
            ordinaryWriter to oversizedLinked,
            ordinaryWriter to oversizedBatch,
            oversizedBinaryWriter to oversizedBinary,
        ).forEach { (candidateWriter, plan) ->
            val failure = runCatching {
                candidateWriter.applyProjectionPlan(
                    AndroidProviderAccountName(primaryAccount.name),
                    rawContactId,
                    before.version,
                    AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                    SOURCE_ID,
                    plan,
                )
            }.exceptionOrNull() as AndroidProviderBoundaryException
            assertEquals(AndroidProviderFailureCategory.BOUND_EXCEEDED, failure.category)
            assertEquals(
                before,
                reader.readRawContactPage(AndroidProviderAccountName(primaryAccount.name))
                    .contacts.single { it.rawContactId == rawContactId },
            )
            assertTrue(
                reader.readDataRows(AndroidProviderAccountName(primaryAccount.name), setOf(rawContactId)).isEmpty(),
            )
        }
    }

    @Test
    fun opaqueLargePhotoReferenceIsNotCountedAsBinderText() = runBlocking {
        val rawContactId = insertRawContact(primaryAccount, SOURCE_ID)
        val accountName = AndroidProviderAccountName(primaryAccount.name)
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val before = reader.readRawContactPage(accountName).contacts.single { it.rawContactId == rawContactId }
        val photo = AndroidContactRow(
            AndroidValueIdentity("bounded-photo"),
            AndroidRowKind.PHOTO,
            value = "data:image/jpeg;base64," + "a".repeat(32 * 1_024),
            binaryReference = "data:image/jpeg;base64," + "b".repeat(32 * 1_024),
        )
        val candidate = AndroidContactsProviderWriter(
            context.contentResolver,
            AndroidProjectionBinaryLoader { MINIMAL_PNG },
            membershipWriteAuthorizer = AndroidGroupMembershipWriteAuthorizer { _, _, _, _, _ -> true },
        )

        assertEquals(
            AndroidProviderProjectionResult.Applied(1),
            candidate.applyProjectionPlan(
                accountName,
                rawContactId,
                before.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_ID),
                SOURCE_ID,
                projectionPlan(listOf(photo)),
            ),
        )
    }

    private fun writer() = AndroidContactsProviderWriter(
        contentResolver = context.contentResolver,
        membershipWriteAuthorizer = AndroidGroupMembershipWriteAuthorizer { _, _, _, _, _ -> true },
    )

    private fun projectionPlan(rows: List<AndroidContactRow>): AndroidProjectionPlan {
        val snapshot = AndroidContactSnapshot("bounded-payload-contact", rows)
        return AndroidProjectionPlan(
            desired = snapshot,
            fingerprint = CanonicalAndroidContactMapper().fingerprint(snapshot),
            operations = rows.map { AndroidRowOperation.Insert(it) },
        )
    }

    private fun insertRawContact(account: Account, sourceIdentity: String?): Long {
        val uri = context.contentResolver.insert(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, account),
            ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                if (sourceIdentity != null) put(ContactsContract.RawContacts.SOURCE_ID, sourceIdentity)
            },
        ) ?: error("Synthetic raw-contact insert failed")
        return ContentUris.parseId(uri).also { rawContactId -> createdRawContacts += rawContactId to account }
    }

    private fun insertStructuredName(
        account: Account,
        rawContactId: Long,
        givenName: String,
        canonicalValueId: String? = null,
    ): Long {
        val uri = context.contentResolver.insert(
            syncAdapterUri(ContactsContract.Data.CONTENT_URI, account),
            ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                )
                put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, givenName)
                if (canonicalValueId != null) put(ContactsContract.Data.SYNC1, canonicalValueId)
            },
        ) ?: error("Synthetic structured-name insert failed")
        return ContentUris.parseId(uri)
    }

    private fun insertGroup(account: Account, canonicalId: String, sourceIdentity: String): Long {
        val uri = context.contentResolver.insert(
            syncAdapterUri(ContactsContract.Groups.CONTENT_URI, account),
            ContentValues().apply {
                put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
                put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
                put(ContactsContract.Groups.TITLE, "Synthetic group")
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                put(ContactsContract.Groups.SHOULD_SYNC, 1)
                put(ContactsContract.Groups.SYNC1, canonicalId)
                put(ContactsContract.Groups.SOURCE_ID, sourceIdentity)
            },
        ) ?: error("Synthetic group insert failed")
        return ContentUris.parseId(uri).also { groupRowId -> createdGroups += groupRowId to account }
    }

    private fun insertMembership(rawContactId: Long, groupRowId: Long): Long {
        val uri = context.contentResolver.insert(
            syncAdapterUri(ContactsContract.Data.CONTENT_URI, primaryAccount),
            ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupRowId)
            },
        ) ?: error("Synthetic membership insert failed")
        return ContentUris.parseId(uri)
    }

    private fun writableGroup(canonicalId: String, groupRowId: Long, sourceIdentity: String) =
        AndroidWritableGroupBinding(canonicalId, groupRowId, sourceIdentity, groupVersion(groupRowId))

    private fun groupVersion(groupRowId: Long): Long = context.contentResolver.query(
        ContactsContract.Groups.CONTENT_URI,
        arrayOf(ContactsContract.Groups.VERSION),
        "${ContactsContract.Groups._ID} = ?",
        arrayOf(groupRowId.toString()),
        null,
    )?.use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    } ?: error("Synthetic group query failed")

    private fun fixtureContact() = CanonicalContact(
        accountId = "synthetic-room-account",
        id = "synthetic-canonical-contact",
        firstName = "Ada",
        lastName = "Lovelace",
        displayName = "Ada Lovelace",
        values = listOf(
            ContactValue(
                id = NAME_VALUE_ID,
                kind = ContactValueKind.STRUCTURED_NAME,
                value = "Lovelace;Ada;;;",
                order = 0,
                isPrimary = true,
                components = mapOf("given" to "Ada", "family" to "Lovelace"),
            ),
            ContactValue(
                id = EMAIL_VALUE_ID,
                kind = ContactValueKind.EMAIL,
                value = "ada@example.test",
                label = "WORK",
                order = 0,
                isPrimary = true,
            ),
        ),
    )

    private fun syncAdapterUri(base: android.net.Uri, account: Account): android.net.Uri = base.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    private companion object {
        val MINIMAL_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        const val SOURCE_ID = "synthetic-source"
        const val FOREIGN_SOURCE_ID = "synthetic-foreign-source"
        const val NAME_VALUE_ID = "name-value"
        const val EMAIL_VALUE_ID = "email-value"
    }
}
