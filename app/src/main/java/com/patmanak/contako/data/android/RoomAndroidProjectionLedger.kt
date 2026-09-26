package com.patmanak.contako.data.android

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.local.AndroidProjectionAccountEntity
import com.patmanak.contako.data.local.AndroidProjectionBaselineEntity
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.AndroidProviderAccountMutationLocks
import com.patmanak.contako.data.local.ContakoDatabase

internal class AndroidProjectionFingerprint(val sha256Hex: String) {
    init {
        require(SHA_256_HEX.matches(sha256Hex))
    }

    override fun equals(other: Any?): Boolean =
        other is AndroidProjectionFingerprint && other.sha256Hex == sha256Hex

    override fun hashCode(): Int = sha256Hex.hashCode()
    override fun toString(): String = "AndroidProjectionFingerprint(REDACTED)"

    private companion object {
        val SHA_256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** Android row IDs are replaceable locators and never ledger identity. */
internal data class AndroidRawContactLocator(
    val providerEpoch: Long,
    val localRowHandle: Long,
) {
    init {
        require(providerEpoch >= 0)
        require(localRowHandle > 0)
    }

    override fun toString(): String = "AndroidRawContactLocator(REDACTED, providerEpoch=$providerEpoch)"
}

internal enum class AndroidProjectionWriteState { DETACHED, WRITE_PENDING, CLEAN, REPAIR_REQUIRED }
internal enum class AndroidIngestionState { NONE, BASELINED, CANONICAL_DELTA_COMMITTED }
internal enum class AndroidTombstoneState { NONE, CANONICAL_COMMITTED, REMOTE_CONVERGED }
internal enum class AndroidAdoptionState { AWAITING_REMOTE_ID, SOURCE_ID_PENDING, ADOPTED }

internal data class AndroidProjectionLedgerSnapshot(
    val canonicalContactId: String,
    val revision: Long,
    val providerEpoch: Long,
    val rawContactLocator: AndroidRawContactLocator?,
    val hasSourceIdentity: Boolean,
    val canonicalProjectionFingerprint: AndroidProjectionFingerprint?,
    val androidBaselineFingerprint: AndroidProjectionFingerprint?,
    val pendingProjectionFingerprint: AndroidProjectionFingerprint?,
    val projectionState: AndroidProjectionWriteState,
    val ingestionState: AndroidIngestionState,
    val tombstoneState: AndroidTombstoneState,
    val adoptionState: AndroidAdoptionState,
) {
    init {
        require(canonicalContactId.isNotBlank())
        require(revision >= 0)
        require(providerEpoch >= 0)
        require(rawContactLocator == null || rawContactLocator.providerEpoch == providerEpoch)
        require(projectionState != AndroidProjectionWriteState.WRITE_PENDING || pendingProjectionFingerprint != null)
        require(projectionState == AndroidProjectionWriteState.WRITE_PENDING || pendingProjectionFingerprint == null)
        require(adoptionState != AndroidAdoptionState.AWAITING_REMOTE_ID || !hasSourceIdentity)
        require(adoptionState == AndroidAdoptionState.AWAITING_REMOTE_ID || hasSourceIdentity)
        require(adoptionState != AndroidAdoptionState.ADOPTED || rawContactLocator != null)
        require(tombstoneState == AndroidTombstoneState.NONE || projectionState == AndroidProjectionWriteState.DETACHED)
    }

    override fun toString(): String =
        "AndroidProjectionLedgerSnapshot(REDACTED, revision=$revision, providerEpoch=$providerEpoch, " +
            "projectionState=$projectionState, ingestionState=$ingestionState, " +
            "tombstoneState=$tombstoneState, adoptionState=$adoptionState)"
}

internal data class AndroidProjectionAccountSnapshot(
    val revision: Long,
    val providerEpoch: Long,
) {
    init {
        require(revision >= 0)
        require(providerEpoch >= 0)
    }
}

internal sealed interface AndroidLedgerCasResult {
    data class Updated(val snapshot: AndroidProjectionLedgerSnapshot) : AndroidLedgerCasResult
    data object Stale : AndroidLedgerCasResult
}

internal enum class AndroidObservationClassification {
    NO_CHANGE,
    SELF_WRITE_RECONCILED,
    CANONICAL_DELTA_COMMITTED,
    TOMBSTONE_COMMITTED,
    TOMBSTONE_ALREADY_COMMITTED,
}

internal sealed interface AndroidObservationResult {
    data class Applied(
        val classification: AndroidObservationClassification,
        val snapshot: AndroidProjectionLedgerSnapshot,
    ) : AndroidObservationResult

    data object Stale : AndroidObservationResult
    data object StaleProviderEpoch : AndroidObservationResult
}

internal sealed interface AndroidProviderEpochResult {
    data class Advanced(
        val account: AndroidProjectionAccountSnapshot,
        val repairedEntries: Int,
    ) : AndroidProviderEpochResult

    data object Stale : AndroidProviderEpochResult
}

internal sealed interface AndroidAccountBindingResult {
    data class Bound(val account: AndroidProjectionAccountSnapshot) : AndroidAccountBindingResult
    data class AlreadyBound(val account: AndroidProjectionAccountSnapshot) : AndroidAccountBindingResult
    data object Stale : AndroidAccountBindingResult
    data object Conflict : AndroidAccountBindingResult
}

/**
 * Account-scoped Room ledger for Android projection/ingestion.
 *
 * The callback supplied to [ingestObservation] MUST commit the controlled canonical delta and its
 * outbox mutation. It runs in the same Room transaction as the baseline/tombstone transition, so
 * callback failure or process death rolls the whole transition back.
 */
internal class RoomAndroidProjectionLedger(
    private val database: ContakoDatabase,
) {
    private val dao get() = database.androidProjectionLedgerDao()

    suspend fun ensureAccount(account: AccountScope): AndroidProjectionAccountSnapshot =
        database.withTransaction {
            dao.insertAccount(AndroidProjectionAccountEntity(account.value, revision = 0, providerEpoch = 0))
            requireNotNull(dao.getAccount(account.value)).toSnapshot()
        }

    suspend fun loadAccount(account: AccountScope): AndroidProjectionAccountSnapshot? =
        dao.getAccount(account.value)?.toSnapshot()

    suspend fun bindAndroidAccountName(
        account: AccountScope,
        expectedAccountRevision: Long,
        androidAccountName: String,
    ): AndroidAccountBindingResult = AndroidProviderAccountMutationLocks.withAccountLock(account.value) {
        require(androidAccountName.isNotBlank() && androidAccountName.length <= 512)
        database.withTransaction {
            val current = dao.getAccount(account.value)
                ?: return@withTransaction AndroidAccountBindingResult.Stale
            if (current.revision != expectedAccountRevision) {
                return@withTransaction AndroidAccountBindingResult.Stale
            }
            if (current.androidAccountName == androidAccountName) {
                return@withTransaction AndroidAccountBindingResult.AlreadyBound(current.toSnapshot())
            }
            if (current.androidAccountName != null) {
                return@withTransaction AndroidAccountBindingResult.Conflict
            }
            if (dao.bindAndroidAccountName(account.value, expectedAccountRevision, androidAccountName) != 1) {
                return@withTransaction AndroidAccountBindingResult.Stale
            }
            AndroidAccountBindingResult.Bound(
                requireNotNull(dao.getAccount(account.value)).toSnapshot(),
            )
        }
    }

    suspend fun load(account: AccountScope, canonicalContactId: String): AndroidProjectionLedgerSnapshot? =
        dao.get(account.value, canonicalContactId)?.toSnapshot()

    /** Returns a fully validated provider-neutral baseline or null for a legacy/unbaselined row. */
    suspend fun loadBaseline(
        account: AccountScope,
        canonicalContactId: String,
    ): AndroidContactSnapshot? {
        val (ledger, entity) = database.withTransaction {
            val current = dao.get(account.value, canonicalContactId)
                ?: return@withTransaction null
            val baseline = dao.getBaseline(account.value, canonicalContactId)
                ?: return@withTransaction null
            current to baseline
        } ?: return null
        if (ledger.androidBaselineFingerprint != entity.fingerprint) baselineIntegrityFailure()
        val decoded = AndroidContactSnapshotBinaryCodec.decode(entity.encodedSnapshot)
        if (
            decoded.canonicalContactId != canonicalContactId ||
            CanonicalAndroidContactMapper().fingerprint(decoded).sha256Hex != entity.fingerprint
        ) {
            baselineIntegrityFailure()
        }
        return decoded
    }

    suspend fun loadBySourceIdentity(
        account: AccountScope,
        sourceIdentity: String,
    ): AndroidProjectionLedgerSnapshot? {
        require(sourceIdentity.isNotBlank() && sourceIdentity.length <= MAX_ID_LENGTH)
        return dao.getBySourceIdentity(account.value, sourceIdentity)?.toSnapshot()
    }

    suspend fun loadByRawContactLocator(
        account: AccountScope,
        locator: AndroidRawContactLocator,
    ): AndroidProjectionLedgerSnapshot? =
        dao.getByRawContactLocator(account.value, locator.providerEpoch, locator.localRowHandle)?.toSnapshot()

    suspend fun attachCanonicalContact(
        account: AccountScope,
        canonicalContactId: String,
        sourceIdentity: String? = null,
        createdRawContactLocator: AndroidRawContactLocator? = null,
    ): AndroidProjectionLedgerSnapshot = database.withTransaction {
        require(canonicalContactId.isNotBlank() && canonicalContactId.length <= MAX_ID_LENGTH)
        require(sourceIdentity == null || sourceIdentity.isNotBlank() && sourceIdentity.length <= MAX_ID_LENGTH)
        require(createdRawContactLocator == null || sourceIdentity == null)
        dao.insertAccount(AndroidProjectionAccountEntity(account.value, revision = 0, providerEpoch = 0))
        val accountState = requireNotNull(dao.getAccount(account.value))
        require(createdRawContactLocator == null || createdRawContactLocator.providerEpoch == accountState.providerEpoch)
        val entry = AndroidProjectionLedgerEntity(
            accountId = account.value,
            canonicalContactId = canonicalContactId,
            revision = 0,
            providerEpoch = accountState.providerEpoch,
            rawContactLocator = createdRawContactLocator?.localRowHandle,
            sourceIdentity = sourceIdentity,
            canonicalProjectionFingerprint = null,
            androidBaselineFingerprint = null,
            pendingProjectionFingerprint = null,
            observedAndroidFingerprint = null,
            projectionState = AndroidProjectionWriteState.DETACHED.name,
            ingestionState = AndroidIngestionState.NONE.name,
            tombstoneState = AndroidTombstoneState.NONE.name,
            adoptionState = if (sourceIdentity == null) {
                AndroidAdoptionState.AWAITING_REMOTE_ID.name
            } else {
                AndroidAdoptionState.SOURCE_ID_PENDING.name
            },
        )
        dao.insert(entry)
        val persisted = requireNotNull(dao.get(account.value, canonicalContactId))
        require(persisted.sourceIdentity == sourceIdentity) {
            "Canonical contact is already attached with a different source identity"
        }
        require(createdRawContactLocator == null || persisted.rawContactLocator == createdRawContactLocator.localRowHandle) {
            "Canonical contact is already attached to a different raw-contact locator"
        }
        persisted.toSnapshot()
    }

    suspend fun prepareProjection(
        account: AccountScope,
        canonicalContactId: String,
        expectedRevision: Long,
        fingerprint: AndroidProjectionFingerprint,
    ): AndroidLedgerCasResult = transition(account, canonicalContactId, expectedRevision) { current ->
        check(current.tombstoneState == AndroidTombstoneState.NONE.name)
        current.copy(
            canonicalProjectionFingerprint = fingerprint.sha256Hex,
            pendingProjectionFingerprint = fingerprint.sha256Hex,
            observedAndroidFingerprint = null,
            projectionState = AndroidProjectionWriteState.WRITE_PENDING.name,
        )
    }

    suspend fun attachSourceIdentity(
        account: AccountScope,
        canonicalContactId: String,
        expectedRevision: Long,
        sourceIdentity: String,
    ): AndroidLedgerCasResult {
        require(sourceIdentity.isNotBlank() && sourceIdentity.length <= MAX_ID_LENGTH)
        return transition(account, canonicalContactId, expectedRevision) { current ->
            check(current.adoptionState == AndroidAdoptionState.AWAITING_REMOTE_ID.name)
            check(current.sourceIdentity == null)
            current.copy(
                sourceIdentity = sourceIdentity,
                adoptionState = AndroidAdoptionState.SOURCE_ID_PENDING.name,
                // The provider row still has no SOURCE_ID. Keeping a previously clean ledger
                // clean makes the final projection skip this contact, leaving adoption pending
                // forever and making the next Android ingestion fail closed. The newly attached
                // remote identity invalidates any earlier projection preparation by definition.
                pendingProjectionFingerprint = null,
                projectionState = AndroidProjectionWriteState.DETACHED.name,
            )
        }
    }

    suspend fun acknowledgeAdoption(
        account: AccountScope,
        canonicalContactId: String,
        expectedRevision: Long,
        locator: AndroidRawContactLocator,
    ): AndroidLedgerCasResult = transition(account, canonicalContactId, expectedRevision) { current ->
        check(current.providerEpoch == locator.providerEpoch)
        check(current.adoptionState == AndroidAdoptionState.SOURCE_ID_PENDING.name)
        check(current.sourceIdentity != null)
        current.copy(
            rawContactLocator = locator.localRowHandle,
            adoptionState = AndroidAdoptionState.ADOPTED.name,
        )
    }

    suspend fun establishObservedBaseline(
        account: AccountScope,
        canonicalContactId: String,
        expectedRevision: Long,
        locator: AndroidRawContactLocator,
        canonicalProjectionFingerprint: AndroidProjectionFingerprint,
        observedFingerprint: AndroidProjectionFingerprint,
        observedSnapshot: AndroidContactSnapshot,
    ): AndroidLedgerCasResult {
        val baseline = observedSnapshot.toBaselineEntity(account, canonicalContactId, observedFingerprint)
        return transition(
            account,
            canonicalContactId,
            expectedRevision,
            afterUpdate = { dao.upsertBaseline(baseline) },
        ) { current ->
            check(current.providerEpoch == locator.providerEpoch)
            check(current.tombstoneState == AndroidTombstoneState.NONE.name)
            check(current.androidBaselineFingerprint == null)
            check(current.pendingProjectionFingerprint == null)
            current.copy(
                rawContactLocator = locator.localRowHandle,
                canonicalProjectionFingerprint = canonicalProjectionFingerprint.sha256Hex,
                androidBaselineFingerprint = observedFingerprint.sha256Hex,
                projectionState = if (canonicalProjectionFingerprint == observedFingerprint) {
                    AndroidProjectionWriteState.CLEAN.name
                } else {
                    AndroidProjectionWriteState.REPAIR_REQUIRED.name
                },
                ingestionState = AndroidIngestionState.BASELINED.name,
                adoptionState = if (current.sourceIdentity == null) {
                    AndroidAdoptionState.AWAITING_REMOTE_ID.name
                } else {
                    AndroidAdoptionState.ADOPTED.name
                },
            )
        }
    }

    suspend fun ingestObservation(
        account: AccountScope,
        canonicalContactId: String,
        expectedRevision: Long,
        locator: AndroidRawContactLocator,
        observedFingerprint: AndroidProjectionFingerprint?,
        deleted: Boolean,
        resultingCanonicalProjectionFingerprint: AndroidProjectionFingerprint?,
        observedSnapshot: AndroidContactSnapshot?,
        commitCanonicalAndOutbox: suspend () -> Unit,
    ): AndroidObservationResult {
        require(deleted == (observedSnapshot == null))
        require(deleted || observedFingerprint != null)
        require(!deleted || observedFingerprint == null)
        val baseline = if (deleted) {
            null
        } else {
            requireNotNull(observedSnapshot).toBaselineEntity(
                account,
                canonicalContactId,
                requireNotNull(observedFingerprint),
            )
        }
        return database.withTransaction {
            val current = dao.get(account.value, canonicalContactId)
                ?: return@withTransaction AndroidObservationResult.Stale
            if (current.revision != expectedRevision) return@withTransaction AndroidObservationResult.Stale
            val accountState = dao.getAccount(account.value)
                ?: return@withTransaction AndroidObservationResult.Stale
            if (
                locator.providerEpoch != accountState.providerEpoch ||
                current.providerEpoch != accountState.providerEpoch
            ) {
                return@withTransaction AndroidObservationResult.StaleProviderEpoch
            }

            val classification: AndroidObservationClassification
            var canonicalCommitRequired = false
            val next = when {
                deleted && current.tombstoneState != AndroidTombstoneState.NONE.name -> {
                    classification = AndroidObservationClassification.TOMBSTONE_ALREADY_COMMITTED
                    current.copy(rawContactLocator = locator.localRowHandle)
                }
                deleted -> {
                    canonicalCommitRequired = true
                    classification = AndroidObservationClassification.TOMBSTONE_COMMITTED
                    current.copy(
                        rawContactLocator = locator.localRowHandle,
                        pendingProjectionFingerprint = null,
                        observedAndroidFingerprint = null,
                        projectionState = AndroidProjectionWriteState.DETACHED.name,
                        ingestionState = AndroidIngestionState.CANONICAL_DELTA_COMMITTED.name,
                        tombstoneState = AndroidTombstoneState.CANONICAL_COMMITTED.name,
                    )
                }
                observedFingerprint!!.sha256Hex == current.pendingProjectionFingerprint -> {
                    classification = AndroidObservationClassification.SELF_WRITE_RECONCILED
                    current.copy(
                        rawContactLocator = locator.localRowHandle,
                        androidBaselineFingerprint = observedFingerprint.sha256Hex,
                        pendingProjectionFingerprint = null,
                        observedAndroidFingerprint = null,
                        projectionState = AndroidProjectionWriteState.CLEAN.name,
                        ingestionState = AndroidIngestionState.BASELINED.name,
                    )
                }
                observedFingerprint.sha256Hex == current.androidBaselineFingerprint -> {
                    classification = AndroidObservationClassification.NO_CHANGE
                    current.copy(rawContactLocator = locator.localRowHandle)
                }
                else -> {
                    val canonicalFingerprint = requireNotNull(resultingCanonicalProjectionFingerprint)
                    canonicalCommitRequired = true
                    classification = AndroidObservationClassification.CANONICAL_DELTA_COMMITTED
                    current.copy(
                        rawContactLocator = locator.localRowHandle,
                        canonicalProjectionFingerprint = canonicalFingerprint.sha256Hex,
                        androidBaselineFingerprint = observedFingerprint.sha256Hex,
                        pendingProjectionFingerprint = null,
                        observedAndroidFingerprint = null,
                        projectionState = if (canonicalFingerprint == observedFingerprint) {
                            AndroidProjectionWriteState.CLEAN.name
                        } else {
                            AndroidProjectionWriteState.REPAIR_REQUIRED.name
                        },
                        ingestionState = AndroidIngestionState.CANONICAL_DELTA_COMMITTED.name,
                    )
                }
            }
            val nextRevision = Math.incrementExact(expectedRevision)
            if (dao.compareAndSetRevision(account.value, canonicalContactId, expectedRevision) != 1) {
                return@withTransaction AndroidObservationResult.Stale
            }
            if (canonicalCommitRequired) commitCanonicalAndOutbox()
            val updated = next.copy(revision = nextRevision)
            check(dao.update(updated) == 1)
            if (deleted) {
                dao.deleteBaseline(account.value, canonicalContactId)
            } else {
                dao.upsertBaseline(requireNotNull(baseline))
            }
            AndroidObservationResult.Applied(classification, updated.toSnapshot())
        }
    }

    suspend fun markTombstoneRemoteConverged(
        account: AccountScope,
        canonicalContactId: String,
        expectedRevision: Long,
    ): AndroidLedgerCasResult = transition(account, canonicalContactId, expectedRevision) { current ->
        check(current.tombstoneState == AndroidTombstoneState.CANONICAL_COMMITTED.name)
        current.copy(tombstoneState = AndroidTombstoneState.REMOTE_CONVERGED.name)
    }

    suspend fun advanceProviderEpoch(
        account: AccountScope,
        expectedAccountRevision: Long,
    ): AndroidProviderEpochResult = AndroidProviderAccountMutationLocks.withAccountLock(account.value) {
        database.withTransaction {
        val current = dao.getAccount(account.value)
            ?: return@withTransaction AndroidProviderEpochResult.Stale
        if (current.revision != expectedAccountRevision) return@withTransaction AndroidProviderEpochResult.Stale
        val nextRevision = Math.incrementExact(current.revision)
        val nextEpoch = Math.incrementExact(current.providerEpoch)
        if (dao.advanceProviderEpoch(account.value, expectedAccountRevision) != 1) {
            return@withTransaction AndroidProviderEpochResult.Stale
        }
        dao.deleteBaselines(account.value)
        dao.deleteUnifiedObservationCommitReceipts(account.value)
        val groupDao = database.androidGroupProjectionDao()
        groupDao.deleteGroupBaselines(account.value)
        groupDao.deleteMembershipBaselines(account.value)
        groupDao.deleteMembershipCommitReceipts(account.value)
        groupDao.deleteGroupObservationCommitReceipts(account.value)
        groupDao.deleteGroupProviderWriteJournals(account.value)
        val entries = dao.getAll(account.value).map { entry ->
            val tombstone = AndroidTombstoneState.valueOf(entry.tombstoneState)
            entry.copy(
                revision = Math.incrementExact(entry.revision),
                providerEpoch = nextEpoch,
                rawContactLocator = null,
                androidBaselineFingerprint = null,
                pendingProjectionFingerprint = null,
                observedAndroidFingerprint = null,
                projectionState = if (tombstone == AndroidTombstoneState.NONE) {
                    AndroidProjectionWriteState.REPAIR_REQUIRED.name
                } else {
                    AndroidProjectionWriteState.DETACHED.name
                },
                ingestionState = if (tombstone == AndroidTombstoneState.NONE) {
                    AndroidIngestionState.NONE.name
                } else {
                    entry.ingestionState
                },
                adoptionState = if (entry.sourceIdentity == null) {
                    AndroidAdoptionState.AWAITING_REMOTE_ID.name
                } else {
                    AndroidAdoptionState.SOURCE_ID_PENDING.name
                },
            )
        }
        if (entries.isNotEmpty()) check(dao.update(entries) == entries.size)
        val groupEntries = groupDao.getAllGroups(account.value).map { entry ->
            val tombstone = AndroidTombstoneState.valueOf(entry.tombstoneState)
            entry.copy(
                revision = Math.incrementExact(entry.revision),
                providerEpoch = nextEpoch,
                groupRowLocator = null,
                providerVersion = null,
                androidBaselineFingerprint = null,
                pendingProjectionFingerprint = null,
                projectionState = if (tombstone == AndroidTombstoneState.NONE) {
                    AndroidProjectionWriteState.REPAIR_REQUIRED.name
                } else {
                    AndroidProjectionWriteState.DETACHED.name
                },
                ingestionState = if (tombstone == AndroidTombstoneState.NONE) {
                    AndroidIngestionState.NONE.name
                } else {
                    entry.ingestionState
                },
                adoptionState = if (entry.sourceIdentity == null) {
                    AndroidAdoptionState.AWAITING_REMOTE_ID.name
                } else {
                    AndroidAdoptionState.SOURCE_ID_PENDING.name
                },
            )
        }
        if (groupEntries.isNotEmpty()) check(groupDao.updateGroups(groupEntries) == groupEntries.size)
        val membershipEntries = groupDao.getAllMemberships(account.value).map { entry ->
            entry.copy(
                revision = Math.incrementExact(entry.revision),
                providerEpoch = nextEpoch,
                rawContactLocator = null,
                preferredEmailValueId = null,
                androidBaselineFingerprint = null,
                pendingProjectionFingerprint = null,
                projectionState = AndroidProjectionWriteState.REPAIR_REQUIRED.name,
                ingestionState = AndroidIngestionState.NONE.name,
            )
        }
        if (membershipEntries.isNotEmpty()) {
            check(groupDao.updateMemberships(membershipEntries) == membershipEntries.size)
        }
        AndroidProviderEpochResult.Advanced(
            AndroidProjectionAccountSnapshot(nextRevision, nextEpoch),
            entries.size + groupEntries.size + membershipEntries.size,
        )
        }
    }

    private suspend fun transition(
        account: AccountScope,
        canonicalContactId: String,
        expectedRevision: Long,
        afterUpdate: suspend (AndroidProjectionLedgerEntity) -> Unit = {},
        transform: (AndroidProjectionLedgerEntity) -> AndroidProjectionLedgerEntity,
    ): AndroidLedgerCasResult = database.withTransaction {
        val current = dao.get(account.value, canonicalContactId)
            ?: return@withTransaction AndroidLedgerCasResult.Stale
        if (current.revision != expectedRevision) return@withTransaction AndroidLedgerCasResult.Stale
        val updated = updateAfterRevisionClaim(current, expectedRevision, transform(current))
            ?: return@withTransaction AndroidLedgerCasResult.Stale
        afterUpdate(updated)
        AndroidLedgerCasResult.Updated(updated.toSnapshot())
    }

    private fun AndroidContactSnapshot.toBaselineEntity(
        account: AccountScope,
        canonicalContactId: String,
        expectedFingerprint: AndroidProjectionFingerprint,
    ): AndroidProjectionBaselineEntity {
        if (
            this.canonicalContactId != canonicalContactId ||
            CanonicalAndroidContactMapper().fingerprint(this) != expectedFingerprint
        ) {
            baselineIntegrityFailure()
        }
        return AndroidProjectionBaselineEntity(
            accountId = account.value,
            canonicalContactId = canonicalContactId,
            fingerprint = expectedFingerprint.sha256Hex,
            encodedSnapshot = AndroidContactSnapshotBinaryCodec.encode(this),
        )
    }

    private suspend fun updateAfterRevisionClaim(
        current: AndroidProjectionLedgerEntity,
        expectedRevision: Long,
        transformed: AndroidProjectionLedgerEntity,
    ): AndroidProjectionLedgerEntity? {
        val nextRevision = Math.incrementExact(expectedRevision)
        if (dao.compareAndSetRevision(current.accountId, current.canonicalContactId, expectedRevision) != 1) return null
        val updated = transformed.copy(revision = nextRevision)
        check(dao.update(updated) == 1)
        return updated
    }

    private fun AndroidProjectionAccountEntity.toSnapshot() =
        AndroidProjectionAccountSnapshot(revision, providerEpoch)

    private fun AndroidProjectionLedgerEntity.toSnapshot(): AndroidProjectionLedgerSnapshot =
        AndroidProjectionLedgerSnapshot(
            canonicalContactId = canonicalContactId,
            revision = revision,
            providerEpoch = providerEpoch,
            rawContactLocator = rawContactLocator?.let { AndroidRawContactLocator(providerEpoch, it) },
            hasSourceIdentity = sourceIdentity != null,
            canonicalProjectionFingerprint = canonicalProjectionFingerprint?.let(::AndroidProjectionFingerprint),
            androidBaselineFingerprint = androidBaselineFingerprint?.let(::AndroidProjectionFingerprint),
            pendingProjectionFingerprint = pendingProjectionFingerprint?.let(::AndroidProjectionFingerprint),
            projectionState = AndroidProjectionWriteState.valueOf(projectionState),
            ingestionState = AndroidIngestionState.valueOf(ingestionState),
            tombstoneState = AndroidTombstoneState.valueOf(tombstoneState),
            adoptionState = AndroidAdoptionState.valueOf(adoptionState),
        )

    private companion object {
        const val MAX_ID_LENGTH = 4_096

        fun baselineIntegrityFailure(): Nothing =
            throw IllegalStateException("Android projection baseline integrity failure")
    }
}
