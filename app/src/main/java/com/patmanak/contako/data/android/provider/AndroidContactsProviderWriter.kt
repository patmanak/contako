package com.patmanak.contako.data.android.provider

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.OperationApplicationException
import android.os.RemoteException
import android.provider.ContactsContract
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidLinkedValueRole
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipRowOperation
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipWritePlan
import com.patmanak.contako.data.android.mapping.AndroidWritableGroupBinding
import com.patmanak.contako.data.android.mapping.AndroidProjectionPlan
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidRowOperation
import com.patmanak.contako.data.android.mapping.AndroidSemanticType
import com.patmanak.contako.data.android.mapping.AndroidValueIdentity
import java.nio.charset.StandardCharsets
import java.util.Base64

internal sealed interface AndroidExpectedSourceIdentity {
    data object Missing : AndroidExpectedSourceIdentity

    data class Present(val value: String) : AndroidExpectedSourceIdentity {
        init {
            require(value.isNotBlank() && value.length <= MAX_ID_LENGTH)
        }
    }

    private companion object {
        const val MAX_ID_LENGTH = 4_096
    }
}

internal fun interface AndroidProjectionBinaryLoader {
    /** Returns cached bytes only. This boundary MUST NOT perform network I/O. */
    fun load(reference: String): ByteArray?
}

internal sealed interface AndroidProviderProjectionResult {
    data class Applied(
        val changedDataRows: Int,
        val sourceIdentityChanged: Boolean = false,
    ) : AndroidProviderProjectionResult {
        init {
            require(changedDataRows >= 0)
            require(changedDataRows > 0 || sourceIdentityChanged)
        }
    }

    /** The exact account, source identity, and raw-contact version were observed without a write. */
    data object NoChangeValidated : AndroidProviderProjectionResult

    /** Provider state changed after planning; the caller must read and plan again. */
    data object ReplanRequired : AndroidProviderProjectionResult
}

/** Payload-free reason for a provider projection replan. */
internal enum class AndroidProviderProjectionReplanReason {
    RAW_CONTACT_VERSION,
    GROUP_BINDINGS,
    NO_CHANGE_AUTHORIZATION,
    DATA_ROW_SCOPE,
    MEMBERSHIP_ROW_SCOPE,
    WRITE_AUTHORIZATION,
    PROVIDER_TRANSACTION,
}

internal fun interface AndroidProviderProjectionReplanObserver {
    fun onReplan(reason: AndroidProviderProjectionReplanReason)
}

internal sealed interface AndroidProviderAcknowledgementResult {
    data object Acknowledged : AndroidProviderAcknowledgementResult
    data object Stale : AndroidProviderAcknowledgementResult
}

/**
 * Final fail-closed check against the current Room account epoch, canonical contact revision,
 * preferred-email context, and group-ledger revisions. Production composition MUST provide this
 * from the serialized account coordinator; the default writer denies every membership write.
 */
internal fun interface AndroidGroupMembershipWriteAuthorizer {
    suspend fun isCurrent(
        plan: AndroidGroupMembershipWritePlan,
        rawContactId: Long,
        expectedRawContactVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
        sourceIdentityAfterWrite: String?,
    ): Boolean
}

/** Proves an editor-created row's identity from durable Room bindings, never from provider claims. */
internal fun interface AndroidUnclaimedDataRowAuthorizer {
    suspend fun isCurrent(
        plan: AndroidGroupMembershipWritePlan,
        rawContactId: Long,
        identities: List<AndroidValueIdentity>,
    ): Boolean
}

/** Applies one contact's already-durable projection plan as one bounded provider transaction. */
internal class AndroidContactsProviderWriter(
    private val contentResolver: ContentResolver,
    private val binaryLoader: AndroidProjectionBinaryLoader = AndroidProjectionBinaryLoader { null },
    private val beforeApplyBatch: () -> Unit = {},
    private val afterApplyBatch: () -> Unit = {},
    private val membershipWriteAuthorizer: AndroidGroupMembershipWriteAuthorizer =
        AndroidGroupMembershipWriteAuthorizer { _, _, _, _, _ -> false },
    private val replanObserver: AndroidProviderProjectionReplanObserver =
        AndroidProviderProjectionReplanObserver { },
    private val unclaimedDataRowAuthorizer: AndroidUnclaimedDataRowAuthorizer =
        AndroidUnclaimedDataRowAuthorizer { _, _, _ -> false },
) {
    fun acknowledgeObservation(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expectedRawContactVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
    ): AndroidProviderAcknowledgementResult {
        require(rawContactId > 0)
        require(expectedRawContactVersion >= 0)
        assertOwnedRawContactIdentity(accountName, rawContactId, expectedSourceIdentity)
        val updated = try {
            contentResolver.update(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, accountName),
                ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 0) },
                rawContactSelection(expectedSourceIdentity),
                rawContactSelectionArgs(accountName, rawContactId, expectedRawContactVersion, expectedSourceIdentity),
            )
        } catch (_: SecurityException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        }
        return when (updated) {
            0 -> AndroidProviderAcknowledgementResult.Stale
            1 -> AndroidProviderAcknowledgementResult.Acknowledged
            else -> throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        }
    }

    fun acknowledgeContactObservation(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expectedRawContactVersion: Long,
        expectedCanonicalContactIdClaim: String?,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
        canonicalContactIdClaimAfterWrite: String = requireNotNull(expectedCanonicalContactIdClaim),
    ): AndroidProviderAcknowledgementResult {
        require(rawContactId > 0 && expectedRawContactVersion >= 0)
        require(canonicalContactIdClaimAfterWrite.isNotBlank())
        val sourceClause = when (expectedSourceIdentity) {
            AndroidExpectedSourceIdentity.Missing -> "${ContactsContract.RawContacts.SOURCE_ID} IS NULL"
            is AndroidExpectedSourceIdentity.Present -> "${ContactsContract.RawContacts.SOURCE_ID} = ?"
        }
        val claimClause = if (expectedCanonicalContactIdClaim == null) {
            "${ContactsContract.RawContacts.SYNC1} IS NULL"
        } else {
            "${ContactsContract.RawContacts.SYNC1} = ?"
        }
        val selection = "${ContactsContract.RawContacts._ID} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.RawContacts.VERSION} = ? AND $claimClause AND $sourceClause"
        val args = buildList {
            add(rawContactId.toString())
            add(accountName.value)
            add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            add(expectedRawContactVersion.toString())
            if (expectedCanonicalContactIdClaim != null) add(expectedCanonicalContactIdClaim)
            if (expectedSourceIdentity is AndroidExpectedSourceIdentity.Present) add(expectedSourceIdentity.value)
        }.toTypedArray()
        val updated = try {
            contentResolver.update(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, accountName),
                ContentValues().apply {
                    put(ContactsContract.RawContacts.DIRTY, 0)
                    put(ContactsContract.RawContacts.SYNC1, canonicalContactIdClaimAfterWrite)
                },
                selection,
                args,
            )
        } catch (_: SecurityException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        }
        return when (updated) {
            0 -> AndroidProviderAcknowledgementResult.Stale
            1 -> AndroidProviderAcknowledgementResult.Acknowledged
            else -> throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        }
    }

    suspend fun applyProjectionPlan(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expectedRawContactVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
        sourceIdentityAfterWrite: String?,
        plan: AndroidProjectionPlan,
        membershipPlan: AndroidGroupMembershipWritePlan? = null,
    ): AndroidProviderProjectionResult {
        require(rawContactId > 0)
        require(expectedRawContactVersion >= 0)
        require(sourceIdentityAfterWrite == null || sourceIdentityAfterWrite.isNotBlank())
        require(sourceIdentityAfterWrite == null || sourceIdentityAfterWrite.length <= MAX_ID_LENGTH)
        val membershipOperations = membershipPlan?.operations.orEmpty()
        validateMembershipPlanIdentity(accountName, plan, membershipPlan)
        val assertedGroupBindingChunks = membershipPlan?.assertedGroupBindings.orEmpty()
            .chunked(MAX_GROUP_BINDINGS_PER_ASSERTION)
        val primaryUpdates = plan.operations.filterIsInstance<AndroidRowOperation.Update>()
            .filter { it.desired.isPrimary || it.desired.isSuperPrimary }
        val providerOperationCount = 2 + plan.operations.size + membershipOperations.size +
            assertedGroupBindingChunks.size + primaryUpdates.size
        if (plan.operations.size + membershipOperations.size > MAX_DATA_OPERATIONS ||
            providerOperationCount > MAX_BATCH_OPERATIONS
        ) {
            boundedFailure()
        }
        require(plan.desired.rows.all { it.identity.canonicalValueId.length <= MAX_ID_LENGTH })
        if (expectedSourceIdentity is AndroidExpectedSourceIdentity.Present) {
            require(sourceIdentityAfterWrite == null || sourceIdentityAfterWrite == expectedSourceIdentity.value) {
                "An adopted source identity cannot be replaced"
            }
        }
        val sourceChangeRequired = when (expectedSourceIdentity) {
            AndroidExpectedSourceIdentity.Missing -> sourceIdentityAfterWrite != null
            is AndroidExpectedSourceIdentity.Present -> false
        }
        assertOwnedRawContactIdentity(accountName, rawContactId, expectedSourceIdentity)
        if (!hasExactRawContactVersion(
                accountName,
                rawContactId,
                expectedRawContactVersion,
                expectedSourceIdentity,
            )
        ) {
            return replan(AndroidProviderProjectionReplanReason.RAW_CONTACT_VERSION)
        }
        if (plan.operations.isEmpty() && membershipOperations.isEmpty() && !sourceChangeRequired) {
            validateProjectionPayload(accountName, sourceIdentityAfterWrite, plan, membershipPlan)
            if (!hasExactOwnedGroups(accountName, membershipPlan?.assertedGroupBindings.orEmpty())) {
                return replan(AndroidProviderProjectionReplanReason.GROUP_BINDINGS)
            }
            if (membershipPlan != null && !membershipWriteAuthorizer.isCurrent(
                    membershipPlan,
                    rawContactId,
                    expectedRawContactVersion,
                    expectedSourceIdentity,
                    sourceIdentityAfterWrite,
                )
            ) {
                return replan(AndroidProviderProjectionReplanReason.NO_CHANGE_AUTHORIZATION)
            }
            return AndroidProviderProjectionResult.NoChangeValidated
        }
        val existingIdentities = plan.operations.mapNotNull { operation ->
            when (operation) {
                is AndroidRowOperation.Insert -> null
                is AndroidRowOperation.Update -> operation.currentIdentity
                is AndroidRowOperation.Delete -> operation.currentIdentity
            }
        }
        var allowUnclaimedRows = sourceChangeRequired
        if (!hasExactOwnedDataRows(rawContactId, plan.operations, allowUnclaimedRows)) {
            // A source ID can already be committed while an unchanged editor-created Data row
            // still has no SYNC1. Resume only with its exact durable account/epoch/row binding.
            if (membershipPlan == null || !unclaimedDataRowAuthorizer.isCurrent(
                    membershipPlan, rawContactId, existingIdentities,
                ) || !hasExactOwnedDataRows(rawContactId, plan.operations, true)
            ) return replan(AndroidProviderProjectionReplanReason.DATA_ROW_SCOPE)
            allowUnclaimedRows = true
        }
        if (!hasExactOwnedGroupMembershipRows(rawContactId, membershipOperations)) {
            return replan(AndroidProviderProjectionReplanReason.MEMBERSHIP_ROW_SCOPE)
        }
        val validatedPayload = validateProjectionPayload(accountName, sourceIdentityAfterWrite, plan, membershipPlan)

        val dataUri = syncAdapterUri(ContactsContract.Data.CONTENT_URI, accountName)
        val rawUri = syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, accountName)
        val groupsUri = syncAdapterUri(ContactsContract.Groups.CONTENT_URI, accountName)
        val operations = ArrayList<ContentProviderOperation>(
            plan.operations.size + membershipOperations.size + 3,
        )
        val insertOperationIndexes = mutableListOf<Int>()
        // Claim the exact observed raw-contact version first. The provider executes the batch in
        // one transaction; later Data mutations may themselves advance RawContacts.VERSION.
        operations += ContentProviderOperation.newAssertQuery(rawUri)
            .withSelection(
                rawContactSelection(expectedSourceIdentity),
                rawContactSelectionArgs(accountName, rawContactId, expectedRawContactVersion, expectedSourceIdentity),
            )
            .withExpectedCount(1)
            .build()
        assertedGroupBindingChunks.forEach { assertedGroupBindings ->
            operations += ContentProviderOperation.newAssertQuery(groupsUri)
                .withSelection(
                    groupBindingSelection(assertedGroupBindings),
                    groupBindingSelectionArgs(accountName, assertedGroupBindings),
                )
                .withExpectedCount(assertedGroupBindings.size)
                .build()
        }
        plan.operations.forEach { operation ->
            if (operation is AndroidRowOperation.Insert) insertOperationIndexes += operations.size
            operations += when (operation) {
                is AndroidRowOperation.Insert -> ContentProviderOperation.newInsert(dataUri)
                    .withValues(operation.desired.toContentValues(rawContactId, validatedPayload.binaryFor(operation.desired)))
                    .build()
                is AndroidRowOperation.Update -> ContentProviderOperation.newUpdate(dataUri)
                    .withSelection(
                        dataRowSelection(allowUnclaimedRows),
                        dataRowSelectionArgs(operation.currentIdentity, rawContactId),
                    )
                    .withValues(operation.desired.toContentValues(rawContactId, validatedPayload.binaryFor(operation.desired)).apply {
                        if (operation.desired.isPrimary || operation.desired.isSuperPrimary) {
                            put(ContactsContract.Data.IS_PRIMARY, 0)
                            put(ContactsContract.Data.IS_SUPER_PRIMARY, 0)
                        }
                    })
                    // No expected count, for the same reason as the acknowledgement below: the
                    // provider reports rows it changed, so an update whose values already match
                    // returns 0 and would abort the whole batch. A projection replays the desired
                    // state, so writing values identical to the current ones is normal. The
                    // selector still pins row id, raw contact and canonical value id, and the
                    // caller verifies the post-write fingerprint, so nothing is written blindly.
                    .build()
                is AndroidRowOperation.Delete -> ContentProviderOperation.newDelete(dataUri)
                    .withSelection(
                        dataRowSelection(allowUnclaimedRows),
                        dataRowSelectionArgs(operation.currentIdentity, rawContactId),
                    )
                    .withExpectedCount(1)
                    .build()
            }
        }
        // ContactsProvider treats a zero super-primary flag as a clearing request and ignores
        // a simultaneous primary promotion. Clear first, then promote after all row mutations,
        // so neither that clearing branch nor a later demotion erases the desired preference.
        // Both phases remain inside the same ownership/version-guarded transaction.
        primaryUpdates.forEach { operation ->
            operations += ContentProviderOperation.newUpdate(dataUri)
                .withSelection(
                    dataRowSelection(allowUnclaimedRows),
                    dataRowSelectionArgs(operation.currentIdentity, rawContactId),
                )
                .withValue(ContactsContract.Data.IS_PRIMARY, 1)
                .apply {
                    if (operation.desired.isSuperPrimary) {
                        withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                    }
                }
                .build()
        }
        membershipOperations.forEach { operation ->
            if (operation is AndroidGroupMembershipRowOperation.Insert) insertOperationIndexes += operations.size
            operations += when (operation) {
                is AndroidGroupMembershipRowOperation.Insert -> ContentProviderOperation.newInsert(dataUri)
                    .withValues(ContentValues().apply {
                        put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                        put(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                        )
                        put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, operation.groupRowLocator)
                    })
                    .build()
                is AndroidGroupMembershipRowOperation.Delete -> ContentProviderOperation.newDelete(dataUri)
                    .withSelection(
                        "${ContactsContract.Data._ID} = ? AND " +
                            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                            "${ContactsContract.Data.MIMETYPE} = ? AND " +
                            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?",
                        arrayOf(
                            operation.dataRowLocator.toString(),
                            rawContactId.toString(),
                            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                            operation.groupRowLocator.toString(),
                        ),
                    )
                    .withExpectedCount(1)
                    .build()
            }
        }
        // Acknowledge only after every contact and membership mutation in this provider transaction.
        //
        // No expected count. ContactsProvider reports rows it actually changed, and setting DIRTY
        // to the value a row already holds changes nothing, so it returns 0 and
        // `withExpectedCount(1)` aborts the whole batch with "Expected 1 rows but actual 0".
        // Every raw contact written by this sync adapter already has DIRTY = 0, which made the
        // acknowledgement a guaranteed no-op and the batch a guaranteed failure.
        //
        // Ownership is still asserted at the head of this batch through account, version and
        // source identity, and the caller verifies the post-write state, so dropping the count
        // here does not weaken the scope guarantee.
        operations += ContentProviderOperation.newUpdate(rawUri)
            .withSelection(
                rawContactIdentitySelection(expectedSourceIdentity),
                rawContactIdentitySelectionArgs(accountName, rawContactId, expectedSourceIdentity),
            )
            .withValue(ContactsContract.RawContacts.DIRTY, 0)
            .apply {
                if (sourceIdentityAfterWrite != null) {
                    withValue(ContactsContract.RawContacts.SOURCE_ID, sourceIdentityAfterWrite)
                }
            }
            .build()
        if (operations.size > MAX_BATCH_OPERATIONS) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
        }

        beforeApplyBatch()
        if (allowUnclaimedRows && !sourceChangeRequired &&
            (membershipPlan == null || !unclaimedDataRowAuthorizer.isCurrent(
                membershipPlan, rawContactId, existingIdentities,
            ))
        ) return replan(AndroidProviderProjectionReplanReason.DATA_ROW_SCOPE)
        if (membershipPlan != null && !membershipWriteAuthorizer.isCurrent(
                membershipPlan,
                rawContactId,
                expectedRawContactVersion,
                expectedSourceIdentity,
                sourceIdentityAfterWrite,
            )
        ) {
            return replan(AndroidProviderProjectionReplanReason.WRITE_AUTHORIZATION)
        }
        try {
            val results = contentResolver.applyBatch(ContakoAndroidAccountContract.CONTACTS_AUTHORITY, operations)
            if (results.size != operations.size || insertOperationIndexes.any { results[it].uri == null }) {
                malformedFailure()
            }
            afterApplyBatch()
        } catch (_: SecurityException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
        } catch (_: RemoteException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
        } catch (_: OperationApplicationException) {
            return replan(AndroidProviderProjectionReplanReason.PROVIDER_TRANSACTION)
        } catch (error: AndroidProviderBoundaryException) {
            throw error
        } catch (_: RuntimeException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        }
        return AndroidProviderProjectionResult.Applied(
            changedDataRows = plan.operations.size + membershipOperations.size,
            sourceIdentityChanged = sourceChangeRequired,
        )
    }

    private fun replan(reason: AndroidProviderProjectionReplanReason): AndroidProviderProjectionResult {
        runCatching { replanObserver.onReplan(reason) }
        return AndroidProviderProjectionResult.ReplanRequired
    }

    private fun hasExactRawContactVersion(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expectedRawContactVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
    ): Boolean {
        val count = queryCount(
            ContactsContract.RawContacts.CONTENT_URI,
            rawContactSelection(expectedSourceIdentity),
            rawContactSelectionArgs(accountName, rawContactId, expectedRawContactVersion, expectedSourceIdentity),
        )
        if (count > 1) throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        return count == 1
    }

    private fun assertOwnedRawContactIdentity(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
    ) {
        val sourceClause = when (expectedSourceIdentity) {
            AndroidExpectedSourceIdentity.Missing -> "${ContactsContract.RawContacts.SOURCE_ID} IS NULL"
            is AndroidExpectedSourceIdentity.Present -> "${ContactsContract.RawContacts.SOURCE_ID} = ?"
        }
        val args = buildList {
            add(rawContactId.toString())
            add(accountName.value)
            add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            if (expectedSourceIdentity is AndroidExpectedSourceIdentity.Present) add(expectedSourceIdentity.value)
        }.toTypedArray()
        val count = queryCount(
            ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts._ID} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND $sourceClause",
            args,
        )
        if (count != 1) throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
    }

    private fun hasExactOwnedDataRows(
        rawContactId: Long,
        operations: List<AndroidRowOperation>,
        allowUnclaimedRows: Boolean,
    ): Boolean {
        val expected = operations.mapNotNull { operation ->
            when (operation) {
                is AndroidRowOperation.Insert -> null
                is AndroidRowOperation.Update -> operation.currentIdentity
                is AndroidRowOperation.Delete -> operation.currentIdentity
            }
        }
        if (expected.map { requireNotNull(it.providerRowId) }.distinct().size != expected.size) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        }
        return expected.all { identity ->
            val count = queryCount(
                ContactsContract.Data.CONTENT_URI,
                dataRowSelection(allowUnclaimedRows),
                dataRowSelectionArgs(identity, rawContactId),
            )
            if (count > 1) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
            }
            count == 1
        }
    }

    /**
     * Android editors create Data rows without Contako's canonical identity. Initial adoption,
     * or explicit proof of the exact durable row binding, may accept a null SYNC1. A non-null
     * foreign value is never accepted. The batch still asserts raw-contact ownership/version.
     */
    private fun dataRowSelection(allowUnclaimedRows: Boolean): String =
        "${ContactsContract.Data._ID} = ? AND " +
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
            if (allowUnclaimedRows) "($DATA_SYNC1 IS NULL OR $DATA_SYNC1 = ?)" else "$DATA_SYNC1 = ?"

    private fun dataRowSelectionArgs(identity: AndroidValueIdentity, rawContactId: Long): Array<String> =
        arrayOf(
            requireNotNull(identity.providerRowId).toString(),
            rawContactId.toString(),
            identity.canonicalValueId,
        )

    private fun hasExactOwnedGroupMembershipRows(
        rawContactId: Long,
        operations: List<AndroidGroupMembershipRowOperation>,
    ): Boolean {
        val deletes = operations.filterIsInstance<AndroidGroupMembershipRowOperation.Delete>()
        if (deletes.map(AndroidGroupMembershipRowOperation.Delete::dataRowLocator).distinct().size != deletes.size) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        }
        return deletes.all { operation ->
            val count = queryCount(
                ContactsContract.Data.CONTENT_URI,
                "${ContactsContract.Data._ID} = ? AND " +
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                    "${ContactsContract.Data.MIMETYPE} = ? AND " +
                    "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?",
                arrayOf(
                    operation.dataRowLocator.toString(),
                    rawContactId.toString(),
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                    operation.groupRowLocator.toString(),
                ),
            )
            if (count > 1) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
            }
            count == 1
        }
    }

    private fun hasExactOwnedGroups(
        accountName: AndroidProviderAccountName,
        bindings: List<AndroidWritableGroupBinding>,
    ): Boolean {
        if (bindings.isEmpty()) return true
        return bindings.chunked(MAX_GROUP_BINDINGS_PER_ASSERTION).all { chunk ->
            val count = queryCount(
                ContactsContract.Groups.CONTENT_URI,
                groupBindingSelection(chunk),
                groupBindingSelectionArgs(accountName, chunk),
            )
            if (count > chunk.size) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
            }
            count == chunk.size
        }
    }

    private fun validateMembershipPlanIdentity(
        accountName: AndroidProviderAccountName,
        contactPlan: AndroidProjectionPlan,
        membershipPlan: AndroidGroupMembershipWritePlan?,
    ) {
        membershipPlan ?: return
        if (membershipPlan.androidAccountName != accountName ||
            membershipPlan.canonicalContactId != contactPlan.desired.canonicalContactId
        ) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        }
        val bindingsById = membershipPlan.assertedGroupBindings.associateBy(AndroidWritableGroupBinding::canonicalGroupId)
        if (bindingsById.size != membershipPlan.assertedGroupBindings.size ||
            membershipPlan.operations.any { operation ->
                bindingsById[operation.canonicalGroupId]?.groupRowLocator != operation.groupRowLocator
            }
        ) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        }
    }

    private fun queryCount(uri: android.net.Uri, selection: String, args: Array<String>): Int = try {
        contentResolver.query(uri, arrayOf(ContactsContract.Data._ID), selection, args, null)?.use { cursor ->
            var count = 0
            while (cursor.moveToNext()) count += 1
            count
        } ?: throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
    } catch (_: SecurityException) {
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
    } catch (error: AndroidProviderBoundaryException) {
        throw error
    } catch (_: RuntimeException) {
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
    }

    private fun AndroidContactRow.toContentValues(
        rawContactId: Long,
        validatedBinary: ByteArray?,
    ): ContentValues = ContentValues().apply {
        put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
        put(ContactsContract.Data.MIMETYPE, kind.mimeType())
        put(DATA_SYNC1, identity.canonicalValueId)
        put(DATA_SYNC2, order.toString())
        put(DATA_SYNC3, linkedCanonicalIdsEncoding())
        put(ContactsContract.Data.IS_PRIMARY, isPrimary.asInt())
        put(ContactsContract.Data.IS_SUPER_PRIMARY, isSuperPrimary.asInt())
        when (kind) {
            AndroidRowKind.STRUCTURED_NAME -> {
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, component(AndroidComponent.DISPLAY_NAME, value))
                put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, component(AndroidComponent.GIVEN_NAME))
                put(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, component(AndroidComponent.MIDDLE_NAME))
                put(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, component(AndroidComponent.FAMILY_NAME))
                put(ContactsContract.CommonDataKinds.StructuredName.PREFIX, component(AndroidComponent.PREFIX))
                put(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, component(AndroidComponent.SUFFIX))
                put(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME, component(AndroidComponent.PHONETIC_GIVEN_NAME))
                put(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME, component(AndroidComponent.PHONETIC_MIDDLE_NAME))
                put(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME, component(AndroidComponent.PHONETIC_FAMILY_NAME))
            }
            AndroidRowKind.EMAIL -> this@toContentValues.putTypedValue(this,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Email.LABEL,
                semanticType.emailType(),
            )
            AndroidRowKind.PHONE -> this@toContentValues.putTypedValue(this,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                semanticType.phoneType(),
            )
            AndroidRowKind.POSTAL_ADDRESS -> {
                put(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, component(AndroidComponent.FORMATTED_ADDRESS, value))
                putNullableInt(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, semanticType.postalType())
                this@toContentValues.putCustomLabel(this, ContactsContract.CommonDataKinds.StructuredPostal.LABEL)
                put(ContactsContract.CommonDataKinds.StructuredPostal.POBOX, component(AndroidComponent.PO_BOX))
                put(ContactsContract.CommonDataKinds.StructuredPostal.STREET, component(AndroidComponent.STREET))
                put(ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD, component(AndroidComponent.EXTENDED_ADDRESS))
                put(ContactsContract.CommonDataKinds.StructuredPostal.CITY, component(AndroidComponent.LOCALITY))
                put(ContactsContract.CommonDataKinds.StructuredPostal.REGION, component(AndroidComponent.REGION))
                put(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, component(AndroidComponent.POSTCODE))
                put(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, component(AndroidComponent.COUNTRY))
            }
            AndroidRowKind.ORGANIZATION -> {
                put(ContactsContract.CommonDataKinds.Organization.COMPANY, component(AndroidComponent.COMPANY, value))
                put(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, component(AndroidComponent.DEPARTMENT))
                put(ContactsContract.CommonDataKinds.Organization.TITLE, component(AndroidComponent.TITLE))
                put(ContactsContract.CommonDataKinds.Organization.JOB_DESCRIPTION, component(AndroidComponent.ROLE))
                putNullableInt(ContactsContract.CommonDataKinds.Organization.TYPE, semanticType.organizationType())
                this@toContentValues.putCustomLabel(this, ContactsContract.CommonDataKinds.Organization.LABEL)
            }
            AndroidRowKind.PHOTO -> {
                put(
                    ContactsContract.CommonDataKinds.Photo.PHOTO,
                    validatedBinary
                        ?: throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA),
                )
            }
            AndroidRowKind.NICKNAME -> put(ContactsContract.CommonDataKinds.Nickname.NAME, value)
            AndroidRowKind.NOTE -> put(ContactsContract.CommonDataKinds.Note.NOTE, value)
            AndroidRowKind.WEBSITE -> this@toContentValues.putTypedValue(this,
                ContactsContract.CommonDataKinds.Website.URL,
                ContactsContract.CommonDataKinds.Website.TYPE,
                ContactsContract.CommonDataKinds.Website.LABEL,
                semanticType.websiteType(),
            )
            AndroidRowKind.BIRTHDAY,
            AndroidRowKind.ANNIVERSARY,
            AndroidRowKind.CUSTOM_DATE,
            -> this@toContentValues.putTypedValue(this,
                ContactsContract.CommonDataKinds.Event.START_DATE,
                ContactsContract.CommonDataKinds.Event.TYPE,
                ContactsContract.CommonDataKinds.Event.LABEL,
                when (kind) {
                    AndroidRowKind.BIRTHDAY -> ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
                    AndroidRowKind.ANNIVERSARY -> ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY
                    AndroidRowKind.CUSTOM_DATE -> if (semanticType == AndroidSemanticType.CUSTOM) {
                        ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM
                    } else {
                        ContactsContract.CommonDataKinds.Event.TYPE_OTHER
                    }
                    else -> error("Not an event row")
                },
            )
            AndroidRowKind.RELATIONSHIP -> this@toContentValues.putTypedValue(this,
                ContactsContract.CommonDataKinds.Relation.NAME,
                ContactsContract.CommonDataKinds.Relation.TYPE,
                ContactsContract.CommonDataKinds.Relation.LABEL,
                semanticType.relationType(),
            )
        }
    }

    private fun AndroidContactRow.putTypedValue(
        target: ContentValues,
        valueColumn: String,
        typeColumn: String,
        labelColumn: String,
        type: Int?,
    ) {
        target.put(valueColumn, value)
        target.putNullableInt(typeColumn, type)
        putCustomLabel(target, labelColumn)
    }

    private fun ContentValues.putNullableInt(column: String, value: Int?) {
        if (value == null) putNull(column) else put(column, value)
    }

    private fun AndroidContactRow.putCustomLabel(target: ContentValues, column: String) {
        if (semanticType == AndroidSemanticType.CUSTOM) target.put(column, requireNotNull(customLabel))
    }

    private fun AndroidContactRow.component(component: AndroidComponent, fallback: String = ""): String? =
        components[component]?.takeIf(String::isNotEmpty) ?: fallback.takeIf(String::isNotEmpty)

    private fun AndroidContactRow.linkedCanonicalIdsEncoding(): String? = linkedCanonicalValueIds.entries
        .sortedBy { it.key.name }
        .joinToString(",") { (role, id) ->
            val encoded = id.encodeBase64Url()
            "${role.name}:${encoded.length}:$encoded"
        }
        .takeIf(String::isNotEmpty)

    private fun String.encodeBase64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

    private fun validateProjectionPayload(
        accountName: AndroidProviderAccountName,
        sourceIdentityAfterWrite: String?,
        plan: AndroidProjectionPlan,
        membershipPlan: AndroidGroupMembershipWritePlan?,
    ): ValidatedProjectionPayload {
        sourceIdentityAfterWrite?.boundedUtf8Size(MAX_ID_UTF8_BYTES)
        plan.desired.rows.forEach { it.validateStaticPayload() }

        val binaries = linkedMapOf<String, ByteArray>()
        var totalBytes = sourceIdentityAfterWrite?.utf8Size() ?: 0L
        plan.operations.forEach { operation ->
            val operationBytes = when (operation) {
                is AndroidRowOperation.Delete ->
                    operation.currentIdentity.canonicalValueId.boundedUtf8Size(MAX_ID_UTF8_BYTES).toLong()
                is AndroidRowOperation.Insert -> operation.desired.writablePayloadSize(binaries)
                is AndroidRowOperation.Update -> {
                    operation.currentIdentity.canonicalValueId.boundedUtf8Size(MAX_ID_UTF8_BYTES).toLong() +
                        operation.desired.writablePayloadSize(binaries)
                }
            }
            if (operationBytes > MAX_OPERATION_PAYLOAD_BYTES) boundedFailure()
            totalBytes += operationBytes
            if (totalBytes > MAX_BATCH_PAYLOAD_BYTES) boundedFailure()
        }
        membershipPlan?.operations.orEmpty().forEach { operation ->
            totalBytes += operation.canonicalGroupId.parcelStringUpperBound(MAX_ID_UTF8_BYTES)
            totalBytes += GROUP_MEMBERSHIP_OPERATION_BYTES
            if (totalBytes > MAX_BATCH_PAYLOAD_BYTES) boundedFailure()
        }
        membershipPlan?.assertedGroupBindings.orEmpty()
            .chunked(MAX_GROUP_BINDINGS_PER_ASSERTION)
            .forEach { assertedBindings ->
                var assertionBytes = groupBindingSelection(assertedBindings)
                    .parcelStringUpperBound(MAX_ASSERT_SELECTION_UTF8_BYTES)
                groupBindingSelectionArgs(accountName, assertedBindings).forEach { argument ->
                    assertionBytes += argument.parcelStringUpperBound(MAX_ID_UTF8_BYTES)
                }
                assertionBytes += GROUP_BINDING_FIXED_BYTES * assertedBindings.size
                if (assertionBytes > MAX_OPERATION_PAYLOAD_BYTES) boundedFailure()
                totalBytes += assertionBytes
                if (totalBytes > MAX_BATCH_PAYLOAD_BYTES) boundedFailure()
        }
        return ValidatedProjectionPayload(binaries)
    }

    private fun AndroidContactRow.validateStaticPayload() {
        identity.canonicalValueId.boundedUtf8Size(MAX_ID_UTF8_BYTES)
        if (kind != AndroidRowKind.PHOTO) {
            value.boundedUtf8Size(MAX_TEXT_UTF8_BYTES)
            customLabel?.boundedUtf8Size(MAX_TEXT_UTF8_BYTES)
            components.values.forEach { it.boundedUtf8Size(MAX_TEXT_UTF8_BYTES) }
        }
        linkedCanonicalValueIds.values.forEach { it.boundedUtf8Size(MAX_ID_UTF8_BYTES) }
        linkedCanonicalIdsEncoding()?.boundedUtf8Size(MAX_LINKED_ENCODING_UTF8_BYTES)
        if (kind == AndroidRowKind.PHOTO && binaryReference == null) malformedFailure()
        if (kind != AndroidRowKind.PHOTO && binaryReference != null) malformedFailure()
    }

    private fun AndroidContactRow.writablePayloadSize(
        binaries: MutableMap<String, ByteArray>,
    ): Long {
        validateStaticPayload()
        var bytes = identity.canonicalValueId.utf8Size()
        if (kind != AndroidRowKind.PHOTO) {
            bytes += value.utf8Size()
            bytes += customLabel?.utf8Size() ?: 0L
            components.values.forEach { bytes += it.utf8Size() }
        }
        linkedCanonicalValueIds.values.forEach { bytes += it.utf8Size() }
        bytes += linkedCanonicalIdsEncoding()?.utf8Size() ?: 0L
        if (kind == AndroidRowKind.PHOTO) {
            val reference = requireNotNull(binaryReference)
            val binary = try {
                binaryLoader.load(reference)
            } catch (_: RuntimeException) {
                malformedFailure()
            } ?: malformedFailure()
            if (binary.isEmpty()) malformedFailure()
            if (binary.size > MAX_BINARY_BYTES) boundedFailure()
            binaries[identity.canonicalValueId] = binary
            bytes += binary.size
        }
        return bytes
    }

    private fun String.boundedUtf8Size(maximum: Int): Int {
        if (length > maximum) boundedFailure()
        return toByteArray(StandardCharsets.UTF_8).size.also { size ->
            if (size > maximum) boundedFailure()
        }
    }

    private fun String.utf8Size(): Long = toByteArray(StandardCharsets.UTF_8).size.toLong()

    private fun String.parcelStringUpperBound(maximumUtf8Bytes: Int): Long {
        val utf8Bytes = boundedUtf8Size(maximumUtf8Bytes).toLong()
        val utf16Bytes = length.toLong() * Char.SIZE_BYTES
        return maxOf(utf8Bytes, utf16Bytes) + PARCEL_STRING_OVERHEAD_BYTES
    }

    private data class ValidatedProjectionPayload(
        val binariesByCanonicalValueId: Map<String, ByteArray>,
    ) {
        fun binaryFor(row: AndroidContactRow): ByteArray? = binariesByCanonicalValueId[row.identity.canonicalValueId]
    }

    private fun boundedFailure(): Nothing =
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)

    private fun malformedFailure(): Nothing =
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)

    private fun AndroidRowKind.mimeType(): String = when (this) {
        AndroidRowKind.STRUCTURED_NAME -> ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        AndroidRowKind.EMAIL -> ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        AndroidRowKind.PHONE -> ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        AndroidRowKind.POSTAL_ADDRESS -> ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE
        AndroidRowKind.ORGANIZATION -> ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
        AndroidRowKind.PHOTO -> ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        AndroidRowKind.NICKNAME -> ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
        AndroidRowKind.NOTE -> ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
        AndroidRowKind.WEBSITE -> ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE
        AndroidRowKind.BIRTHDAY, AndroidRowKind.ANNIVERSARY, AndroidRowKind.CUSTOM_DATE ->
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
        AndroidRowKind.RELATIONSHIP -> ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE
    }

    private fun AndroidSemanticType.emailType(): Int? = when (this) {
        AndroidSemanticType.HOME -> ContactsContract.CommonDataKinds.Email.TYPE_HOME
        AndroidSemanticType.WORK -> ContactsContract.CommonDataKinds.Email.TYPE_WORK
        AndroidSemanticType.MOBILE -> ContactsContract.CommonDataKinds.Email.TYPE_MOBILE
        AndroidSemanticType.CUSTOM -> ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM
        AndroidSemanticType.UNSPECIFIED -> null
        else -> ContactsContract.CommonDataKinds.Email.TYPE_OTHER
    }

    private fun AndroidSemanticType.phoneType(): Int? = when (this) {
        AndroidSemanticType.HOME -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
        AndroidSemanticType.WORK -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
        AndroidSemanticType.MOBILE -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        AndroidSemanticType.FAX_HOME -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME
        AndroidSemanticType.FAX_WORK -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
        AndroidSemanticType.OTHER_FAX -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX
        AndroidSemanticType.PAGER -> ContactsContract.CommonDataKinds.Phone.TYPE_PAGER
        AndroidSemanticType.CALLBACK -> ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK
        AndroidSemanticType.CAR -> ContactsContract.CommonDataKinds.Phone.TYPE_CAR
        AndroidSemanticType.COMPANY_MAIN -> ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN
        AndroidSemanticType.ISDN -> ContactsContract.CommonDataKinds.Phone.TYPE_ISDN
        AndroidSemanticType.MAIN -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
        AndroidSemanticType.RADIO -> ContactsContract.CommonDataKinds.Phone.TYPE_RADIO
        AndroidSemanticType.TELEX -> ContactsContract.CommonDataKinds.Phone.TYPE_TELEX
        AndroidSemanticType.TTY_TDD -> ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD
        AndroidSemanticType.WORK_MOBILE -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE
        AndroidSemanticType.WORK_PAGER -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER
        AndroidSemanticType.ASSISTANT -> ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT
        AndroidSemanticType.MMS -> ContactsContract.CommonDataKinds.Phone.TYPE_MMS
        AndroidSemanticType.CUSTOM -> ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
        AndroidSemanticType.UNSPECIFIED -> null
        else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
    }

    private fun AndroidSemanticType.postalType(): Int? = when (this) {
        AndroidSemanticType.HOME -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
        AndroidSemanticType.WORK -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK
        AndroidSemanticType.CUSTOM -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM
        AndroidSemanticType.UNSPECIFIED -> null
        else -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER
    }

    private fun AndroidSemanticType.organizationType(): Int? = when (this) {
        AndroidSemanticType.WORK -> ContactsContract.CommonDataKinds.Organization.TYPE_WORK
        AndroidSemanticType.CUSTOM -> ContactsContract.CommonDataKinds.Organization.TYPE_CUSTOM
        AndroidSemanticType.UNSPECIFIED -> null
        else -> ContactsContract.CommonDataKinds.Organization.TYPE_OTHER
    }

    private fun AndroidSemanticType.websiteType(): Int? = when (this) {
        AndroidSemanticType.HOME -> ContactsContract.CommonDataKinds.Website.TYPE_HOME
        AndroidSemanticType.WORK -> ContactsContract.CommonDataKinds.Website.TYPE_WORK
        AndroidSemanticType.BLOG -> ContactsContract.CommonDataKinds.Website.TYPE_BLOG
        AndroidSemanticType.PROFILE -> ContactsContract.CommonDataKinds.Website.TYPE_PROFILE
        AndroidSemanticType.FTP -> ContactsContract.CommonDataKinds.Website.TYPE_FTP
        AndroidSemanticType.CUSTOM -> ContactsContract.CommonDataKinds.Website.TYPE_CUSTOM
        AndroidSemanticType.UNSPECIFIED -> null
        else -> ContactsContract.CommonDataKinds.Website.TYPE_OTHER
    }

    private fun AndroidSemanticType.relationType(): Int? = when (this) {
        AndroidSemanticType.ASSISTANT -> ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT
        AndroidSemanticType.BROTHER -> ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER
        AndroidSemanticType.CHILD -> ContactsContract.CommonDataKinds.Relation.TYPE_CHILD
        AndroidSemanticType.DOMESTIC_PARTNER -> ContactsContract.CommonDataKinds.Relation.TYPE_DOMESTIC_PARTNER
        AndroidSemanticType.FATHER -> ContactsContract.CommonDataKinds.Relation.TYPE_FATHER
        AndroidSemanticType.FRIEND -> ContactsContract.CommonDataKinds.Relation.TYPE_FRIEND
        AndroidSemanticType.MANAGER -> ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER
        AndroidSemanticType.MOTHER -> ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER
        AndroidSemanticType.PARENT -> ContactsContract.CommonDataKinds.Relation.TYPE_PARENT
        AndroidSemanticType.PARTNER -> ContactsContract.CommonDataKinds.Relation.TYPE_PARTNER
        AndroidSemanticType.REFERRED_BY -> ContactsContract.CommonDataKinds.Relation.TYPE_REFERRED_BY
        AndroidSemanticType.RELATIVE -> ContactsContract.CommonDataKinds.Relation.TYPE_RELATIVE
        AndroidSemanticType.SISTER -> ContactsContract.CommonDataKinds.Relation.TYPE_SISTER
        AndroidSemanticType.SPOUSE -> ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE
        AndroidSemanticType.CUSTOM -> ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM
        AndroidSemanticType.UNSPECIFIED -> null
        else -> ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM
    }

    private fun rawContactSelection(expected: AndroidExpectedSourceIdentity): String =
            "${ContactsContract.RawContacts._ID} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.RawContacts.VERSION} = ? AND " +
            when (expected) {
                AndroidExpectedSourceIdentity.Missing -> "${ContactsContract.RawContacts.SOURCE_ID} IS NULL"
                is AndroidExpectedSourceIdentity.Present -> "${ContactsContract.RawContacts.SOURCE_ID} = ?"
            }

    private fun rawContactIdentitySelection(expected: AndroidExpectedSourceIdentity): String =
        "${ContactsContract.RawContacts._ID} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
            when (expected) {
                AndroidExpectedSourceIdentity.Missing -> "${ContactsContract.RawContacts.SOURCE_ID} IS NULL"
                is AndroidExpectedSourceIdentity.Present -> "${ContactsContract.RawContacts.SOURCE_ID} = ?"
            }

    private fun rawContactIdentitySelectionArgs(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expected: AndroidExpectedSourceIdentity,
    ): Array<String> = buildList {
        add(rawContactId.toString())
        add(accountName.value)
        add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
        if (expected is AndroidExpectedSourceIdentity.Present) add(expected.value)
    }.toTypedArray()

    private fun rawContactSelectionArgs(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expectedRawContactVersion: Long,
        expected: AndroidExpectedSourceIdentity,
    ): Array<String> = buildList {
        add(rawContactId.toString())
        add(accountName.value)
        add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
        add(expectedRawContactVersion.toString())
        if (expected is AndroidExpectedSourceIdentity.Present) add(expected.value)
    }.toTypedArray()

    private fun groupBindingSelection(bindings: List<AndroidWritableGroupBinding>): String =
        bindings.joinToString(" OR ", prefix = "(", postfix = ")") { binding ->
            "(${ContactsContract.Groups._ID} = ? AND " +
                "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.Groups.SYNC1} = ? AND " +
                "${ContactsContract.Groups.VERSION} = ? AND " +
                "${ContactsContract.Groups.DELETED} = 0 AND " +
                if (binding.sourceIdentity == null) {
                    "${ContactsContract.Groups.SOURCE_ID} IS NULL)"
                } else {
                    "${ContactsContract.Groups.SOURCE_ID} = ?)"
                }
        }

    private fun groupBindingSelectionArgs(
        accountName: AndroidProviderAccountName,
        bindings: List<AndroidWritableGroupBinding>,
    ): Array<String> = buildList {
        bindings.forEach { binding ->
            add(binding.groupRowLocator.toString())
            add(accountName.value)
            add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            add(binding.canonicalGroupId)
            add(binding.expectedVersion.toString())
            binding.sourceIdentity?.let(::add)
        }
    }.toTypedArray()

    private fun syncAdapterUri(base: android.net.Uri, accountName: AndroidProviderAccountName): android.net.Uri =
        base.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName.value)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ContakoAndroidAccountContract.ACCOUNT_TYPE)
            .build()

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private companion object {
        const val MAX_ID_LENGTH = 4_096
        const val MAX_DATA_OPERATIONS = 199
        const val MAX_BATCH_OPERATIONS = 200
        // applyBatch crosses Binder. Photo projection uses the separately journaled,
        // bounded atomic photo coordinator; this generic boundary accepts bounded inline bytes.
        const val MAX_BINARY_BYTES = 256 * 1_024
        const val MAX_TEXT_UTF8_BYTES = 16 * 1_024
        const val MAX_ID_UTF8_BYTES = 4_096
        const val MAX_LINKED_ENCODING_UTF8_BYTES = 16 * 1_024
        const val MAX_OPERATION_PAYLOAD_BYTES = 320L * 1_024
        const val MAX_BATCH_PAYLOAD_BYTES = 512L * 1_024
        const val GROUP_MEMBERSHIP_OPERATION_BYTES = 32L
        const val GROUP_BINDING_FIXED_BYTES = 96L
        const val PARCEL_STRING_OVERHEAD_BYTES = 16L
        const val MAX_ASSERT_SELECTION_UTF8_BYTES = 128 * 1_024
        // Six variables per binding in the worst case. Keep each provider statement well below
        // the oldest supported SQLite variable ceiling while preserving one atomic applyBatch.
        const val MAX_GROUP_BINDINGS_PER_ASSERTION = 64
        // See AndroidContactsProviderReader: stable public provider column names hidden by the
        // Kotlin SDK stub's protected DataColumns declaration.
        const val DATA_SYNC1 = "data_sync1"
        const val DATA_SYNC2 = "data_sync2"
        const val DATA_SYNC3 = "data_sync3"
    }
}
