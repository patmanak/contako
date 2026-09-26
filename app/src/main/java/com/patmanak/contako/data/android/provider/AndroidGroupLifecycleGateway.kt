package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class AndroidGroupLifecycleFailure {
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    MALFORMED_PROVIDER_DATA,
    BOUND_EXCEEDED,
    AMBIGUOUS_CLAIM,
    SOURCE_IDENTITY_MISMATCH,
    AUTHORIZATION_STALE,
}

/** Category-only diagnostics: group titles, identities, and provider locators stay redacted. */
internal class AndroidGroupLifecycleException(
    val category: AndroidGroupLifecycleFailure,
) : IllegalStateException("Android group lifecycle failure: ${category.name}")

internal data class AndroidOwnedGroupHandle(
    val groupRowId: Long,
    val version: Long,
    val hasSourceIdentity: Boolean,
    val deleted: Boolean,
) {
    init {
        require(groupRowId > 0)
        require(version >= 0)
    }

    override fun toString(): String =
        "AndroidOwnedGroupHandle(REDACTED, version=$version, " +
            "hasSourceIdentity=$hasSourceIdentity, deleted=$deleted)"
}

internal sealed interface AndroidEnsureGroupResult {
    val handle: AndroidOwnedGroupHandle

    data class Existing(override val handle: AndroidOwnedGroupHandle) : AndroidEnsureGroupResult
    data class Created(override val handle: AndroidOwnedGroupHandle) : AndroidEnsureGroupResult
    data class RecoveredAfterLostAcknowledgement(
        override val handle: AndroidOwnedGroupHandle,
    ) : AndroidEnsureGroupResult
}

internal sealed interface AndroidAdoptGroupResult {
    data class Adopted(val handle: AndroidOwnedGroupHandle) : AndroidAdoptGroupResult
    data class RecoveredAfterLostAcknowledgement(val handle: AndroidOwnedGroupHandle) : AndroidAdoptGroupResult
    data object Stale : AndroidAdoptGroupResult
}

internal sealed interface AndroidProjectGroupResult {
    data class Applied(val handle: AndroidOwnedGroupHandle) : AndroidProjectGroupResult
    data class RecoveredAfterLostAcknowledgement(val handle: AndroidOwnedGroupHandle) : AndroidProjectGroupResult
    data class NoChangeValidated(val handle: AndroidOwnedGroupHandle) : AndroidProjectGroupResult
    data object ReplanRequired : AndroidProjectGroupResult
}

internal sealed interface AndroidDeleteGroupResult {
    data object Deleted : AndroidDeleteGroupResult
    data object RecoveredAfterLostAcknowledgement : AndroidDeleteGroupResult
    /** Callers MUST require a matching durable delete intent before accepting absence. */
    data object AbsentRequiresDurableIntent : AndroidDeleteGroupResult
    data object Stale : AndroidDeleteGroupResult
}

internal sealed interface AndroidVerifiedGroupProviderPostState {
    val providerStateFingerprint: String

    data class Present(
        val handle: AndroidOwnedGroupHandle,
        val sourceIdentity: String?,
        override val providerStateFingerprint: String,
    ) : AndroidVerifiedGroupProviderPostState

    data class Absent(
        override val providerStateFingerprint: String,
    ) : AndroidVerifiedGroupProviderPostState
}

internal sealed interface AndroidGroupProviderStateClassification {
    data class ExactPostState(
        val state: AndroidVerifiedGroupProviderPostState,
    ) : AndroidGroupProviderStateClassification
    data object ExactPreState : AndroidGroupProviderStateClassification
    data object Conflict : AndroidGroupProviderStateClassification
}

internal data class AndroidGroupWriteContext(
    val accountId: String,
    val providerEpoch: Long,
    val expectedAccountRevision: Long,
    val expectedCanonicalGroupRevision: Long,
    val expectedGroupLedgerRevision: Long,
) {
    init {
        require(accountId.isNotBlank())
        require(providerEpoch >= 0)
        require(expectedAccountRevision >= 0)
        require(expectedCanonicalGroupRevision >= 0)
        require(expectedGroupLedgerRevision >= 0)
    }

    override fun toString(): String = "AndroidGroupWriteContext(REDACTED)"
}

internal enum class AndroidGroupProviderOperation { CREATE, ADOPT, UPDATE, ACKNOWLEDGE, DELETE }

internal data class AndroidGroupWriteAuthorization(
    val context: AndroidGroupWriteContext,
    val accountName: AndroidProviderAccountName,
    val canonicalGroupId: String,
    val operation: AndroidGroupProviderOperation,
    val expectedGroupRowId: Long?,
    val expectedProviderVersion: Long?,
    val expectedSourceIdentity: AndroidExpectedSourceIdentity,
    val sourceIdentityAfterWrite: String?,
    val expectedDeleted: Boolean,
    val desiredTitle: String?,
    val desiredVisibility: Boolean?,
) {
    init {
        require(canonicalGroupId.isNotBlank())
        require((expectedGroupRowId == null) == (expectedProviderVersion == null))
        require(expectedGroupRowId == null || expectedGroupRowId > 0)
        require(expectedProviderVersion == null || expectedProviderVersion >= 0)
        require(sourceIdentityAfterWrite == null || sourceIdentityAfterWrite.isNotBlank())
        require(desiredTitle == null || desiredTitle.isNotBlank())
    }

    override fun toString(): String = "AndroidGroupWriteAuthorization(REDACTED, operation=$operation)"
}

internal fun interface AndroidGroupWriteAuthorizer {
    suspend fun isCurrent(authorization: AndroidGroupWriteAuthorization): Boolean
}

/**
 * Exact-account lifecycle boundary for editable Android `Groups` rows.
 *
 * `SYNC1` carries the stable canonical group identity, `SOURCE_ID` carries the remote label
 * identity after adoption, and `_ID` remains a replaceable locator. Every mutation is guarded by
 * account, identity, version, source, and tombstone state. Callers still MUST validate the handle
 * against the durable Room group ledger before canonical ingestion or provider mutation.
 */
internal class AndroidGroupLifecycleGateway(
    private val contentResolver: ContentResolver,
    private val afterInsertCommitted: () -> Unit = {},
    private val afterUpdateCommitted: () -> Unit = {},
    private val afterDeleteCommitted: () -> Unit = {},
    private val writeAuthorizer: AndroidGroupWriteAuthorizer = AndroidGroupWriteAuthorizer { false },
) {
    suspend fun ensureOwnedGroup(
        context: AndroidGroupWriteContext,
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        sourceIdentity: String?,
        title: String,
        isVisible: Boolean,
    ): AndroidEnsureGroupResult = CREATION_MUTEX.withLock {
        validateIdentityInputs(accountName, canonicalGroupId, sourceIdentity)
        validateTitle(title)
        findByClaim(accountName, canonicalGroupId, includeDeleted = true)?.let { current ->
            validateSourceIdentity(current.sourceIdentity, sourceIdentity)
            if (current.deleted) throw failure(AndroidGroupLifecycleFailure.AUTHORIZATION_STALE)
            return@withLock AndroidEnsureGroupResult.Existing(current.toHandle())
        }
        sourceIdentity?.let { remoteIdentity ->
            if (findBySourceIdentity(accountName, remoteIdentity, includeDeleted = true) != null) {
                throw failure(AndroidGroupLifecycleFailure.SOURCE_IDENTITY_MISMATCH)
            }
        }
        if (!writeAuthorizer.isCurrent(
                authorization(
                    context = context,
                    accountName = accountName,
                    canonicalGroupId = canonicalGroupId,
                    operation = AndroidGroupProviderOperation.CREATE,
                    expected = null,
                    expectedSourceIdentity = sourceIdentity.toExpectedSource(),
                    sourceIdentityAfterWrite = sourceIdentity,
                    expectedDeleted = false,
                    title = title,
                    isVisible = isVisible,
                ),
            )
        ) {
            throw failure(AndroidGroupLifecycleFailure.AUTHORIZATION_STALE)
        }
        val values = desiredValues(
            accountName = accountName,
            canonicalGroupId = canonicalGroupId,
            sourceIdentity = sourceIdentity,
            title = title,
            isVisible = isVisible,
            includeAccount = true,
        )
        val insertedUri = try {
            val uri = syncAdapterUri(ContactsContract.Groups.CONTENT_URI, accountName)
            val absenceSelection = buildString {
                append("${ContactsContract.Groups.ACCOUNT_NAME} = ? AND ")
                append("${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND (")
                append("${ContactsContract.Groups.SYNC1} = ?")
                if (sourceIdentity != null) append(" OR ${ContactsContract.Groups.SOURCE_ID} = ?")
                append(')')
            }
            val absenceArgs = buildList {
                add(accountName.value)
                add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
                add(canonicalGroupId)
                sourceIdentity?.let(::add)
            }.toTypedArray()
            val results = contentResolver.applyBatch(
                ContactsContract.AUTHORITY,
                arrayListOf(
                    ContentProviderOperation.newAssertQuery(uri)
                        .withSelection(absenceSelection, absenceArgs)
                        .withExpectedCount(0)
                        .build(),
                    ContentProviderOperation.newInsert(uri)
                        .withValues(values)
                        .build(),
                ),
            )
            val committedUri = results.getOrNull(1)?.uri
            afterInsertCommitted()
            committedUri
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            throw failure(AndroidGroupLifecycleFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            return@withLock recoverInsert(accountName, canonicalGroupId, sourceIdentity, title, isVisible)
        }
        val current = findByClaim(accountName, canonicalGroupId, includeDeleted = false)
            ?: throw failure(AndroidGroupLifecycleFailure.PROVIDER_UNAVAILABLE)
        validateSourceIdentity(current.sourceIdentity, sourceIdentity)
        if (!current.matchesDesired(title, isVisible, sourceIdentity)) {
            throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        val returnedId = insertedUri?.let(::safeContentId)
            ?: return@withLock AndroidEnsureGroupResult.RecoveredAfterLostAcknowledgement(current.toHandle())
        if (returnedId != current.groupRowId) throw failure(AndroidGroupLifecycleFailure.AMBIGUOUS_CLAIM)
        AndroidEnsureGroupResult.Created(current.toHandle())
    }

    suspend fun adoptUnclaimedGroup(
        context: AndroidGroupWriteContext,
        accountName: AndroidProviderAccountName,
        groupRowId: Long,
        expectedVersion: Long,
        canonicalGroupId: String,
    ): AndroidAdoptGroupResult = CREATION_MUTEX.withLock {
        validateIdentityInputs(accountName, canonicalGroupId, sourceIdentity = null)
        require(groupRowId > 0)
        require(expectedVersion >= 0)
        if (findByClaim(accountName, canonicalGroupId, includeDeleted = true) != null) {
            throw failure(AndroidGroupLifecycleFailure.AMBIGUOUS_CLAIM)
        }
        val current = findByLocator(accountName, groupRowId, includeDeleted = false)
            ?: return@withLock AndroidAdoptGroupResult.Stale
        if (current.version != expectedVersion || current.canonicalGroupId != null || current.sourceIdentity != null) {
            return@withLock AndroidAdoptGroupResult.Stale
        }
        if (!writeAuthorizer.isCurrent(
                authorization(
                    context = context,
                    accountName = accountName,
                    canonicalGroupId = canonicalGroupId,
                    operation = AndroidGroupProviderOperation.ADOPT,
                    expected = current,
                    expectedSourceIdentity = AndroidExpectedSourceIdentity.Missing,
                    sourceIdentityAfterWrite = null,
                    expectedDeleted = false,
                    title = current.title,
                    isVisible = current.visible,
                ),
            )
        ) {
            return@withLock AndroidAdoptGroupResult.Stale
        }
        val updated = try {
            contentResolver.update(
                syncAdapterUri(ContactsContract.Groups.CONTENT_URI, accountName),
                ContentValues().apply {
                    put(ContactsContract.Groups.SYNC1, canonicalGroupId)
                    put(ContactsContract.Groups.DIRTY, 0)
                    put(ContactsContract.Groups.GROUP_IS_READ_ONLY, 0)
                    put(ContactsContract.Groups.SHOULD_SYNC, 1)
                },
                unclaimedSelection(),
                arrayOf(
                    groupRowId.toString(),
                    accountName.value,
                    ContakoAndroidAccountContract.ACCOUNT_TYPE,
                    expectedVersion.toString(),
                ),
            ).also { count -> if (count == 1) afterUpdateCommitted() }
        } catch (_: SecurityException) {
            throw failure(AndroidGroupLifecycleFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            val recovered = findByClaim(accountName, canonicalGroupId, includeDeleted = false)
            if (recovered?.groupRowId == groupRowId && recovered.sourceIdentity == null) {
                if (recovered.dirty || recovered.readOnly || !recovered.shouldSync) {
                    throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
                }
                return@withLock AndroidAdoptGroupResult.RecoveredAfterLostAcknowledgement(recovered.toHandle())
            }
            throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        when (updated) {
            0 -> AndroidAdoptGroupResult.Stale
            1 -> {
                val adopted = findByClaim(accountName, canonicalGroupId, includeDeleted = false)
                    ?: throw failure(AndroidGroupLifecycleFailure.PROVIDER_UNAVAILABLE)
                if (adopted.groupRowId != groupRowId || adopted.sourceIdentity != null) {
                    throw failure(AndroidGroupLifecycleFailure.AMBIGUOUS_CLAIM)
                }
                if (adopted.dirty || adopted.readOnly || !adopted.shouldSync) {
                    throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
                }
                AndroidAdoptGroupResult.Adopted(adopted.toHandle())
            }
            else -> throw failure(AndroidGroupLifecycleFailure.AMBIGUOUS_CLAIM)
        }
    }

    suspend fun applyProjection(
        context: AndroidGroupWriteContext,
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        groupRowId: Long,
        expectedVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
        sourceIdentityAfterWrite: String?,
        title: String,
        isVisible: Boolean,
    ): AndroidProjectGroupResult {
        validateIdentityInputs(
            accountName,
            canonicalGroupId,
            (expectedSourceIdentity as? AndroidExpectedSourceIdentity.Present)?.value,
        )
        validateTitle(title)
        require(groupRowId > 0)
        require(expectedVersion >= 0)
        require(sourceIdentityAfterWrite == null || sourceIdentityAfterWrite.isNotBlank())
        sourceIdentityAfterWrite?.requireBoundedUtf8(MAX_ID_UTF8_BYTES)
        if (expectedSourceIdentity is AndroidExpectedSourceIdentity.Present &&
            sourceIdentityAfterWrite != null &&
            sourceIdentityAfterWrite != expectedSourceIdentity.value
        ) {
            throw failure(AndroidGroupLifecycleFailure.SOURCE_IDENTITY_MISMATCH)
        }
        val current = findByClaim(accountName, canonicalGroupId, includeDeleted = false)
            ?: return AndroidProjectGroupResult.ReplanRequired
        if (current.groupRowId != groupRowId || current.version != expectedVersion ||
            !current.matches(expectedSourceIdentity)
        ) {
            return AndroidProjectGroupResult.ReplanRequired
        }
        val desiredSource = sourceIdentityAfterWrite ?: current.sourceIdentity
        if (desiredSource != null && desiredSource != current.sourceIdentity) {
            val conflicting = findBySourceIdentity(accountName, desiredSource, includeDeleted = true)
            if (conflicting != null && conflicting.groupRowId != current.groupRowId) {
                throw failure(AndroidGroupLifecycleFailure.SOURCE_IDENTITY_MISMATCH)
            }
        }
        if (!writeAuthorizer.isCurrent(
                authorization(
                    context = context,
                    accountName = accountName,
                    canonicalGroupId = canonicalGroupId,
                    operation = AndroidGroupProviderOperation.UPDATE,
                    expected = current,
                    expectedSourceIdentity = expectedSourceIdentity,
                    sourceIdentityAfterWrite = desiredSource,
                    expectedDeleted = false,
                    title = title,
                    isVisible = isVisible,
                ),
            )
        ) {
            return AndroidProjectGroupResult.ReplanRequired
        }
        if (current.matchesDesired(title, isVisible, desiredSource)) {
            return AndroidProjectGroupResult.NoChangeValidated(current.toHandle())
        }
        val updated = try {
            contentResolver.update(
                syncAdapterUri(ContactsContract.Groups.CONTENT_URI, accountName),
                desiredValues(
                    accountName,
                    canonicalGroupId,
                    desiredSource,
                    title,
                    isVisible,
                    includeAccount = false,
                ),
                claimedSelection(expectedSourceIdentity, expectedDeleted = false),
                claimedSelectionArgs(
                    accountName,
                    canonicalGroupId,
                    groupRowId,
                    expectedVersion,
                    expectedSourceIdentity,
                ),
            ).also { count -> if (count == 1) afterUpdateCommitted() }
        } catch (_: SecurityException) {
            throw failure(AndroidGroupLifecycleFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            val recovered = findByClaim(accountName, canonicalGroupId, includeDeleted = false)
            if (recovered?.groupRowId == groupRowId && recovered.matchesDesired(title, isVisible, desiredSource)) {
                return AndroidProjectGroupResult.RecoveredAfterLostAcknowledgement(recovered.toHandle())
            }
            throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        return when (updated) {
            0 -> AndroidProjectGroupResult.ReplanRequired
            1 -> {
                val projected = findByClaim(accountName, canonicalGroupId, includeDeleted = false)
                    ?: throw failure(AndroidGroupLifecycleFailure.PROVIDER_UNAVAILABLE)
                if (projected.groupRowId != groupRowId || !projected.matchesDesired(title, isVisible, desiredSource)) {
                    throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
                }
                AndroidProjectGroupResult.Applied(projected.toHandle())
            }
            else -> throw failure(AndroidGroupLifecycleFailure.AMBIGUOUS_CLAIM)
        }
    }

    suspend fun acknowledgeObservation(
        context: AndroidGroupWriteContext,
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        groupRowId: Long,
        expectedVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
    ): AndroidProviderAcknowledgementResult {
        validateIdentityInputs(
            accountName,
            canonicalGroupId,
            (expectedSourceIdentity as? AndroidExpectedSourceIdentity.Present)?.value,
        )
        val current = findByClaim(accountName, canonicalGroupId, includeDeleted = false)
            ?: return AndroidProviderAcknowledgementResult.Stale
        if (current.groupRowId != groupRowId || current.version != expectedVersion ||
            !current.matches(expectedSourceIdentity)
        ) {
            return AndroidProviderAcknowledgementResult.Stale
        }
        if (!writeAuthorizer.isCurrent(
                authorization(
                    context = context,
                    accountName = accountName,
                    canonicalGroupId = canonicalGroupId,
                    operation = AndroidGroupProviderOperation.ACKNOWLEDGE,
                    expected = current,
                    expectedSourceIdentity = expectedSourceIdentity,
                    sourceIdentityAfterWrite = current.sourceIdentity,
                    expectedDeleted = false,
                    title = current.title,
                    isVisible = current.visible,
                ),
            )
        ) {
            return AndroidProviderAcknowledgementResult.Stale
        }
        if (!current.dirty) return AndroidProviderAcknowledgementResult.Acknowledged
        val updated = try {
            contentResolver.update(
                syncAdapterUri(ContactsContract.Groups.CONTENT_URI, accountName),
                ContentValues().apply { put(ContactsContract.Groups.DIRTY, 0) },
                claimedSelection(expectedSourceIdentity, expectedDeleted = false),
                claimedSelectionArgs(
                    accountName,
                    canonicalGroupId,
                    groupRowId,
                    expectedVersion,
                    expectedSourceIdentity,
                ),
            )
        } catch (_: SecurityException) {
            throw failure(AndroidGroupLifecycleFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        return when (updated) {
            0 -> AndroidProviderAcknowledgementResult.Stale
            1 -> AndroidProviderAcknowledgementResult.Acknowledged
            else -> throw failure(AndroidGroupLifecycleFailure.AMBIGUOUS_CLAIM)
        }
    }

    suspend fun deleteOwnedGroup(
        context: AndroidGroupWriteContext,
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        groupRowId: Long,
        expectedVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
        expectedDeleted: Boolean,
    ): AndroidDeleteGroupResult {
        validateIdentityInputs(
            accountName,
            canonicalGroupId,
            (expectedSourceIdentity as? AndroidExpectedSourceIdentity.Present)?.value,
        )
        require(groupRowId > 0)
        require(expectedVersion >= 0)
        val current = findByClaim(accountName, canonicalGroupId, includeDeleted = true)
            ?: return AndroidDeleteGroupResult.AbsentRequiresDurableIntent
        if (current.groupRowId != groupRowId || current.version != expectedVersion ||
            current.deleted != expectedDeleted || !current.matches(expectedSourceIdentity)
        ) {
            return AndroidDeleteGroupResult.Stale
        }
        if (!writeAuthorizer.isCurrent(
                authorization(
                    context = context,
                    accountName = accountName,
                    canonicalGroupId = canonicalGroupId,
                    operation = AndroidGroupProviderOperation.DELETE,
                    expected = current,
                    expectedSourceIdentity = expectedSourceIdentity,
                    sourceIdentityAfterWrite = current.sourceIdentity,
                    expectedDeleted = expectedDeleted,
                    title = null,
                    isVisible = null,
                ),
            )
        ) {
            return AndroidDeleteGroupResult.Stale
        }
        var deleteCommitted = false
        val deleted = try {
            contentResolver.delete(
                syncAdapterUri(ContactsContract.Groups.CONTENT_URI, accountName),
                claimedSelection(expectedSourceIdentity, expectedDeleted),
                claimedSelectionArgs(
                    accountName,
                    canonicalGroupId,
                    groupRowId,
                    expectedVersion,
                    expectedSourceIdentity,
                ),
            ).also { count ->
                if (count == 1) {
                    deleteCommitted = true
                    afterDeleteCommitted()
                }
            }
        } catch (_: SecurityException) {
            throw failure(AndroidGroupLifecycleFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            if (deleteCommitted && findByClaim(accountName, canonicalGroupId, includeDeleted = true) == null) {
                return AndroidDeleteGroupResult.RecoveredAfterLostAcknowledgement
            }
            throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        return when (deleted) {
            0 -> if (findByClaim(accountName, canonicalGroupId, includeDeleted = true) == null) {
                AndroidDeleteGroupResult.AbsentRequiresDurableIntent
            } else {
                AndroidDeleteGroupResult.Stale
            }
            1 -> AndroidDeleteGroupResult.Deleted
            else -> throw failure(AndroidGroupLifecycleFailure.AMBIGUOUS_CLAIM)
        }
    }

    /**
     * Re-reads ContactsProvider and classifies the exact durable command boundary. Only an
     * [AndroidVerifiedGroupProviderPostState] returned here may be journaled as COMMITTED.
     */
    fun classifyProviderState(
        authorization: AndroidGroupWriteAuthorization,
    ): AndroidGroupProviderStateClassification {
        validateIdentityInputs(
            authorization.accountName,
            authorization.canonicalGroupId,
            (authorization.expectedSourceIdentity as? AndroidExpectedSourceIdentity.Present)?.value,
        )
        if (authorization.operation == AndroidGroupProviderOperation.DELETE) {
            val claimed = findByClaim(
                authorization.accountName,
                authorization.canonicalGroupId,
                includeDeleted = true,
            )
            if (claimed == null) {
                val locator = authorization.expectedGroupRowId?.let {
                    findByLocator(authorization.accountName, it, includeDeleted = true)
                }
                return if (locator == null) {
                    AndroidGroupProviderStateClassification.ExactPostState(
                        AndroidVerifiedGroupProviderPostState.Absent(
                            absentStateFingerprint(authorization),
                        ),
                    )
                } else {
                    AndroidGroupProviderStateClassification.Conflict
                }
            }
            return if (claimed.matchesExactPreState(authorization)) {
                AndroidGroupProviderStateClassification.ExactPreState
            } else {
                AndroidGroupProviderStateClassification.Conflict
            }
        }

        val claimed = findByClaim(
            authorization.accountName,
            authorization.canonicalGroupId,
            includeDeleted = true,
        )
        if (claimed == null) {
            if (authorization.operation == AndroidGroupProviderOperation.CREATE) {
                val sourceConflict = authorization.sourceIdentityAfterWrite?.let {
                    findBySourceIdentity(authorization.accountName, it, includeDeleted = true)
                }
                return if (sourceConflict == null) {
                    AndroidGroupProviderStateClassification.ExactPreState
                } else {
                    AndroidGroupProviderStateClassification.Conflict
                }
            }
            if (authorization.operation == AndroidGroupProviderOperation.ADOPT) {
                val locator = authorization.expectedGroupRowId?.let {
                    findByLocator(authorization.accountName, it, includeDeleted = false)
                }
                return if (locator != null && locator.canonicalGroupId == null &&
                    locator.sourceIdentity == null && locator.version == authorization.expectedProviderVersion
                ) {
                    AndroidGroupProviderStateClassification.ExactPreState
                } else {
                    AndroidGroupProviderStateClassification.Conflict
                }
            }
            return AndroidGroupProviderStateClassification.Conflict
        }
        if (claimed.deleted ||
            (authorization.expectedGroupRowId != null &&
                claimed.groupRowId != authorization.expectedGroupRowId)
        ) {
            return AndroidGroupProviderStateClassification.Conflict
        }
        val desiredTitle = requireNotNull(authorization.desiredTitle)
        val desiredVisibility = requireNotNull(authorization.desiredVisibility)
        if (claimed.matchesDesired(
                desiredTitle,
                desiredVisibility,
                authorization.sourceIdentityAfterWrite,
            )
        ) {
            return AndroidGroupProviderStateClassification.ExactPostState(
                AndroidVerifiedGroupProviderPostState.Present(
                    handle = claimed.toHandle(),
                    sourceIdentity = claimed.sourceIdentity,
                    providerStateFingerprint = claimed.stateFingerprint(),
                ),
            )
        }
        return if (claimed.matchesExactPreState(authorization)) {
            AndroidGroupProviderStateClassification.ExactPreState
        } else {
            AndroidGroupProviderStateClassification.Conflict
        }
    }

    private fun recoverInsert(
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        sourceIdentity: String?,
        title: String,
        isVisible: Boolean,
    ): AndroidEnsureGroupResult {
        val recovered = findByClaim(accountName, canonicalGroupId, includeDeleted = false)
            ?: throw failure(AndroidGroupLifecycleFailure.PROVIDER_UNAVAILABLE)
        validateSourceIdentity(recovered.sourceIdentity, sourceIdentity)
        if (!recovered.matchesDesired(title, isVisible, sourceIdentity)) {
            throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        return AndroidEnsureGroupResult.RecoveredAfterLostAcknowledgement(recovered.toHandle())
    }

    private fun findByClaim(
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        includeDeleted: Boolean,
    ): GroupCandidate? = queryCandidates(
        accountName = accountName,
        selection = "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.Groups.SYNC1} = ?" +
            if (includeDeleted) "" else " AND ${ContactsContract.Groups.DELETED} = 0",
        selectionArgs = arrayOf(
            accountName.value,
            ContakoAndroidAccountContract.ACCOUNT_TYPE,
            canonicalGroupId,
        ),
    )

    private fun findByLocator(
        accountName: AndroidProviderAccountName,
        groupRowId: Long,
        includeDeleted: Boolean,
    ): GroupCandidate? = queryCandidates(
        accountName = accountName,
        selection = "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND ${ContactsContract.Groups._ID} = ?" +
            if (includeDeleted) "" else " AND ${ContactsContract.Groups.DELETED} = 0",
        selectionArgs = arrayOf(
            accountName.value,
            ContakoAndroidAccountContract.ACCOUNT_TYPE,
            groupRowId.toString(),
        ),
    )

    private fun findBySourceIdentity(
        accountName: AndroidProviderAccountName,
        sourceIdentity: String,
        includeDeleted: Boolean,
    ): GroupCandidate? = queryCandidates(
        accountName = accountName,
        selection = "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.Groups.SOURCE_ID} = ?" +
            if (includeDeleted) "" else " AND ${ContactsContract.Groups.DELETED} = 0",
        selectionArgs = arrayOf(
            accountName.value,
            ContakoAndroidAccountContract.ACCOUNT_TYPE,
            sourceIdentity,
        ),
    )

    private fun queryCandidates(
        accountName: AndroidProviderAccountName,
        selection: String,
        selectionArgs: Array<String>,
    ): GroupCandidate? {
        accountName.value.requireBoundedUtf8(MAX_ACCOUNT_NAME_UTF8_BYTES)
        val candidates: List<GroupCandidate> = try {
            contentResolver.query(
                ContactsContract.Groups.CONTENT_URI.buildUpon()
                    .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, MAX_CLAIM_MATCHES.toString())
                    .build(),
                GROUP_PROJECTION,
                selection,
                selectionArgs,
                "${ContactsContract.Groups._ID} ASC",
            )?.use { cursor ->
                buildList<GroupCandidate> {
                    while (cursor.moveToNext() && size < MAX_CLAIM_MATCHES) {
                        val candidate = GroupCandidate(
                            groupRowId = cursor.getLong(0),
                            canonicalGroupId = cursor.optionalIdentity(1),
                            sourceIdentity = cursor.optionalIdentity(2),
                            title = cursor.boundedText(3, MAX_TITLE_UTF8_BYTES),
                            dirty = cursor.getInt(4) != 0,
                            deleted = cursor.getInt(5) != 0,
                            visible = cursor.getInt(6) != 0,
                            shouldSync = cursor.getInt(7) != 0,
                            readOnly = cursor.getInt(8) != 0,
                            version = cursor.getLong(9),
                        )
                        if (candidate.groupRowId <= 0 || candidate.version < 0) {
                            throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
                        }
                        add(candidate)
                    }
                }
            } ?: throw failure(AndroidGroupLifecycleFailure.PROVIDER_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw failure(AndroidGroupLifecycleFailure.PERMISSION_DENIED)
        } catch (error: AndroidGroupLifecycleException) {
            throw error
        } catch (_: RuntimeException) {
            throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        if (candidates.size > 1) throw failure(AndroidGroupLifecycleFailure.AMBIGUOUS_CLAIM)
        return candidates.singleOrNull()
    }

    private fun android.database.Cursor.optionalIdentity(index: Int): String? {
        if (isNull(index)) return null
        val value = boundedText(index, MAX_ID_UTF8_BYTES)
        if (value.isBlank()) throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        return value
    }

    private fun android.database.Cursor.boundedText(index: Int, maximumBytes: Int): String {
        if (isNull(index)) return ""
        return getString(index).also { it.requireBoundedUtf8(maximumBytes) }
    }

    private fun desiredValues(
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        sourceIdentity: String?,
        title: String,
        isVisible: Boolean,
        includeAccount: Boolean,
    ) = ContentValues().apply {
        if (includeAccount) {
            put(ContactsContract.Groups.ACCOUNT_NAME, accountName.value)
            put(ContactsContract.Groups.ACCOUNT_TYPE, ContakoAndroidAccountContract.ACCOUNT_TYPE)
            put(ContactsContract.Groups.SYNC1, canonicalGroupId)
        }
        if (sourceIdentity == null) putNull(ContactsContract.Groups.SOURCE_ID)
        else put(ContactsContract.Groups.SOURCE_ID, sourceIdentity)
        put(ContactsContract.Groups.TITLE, title)
        put(ContactsContract.Groups.GROUP_VISIBLE, if (isVisible) 1 else 0)
        put(ContactsContract.Groups.SHOULD_SYNC, 1)
        put(ContactsContract.Groups.GROUP_IS_READ_ONLY, 0)
        put(ContactsContract.Groups.DIRTY, 0)
    }

    private fun unclaimedSelection(): String =
        "${ContactsContract.Groups._ID} = ? AND ${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND ${ContactsContract.Groups.VERSION} = ? AND " +
            "${ContactsContract.Groups.SYNC1} IS NULL AND ${ContactsContract.Groups.SOURCE_ID} IS NULL AND " +
            "${ContactsContract.Groups.DELETED} = 0"

    private fun claimedSelection(
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
        expectedDeleted: Boolean,
    ): String =
        "${ContactsContract.Groups._ID} = ? AND ${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND ${ContactsContract.Groups.VERSION} = ? AND " +
            "${ContactsContract.Groups.SYNC1} = ? AND " + when (expectedSourceIdentity) {
            AndroidExpectedSourceIdentity.Missing -> "${ContactsContract.Groups.SOURCE_ID} IS NULL"
            is AndroidExpectedSourceIdentity.Present -> "${ContactsContract.Groups.SOURCE_ID} = ?"
        } + " AND ${ContactsContract.Groups.DELETED} = ${if (expectedDeleted) 1 else 0}"

    private fun claimedSelectionArgs(
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        groupRowId: Long,
        expectedVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
    ): Array<String> = buildList {
        add(groupRowId.toString())
        add(accountName.value)
        add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
        add(expectedVersion.toString())
        add(canonicalGroupId)
        if (expectedSourceIdentity is AndroidExpectedSourceIdentity.Present) {
            add(expectedSourceIdentity.value)
        }
    }.toTypedArray()

    private fun authorization(
        context: AndroidGroupWriteContext,
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        operation: AndroidGroupProviderOperation,
        expected: GroupCandidate?,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
        sourceIdentityAfterWrite: String?,
        expectedDeleted: Boolean,
        title: String?,
        isVisible: Boolean?,
    ) = AndroidGroupWriteAuthorization(
        context = context,
        accountName = accountName,
        canonicalGroupId = canonicalGroupId,
        operation = operation,
        expectedGroupRowId = expected?.groupRowId,
        expectedProviderVersion = expected?.version,
        expectedSourceIdentity = expectedSourceIdentity,
        sourceIdentityAfterWrite = sourceIdentityAfterWrite,
        expectedDeleted = expectedDeleted,
        desiredTitle = title,
        desiredVisibility = isVisible,
    )

    private fun String?.toExpectedSource(): AndroidExpectedSourceIdentity = if (this == null) {
        AndroidExpectedSourceIdentity.Missing
    } else {
        AndroidExpectedSourceIdentity.Present(this)
    }

    private fun validateIdentityInputs(
        accountName: AndroidProviderAccountName,
        canonicalGroupId: String,
        sourceIdentity: String?,
    ) {
        accountName.value.requireBoundedUtf8(MAX_ACCOUNT_NAME_UTF8_BYTES)
        if (canonicalGroupId.isBlank()) throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        canonicalGroupId.requireBoundedUtf8(MAX_ID_UTF8_BYTES)
        if (sourceIdentity != null) {
            if (sourceIdentity.isBlank()) throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
            sourceIdentity.requireBoundedUtf8(MAX_ID_UTF8_BYTES)
        }
    }

    private fun validateTitle(title: String) {
        if (title.isBlank()) throw failure(AndroidGroupLifecycleFailure.MALFORMED_PROVIDER_DATA)
        title.requireBoundedUtf8(MAX_TITLE_UTF8_BYTES)
    }

    private fun validateSourceIdentity(actual: String?, expected: String?) {
        if (actual != expected) throw failure(AndroidGroupLifecycleFailure.SOURCE_IDENTITY_MISMATCH)
    }

    private fun String.requireBoundedUtf8(maximumBytes: Int) {
        if (length > maximumBytes || toByteArray(StandardCharsets.UTF_8).size > maximumBytes) {
            throw failure(AndroidGroupLifecycleFailure.BOUND_EXCEEDED)
        }
    }

    private fun syncAdapterUri(base: Uri, accountName: AndroidProviderAccountName): Uri = base.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.Groups.ACCOUNT_NAME, accountName.value)
        .appendQueryParameter(
            ContactsContract.Groups.ACCOUNT_TYPE,
            ContakoAndroidAccountContract.ACCOUNT_TYPE,
        )
        .build()

    private fun safeContentId(uri: Uri): Long? = try {
        ContentUris.parseId(uri).takeIf { it > 0 }
    } catch (_: RuntimeException) {
        null
    }

    private data class GroupCandidate(
        val groupRowId: Long,
        val canonicalGroupId: String?,
        val sourceIdentity: String?,
        val title: String,
        val dirty: Boolean,
        val deleted: Boolean,
        val visible: Boolean,
        val shouldSync: Boolean,
        val readOnly: Boolean,
        val version: Long,
    ) {
        fun toHandle() = AndroidOwnedGroupHandle(groupRowId, version, sourceIdentity != null, deleted)

        fun matches(expected: AndroidExpectedSourceIdentity): Boolean = when (expected) {
            AndroidExpectedSourceIdentity.Missing -> sourceIdentity == null
            is AndroidExpectedSourceIdentity.Present -> sourceIdentity == expected.value
        }

        fun matchesDesired(desiredTitle: String, desiredVisible: Boolean, desiredSource: String?): Boolean =
            !deleted && !dirty && !readOnly && shouldSync && visible == desiredVisible &&
                title == desiredTitle && sourceIdentity == desiredSource

        fun matchesExactPreState(authorization: AndroidGroupWriteAuthorization): Boolean =
            groupRowId == authorization.expectedGroupRowId &&
                version == authorization.expectedProviderVersion &&
                deleted == authorization.expectedDeleted &&
                matches(authorization.expectedSourceIdentity)

        fun stateFingerprint(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(
                "contako-android-group-provider-state-v1",
                groupRowId.toString(),
                version.toString(),
                canonicalGroupId.orEmpty(),
                sourceIdentity.orEmpty(),
                title,
                if (dirty) "1" else "0",
                if (deleted) "1" else "0",
                if (visible) "1" else "0",
                if (shouldSync) "1" else "0",
                if (readOnly) "1" else "0",
            ).forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun absentStateFingerprint(authorization: AndroidGroupWriteAuthorization): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            "contako-android-group-provider-absent-v1",
            authorization.context.accountId,
            authorization.accountName.value,
            authorization.canonicalGroupId,
            authorization.context.providerEpoch.toString(),
        ).forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_CLAIM_MATCHES = 2
        const val MAX_ACCOUNT_NAME_UTF8_BYTES = 512
        const val MAX_ID_UTF8_BYTES = 4_096
        const val MAX_TITLE_UTF8_BYTES = 16 * 1_024
        val CREATION_MUTEX = Mutex()
        val GROUP_PROJECTION = arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.SYNC1,
            ContactsContract.Groups.SOURCE_ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.DIRTY,
            ContactsContract.Groups.DELETED,
            ContactsContract.Groups.GROUP_VISIBLE,
            ContactsContract.Groups.SHOULD_SYNC,
            ContactsContract.Groups.GROUP_IS_READ_ONLY,
            ContactsContract.Groups.VERSION,
        )

        fun failure(category: AndroidGroupLifecycleFailure) = AndroidGroupLifecycleException(category)
    }
}
