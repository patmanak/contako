package com.patmanak.contako.data.android.provider

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotCodecException
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.mapping.AndroidLinkedValueRole
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidProviderIdentityOwnerEntity
import com.patmanak.contako.data.local.AndroidProviderRowBindingEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import java.util.UUID
import java.util.concurrent.Callable

internal data class AndroidPendingProviderBinding(
    val kind: AndroidRowKind,
    val binding: AndroidDurableValueBinding,
) {
    override fun toString(): String = "AndroidPendingProviderBinding(REDACTED, kind=$kind)"
}

internal sealed interface AndroidPendingBindingRegistration {
    data object Registered : AndroidPendingBindingRegistration
    data object Divergence : AndroidPendingBindingRegistration
}

/** Closed trust-boundary detail; never includes claims, locators or stored identities. */
internal enum class AndroidProviderIdentityFailure {
    INVALID_CLAIMS, MIXED_SCOPE_OR_DUPLICATE_LOCATOR, LEDGER_SCOPE_MISSING,
    ATTACHED_BINDING_INVALID, ATTACHED_PRIMARY_MISMATCH, ATTACHED_LINKED_MISMATCH,
    CLAIM_NOT_REGISTERED, CLAIM_ATTACHED_ELSEWHERE, PENDING_BINDING_INVALID,
    PENDING_CLAIM_MISMATCH, UNBOUND_LINKED_CLAIM, DUPLICATE_RESOLVED_IDENTITY,
    STORAGE_CONSTRAINT, TRANSACTION_DIVERGED,
}

/**
 * Room-backed trust boundary for untrusted DATA_SYNC identity claims.
 *
 * The resolver is scoped to one canonical account and one provider epoch. All rows in one call are
 * validated before a write, then owners and locator bindings are committed in one SQLite
 * transaction. Claimed identities are accepted on first observation only when the corresponding
 * pending binding was durably registered before provider projection.
 */
internal class RoomAndroidProviderIdentityResolver(
    private val database: ContakoDatabase,
    private val account: AccountScope,
    private val providerEpoch: Long,
    private val onFailure: (AndroidProviderIdentityFailure, AndroidRowKind?) -> Unit = { _, _ -> },
) : AndroidProviderIdentityResolver {
    init {
        require(providerEpoch >= 0)
    }

    private val dao get() = database.androidProviderIdentityDao()

    /** Recover reinserted rows only against a whole durable projection, never DATA_SYNC alone. */
    suspend fun recoverProjectionBindings(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        canonicalRevision: Long,
        observation: AndroidStableRawContactObservation,
        desired: AndroidContactSnapshot,
        photoCapture: AndroidDurablePhotoCapture,
        missingPhotoReference: () -> String,
        confirmObservation: () -> Boolean,
        onRejected: (AndroidBindingRecoveryDiagnostic) -> Unit = {},
    ): Boolean {
        val diagnostic = AndroidBindingRecoveryDiagnosticRecorder(onRejected)
        val recovered = try {
            database.withTransaction {
                val accountRow = database.androidProjectionLedgerDao().getAccount(account.value)
                    ?: return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.LOCAL_STATE_MISSING)
                val ledger = database.androidProjectionLedgerDao().get(account.value, canonicalContactId)
                    ?: return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.LOCAL_STATE_MISSING)
                val canonical = database.contactDao().get(account.value, canonicalContactId)?.contact
                    ?: return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.LOCAL_STATE_MISSING)
                val raw = observation.rawContact
                val mapper = CanonicalAndroidContactMapper()
                if (context.account != account || context.providerEpoch != providerEpoch ||
                    accountRow.revision != context.accountRevision || accountRow.providerEpoch != providerEpoch ||
                    accountRow.androidAccountName != context.androidAccountName ||
                    ledger.providerEpoch != providerEpoch || ledger.rawContactLocator != raw.rawContactId ||
                    ledger.tombstoneState != "NONE" ||
                    canonical.revision != canonicalRevision || canonical.isDeleted || canonical.conflictState != null ||
                    canonical.remoteContactId == null || canonical.remoteContactId != ledger.sourceIdentity ||
                    raw.dirty || raw.deleted || raw.canonicalContactIdClaim != canonicalContactId ||
                    raw.sourceIdentity != ledger.sourceIdentity || desired.canonicalContactId != canonicalContactId
                ) return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.CONTEXT_CHANGED)

                // A provider can relocate a name after a completed write too. The last completed
                // baseline remains the authority for those rows even if Proton has since refreshed
                // the canonical contact. Never substitute the newly desired state for that receipt.
                val pending = ledger.projectionState == "WRITE_PENDING"
                val expected = when {
                    pending && ledger.pendingProjectionFingerprint == mapper.fingerprint(desired).sha256Hex -> desired
                    ledger.projectionState == "CLEAN" -> {
                        val baseline = database.androidProjectionLedgerDao().getBaseline(account.value, canonicalContactId)
                            ?: return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.BASELINE_MISSING)
                        val decoded = AndroidContactSnapshotBinaryCodec.decode(baseline.encodedSnapshot)
                        if (decoded.canonicalContactId != canonicalContactId ||
                            baseline.fingerprint != ledger.androidBaselineFingerprint ||
                            mapper.fingerprint(decoded).sha256Hex != baseline.fingerprint
                        ) return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.BASELINE_INTEGRITY)
                        decoded
                    }
                    else -> return@withTransaction diagnostic.reject(
                        if (pending) AndroidBindingRecoveryReason.PENDING_FINGERPRINT_CHANGED else AndroidBindingRecoveryReason.PROJECTION_STATE,
                    )
                }
                val expectedFingerprint = mapper.fingerprint(expected)

                val accountName = AndroidProviderAccountName(context.androidAccountName)
                val route = AndroidProviderMimeRouter().route(raw.rawContactId, observation.dataRows)
                if (route.unsupportedOwnedRows.rows.isNotEmpty()) return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.UNSUPPORTED_OWNED_ROWS)
                val moves = mutableListOf<ProjectionBindingMove>()
                // This resolver is read-only. No pending attachment or fresh identity is persisted
                // until the complete decoded payload has matched the durable write fingerprint.
                val candidateResolver = AndroidProviderIdentityResolver { claims ->
                    if (claims.size > MAX_BINDINGS_PER_CONTACT)
                        return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.CLAIM_LIMIT)
                    val resolved = linkedMapOf<Long, AndroidDurableValueBinding>()
                    for (claim in claims) {
                        if (!validClaim(claim) || claim.accountName != accountName ||
                            claim.canonicalContactId != canonicalContactId || claim.rawContactId != raw.rawContactId
                        ) return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.CLAIM_SCOPE, claim.kind)
                        val id = claim.claimedCanonicalValueId
                            ?: return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.CLAIM_MISSING, claim.kind)
                        val group = dao.getBindingGroup(account.value, canonicalContactId, providerEpoch, raw.rawContactId, id)
                        val state = group.firstOrNull()?.state
                            ?: return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.BINDING_MISSING, claim.kind)
                        if (state != STATE_ATTACHED && state != STATE_PENDING)
                            return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.BINDING_STATE, claim.kind)
                        val binding = group.toDurableBinding(accountName, claim.kind, state)
                            ?: return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.BINDING_SHAPE, claim.kind)
                        if (binding.canonicalValueId != id || binding.linkedCanonicalValueIds != claim.claimedLinkedCanonicalValueIds)
                            return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.CLAIM_IDENTITY_MISMATCH, claim.kind)
                        val locators = group.map { it.dataRowLocator }.distinct()
                        if (locators.size != 1) return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.BINDING_LOCATORS, claim.kind)
                        val old = locators.single()
                        if (state == STATE_PENDING) {
                            if (!pending || old != null || dao.getAttachedBindingGroup(account.value, canonicalContactId, providerEpoch,
                                    raw.rawContactId, claim.providerRowId).isNotEmpty()
                            ) return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.PENDING_ATTACHMENT, claim.kind)
                        } else if (old != claim.providerRowId) {
                            // All non-photo rows share the same durable applyBatch receipt.
                            // Restricting this to name/email stranded a reinserted birthday.
                            // Photos require their separate stream journal; CLEAN-baseline
                            // recovery remains limited to the previously supported name row.
                            val supportedMove = permitsProjectionBindingRelocation(claim.kind, pending) &&
                                group.size == 1 + binding.linkedCanonicalValueIds.size
                            if (old == null || !supportedMove)
                                return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.UNSUPPORTED_RELOCATION, claim.kind)
                            if (observation.dataRows.any { it.dataRowId == old })
                                return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.OLD_ROW_PRESENT, claim.kind)
                            if (dao.getAttachedBindingGroup(account.value, canonicalContactId, providerEpoch, raw.rawContactId, claim.providerRowId).isNotEmpty())
                                return@AndroidProviderIdentityResolver diagnostic.rejectClaim(AndroidBindingRecoveryReason.DESTINATION_BOUND, claim.kind)
                            moves += ProjectionBindingMove(id, old, claim.providerRowId, claim.kind, group.size)
                        }
                        resolved[claim.providerRowId] = binding
                    }
                    AndroidProviderIdentityResolution.Bound(resolved)
                }
                val decoded = AndroidProviderRowCodec(candidateResolver, photoCapture, missingPhotoReference)
                    .decode(accountName, canonicalContactId, raw.rawContactId, route.contactRows.rows)
                if (moves.isEmpty()) return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.NO_RELOCATIONS)
                if (mapper.fingerprint(mapper.normalizeGeneratedName(decoded, expected)) != expectedFingerprint) {
                    var kind: AndroidRowKind? = null
                    var component: com.patmanak.contako.data.android.mapping.AndroidComponent? = null
                    var difference: com.patmanak.contako.data.sync.AndroidComponentDifference? = null
                    // This comparison uses the existing decoded snapshots and returns enums only.
                    val mismatch = runCatching {
                        com.patmanak.contako.data.sync.classifyAndroidProjectionMismatch(expected, decoded, mapper) { k, c, d ->
                            kind = k
                            component = c
                            difference = d
                        }
                    }.getOrNull()
                    return@withTransaction diagnostic.reject(
                        AndroidBindingRecoveryReason.PROJECTION_MISMATCH, kind, mismatch = mismatch, component = component, difference = difference,
                    )
                }
                if (!confirmObservation()) return@withTransaction diagnostic.reject(AndroidBindingRecoveryReason.OBSERVATION_CHANGED)
                moves.forEach { move ->
                    val updated = dao.relocateProjectionBindingGroup(
                        account.value, canonicalContactId, providerEpoch, raw.rawContactId,
                        move.id, move.kind.name, move.old, move.current,
                    )
                    if (updated != move.rows) throw IdentityBindingTransactionDiverged()
                }
                true
            }
        } catch (failure: AndroidProviderRowCodecException) {
            diagnostic.reject(AndroidBindingRecoveryReason.ROW_DECODING, codec = failure.category)
        } catch (_: AndroidContactSnapshotCodecException) {
            diagnostic.reject(AndroidBindingRecoveryReason.BASELINE_DECODING)
        } catch (_: SQLiteConstraintException) {
            diagnostic.reject(AndroidBindingRecoveryReason.STORAGE_CONSTRAINT)
        } catch (_: IdentityBindingTransactionDiverged) {
            diagnostic.reject(AndroidBindingRecoveryReason.TRANSACTION_DIVERGED)
        }
        if (!recovered) diagnostic.publishFailure()
        return recovered
    }

    fun registerPendingBindings(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        rawContactId: Long,
        bindings: List<AndroidPendingProviderBinding>,
    ): AndroidPendingBindingRegistration {
        if (!validScope(canonicalContactId, rawContactId) || bindings.size > MAX_BINDINGS_PER_CONTACT) {
            return AndroidPendingBindingRegistration.Divergence
        }
        if (bindings.any { !validBinding(it.kind, it.binding) }) {
            return AndroidPendingBindingRegistration.Divergence
        }
        val allIds = bindings.flatMap { binding ->
            listOf(binding.binding.canonicalValueId) + binding.binding.linkedCanonicalValueIds.values
        }
        if (allIds.distinct().size != allIds.size) return AndroidPendingBindingRegistration.Divergence

        return try {
            database.runInTransaction(Callable {
                if (dao.getLedgerScope(account.value, canonicalContactId, providerEpoch, rawContactId) == null) {
                    return@Callable AndroidPendingBindingRegistration.Divergence
                }
                val ownersToInsert = linkedMapOf<String, AndroidProviderIdentityOwnerEntity>()
                val rowsToInsert = mutableListOf<AndroidProviderRowBindingEntity>()
                bindings.forEach { pending ->
                    val primaryId = pending.binding.canonicalValueId
                    val expectedRows = pending.toRows(
                        accountName,
                        canonicalContactId,
                        rawContactId,
                        dataRowLocator = null,
                        state = STATE_PENDING,
                    )
                    val existingGroup = dao.getBindingGroup(
                        account.value,
                        canonicalContactId,
                        providerEpoch,
                        rawContactId,
                        primaryId,
                    )
                    if (existingGroup.isNotEmpty()) {
                        if (!existingGroup.matches(expectedRows, allowAttached = true)) {
                            return@Callable AndroidPendingBindingRegistration.Divergence
                        }
                    } else {
                        expectedRows.forEach { row ->
                            if (dao.getBindingByCanonicalValueId(
                                    account.value,
                                    canonicalContactId,
                                    providerEpoch,
                                    row.canonicalValueId,
                                ) != null
                            ) {
                                return@Callable AndroidPendingBindingRegistration.Divergence
                            }
                            if (dao.getOwner(account.value, canonicalContactId, row.canonicalValueId) == null) {
                                ownersToInsert.putIfAbsent(
                                    row.canonicalValueId,
                                    AndroidProviderIdentityOwnerEntity(
                                        account.value,
                                        canonicalContactId,
                                        row.canonicalValueId,
                                    ),
                                )
                            }
                        }
                        rowsToInsert += expectedRows
                    }
                }
                if (ownersToInsert.isNotEmpty()) dao.insertOwners(ownersToInsert.values.toList())
                if (rowsToInsert.isNotEmpty()) dao.insertBindings(rowsToInsert)
                AndroidPendingBindingRegistration.Registered
            })
        } catch (_: SQLiteConstraintException) {
            AndroidPendingBindingRegistration.Divergence
        } catch (_: IdentityBindingTransactionDiverged) {
            AndroidPendingBindingRegistration.Divergence
        }
    }

    override fun resolve(claims: List<AndroidProviderIdentityClaim>): AndroidProviderIdentityResolution {
        if (claims.isEmpty()) return AndroidProviderIdentityResolution.Bound(emptyMap())
        if (claims.size > MAX_BINDINGS_PER_CONTACT || claims.any { !validClaim(it) }) {
            return divergence(AndroidProviderIdentityFailure.INVALID_CLAIMS)
        }
        val first = claims.first()
        if (claims.any {
                it.accountName != first.accountName ||
                    it.canonicalContactId != first.canonicalContactId ||
                    it.rawContactId != first.rawContactId
            } || claims.map { it.providerRowId }.distinct().size != claims.size
        ) {
            return divergence(AndroidProviderIdentityFailure.MIXED_SCOPE_OR_DUPLICATE_LOCATOR)
        }

        return try {
            database.runInTransaction(Callable {
                if (dao.getLedgerScope(
                        account.value,
                        first.canonicalContactId,
                        providerEpoch,
                        first.rawContactId,
                    ) == null
                ) {
                    return@Callable divergence(AndroidProviderIdentityFailure.LEDGER_SCOPE_MISSING)
                }

                val actions = mutableListOf<ResolutionAction>()
                val resolved = linkedMapOf<Long, AndroidDurableValueBinding>()
                val resolvedIds = mutableSetOf<String>()
                claims.sortedBy { it.providerRowId }.forEach { claim ->
                    val attached = dao.getAttachedBindingGroup(
                        account.value,
                        claim.canonicalContactId,
                        providerEpoch,
                        claim.rawContactId,
                        claim.providerRowId,
                    )
                    val claimedPrimary = claim.claimedCanonicalValueId?.takeIf(String::isNotBlank)
                    val binding = when {
                        attached.isNotEmpty() -> {
                            val durable = attached.toDurableBinding(
                                claim.accountName,
                                claim.kind,
                                expectedState = STATE_ATTACHED,
                            ) ?: return@Callable divergence(AndroidProviderIdentityFailure.ATTACHED_BINDING_INVALID, claim.kind)
                            if (claimedPrimary != null && claimedPrimary != durable.canonicalValueId) {
                                return@Callable divergence(AndroidProviderIdentityFailure.ATTACHED_PRIMARY_MISMATCH, claim.kind)
                            }
                            if (claim.claimedLinkedCanonicalValueIds != durable.linkedCanonicalValueIds) {
                                return@Callable divergence(AndroidProviderIdentityFailure.ATTACHED_LINKED_MISMATCH, claim.kind)
                            }
                            durable
                        }
                        claimedPrimary != null -> {
                            val pending = dao.getBindingGroup(
                                account.value,
                                claim.canonicalContactId,
                                providerEpoch,
                                claim.rawContactId,
                                claimedPrimary,
                            )
                            val durable = pending.toDurableBinding(
                                claim.accountName,
                                claim.kind,
                                expectedState = STATE_PENDING,
                            ) ?: return@Callable divergence(
                                when {
                                    pending.isEmpty() -> AndroidProviderIdentityFailure.CLAIM_NOT_REGISTERED
                                    pending.any { it.state == STATE_ATTACHED } -> AndroidProviderIdentityFailure.CLAIM_ATTACHED_ELSEWHERE
                                    else -> AndroidProviderIdentityFailure.PENDING_BINDING_INVALID
                                },
                                claim.kind,
                            )
                            if (durable.canonicalValueId != claimedPrimary ||
                                durable.linkedCanonicalValueIds != claim.claimedLinkedCanonicalValueIds
                            ) {
                                return@Callable divergence(AndroidProviderIdentityFailure.PENDING_CLAIM_MISMATCH, claim.kind)
                            }
                            actions += ResolutionAction.AttachPending(claim, pending.size)
                            durable
                        }
                        claim.claimedLinkedCanonicalValueIds.isNotEmpty() ->
                            return@Callable divergence(AndroidProviderIdentityFailure.UNBOUND_LINKED_CLAIM, claim.kind)
                        else -> {
                            val allocated = allocateFreshIdentity(
                                claim.canonicalContactId,
                                claim.rawContactId,
                                claim.providerRowId,
                                claim.accountName,
                                claim.kind,
                                resolvedIds,
                            )
                            actions += allocated.second
                            allocated.first
                        }
                    }
                    val ids = listOf(binding.canonicalValueId) + binding.linkedCanonicalValueIds.values
                    if (!resolvedIds.addAllDistinct(ids)) {
                        return@Callable divergence(AndroidProviderIdentityFailure.DUPLICATE_RESOLVED_IDENTITY, claim.kind)
                    }
                    resolved[claim.providerRowId] = binding
                }

                actions.forEach { action ->
                    when (action) {
                        is ResolutionAction.AttachPending -> {
                            val updated = dao.attachPendingBindingGroup(
                                account.value,
                                action.claim.canonicalContactId,
                                providerEpoch,
                                action.claim.rawContactId,
                                requireNotNull(action.claim.claimedCanonicalValueId),
                                action.claim.providerRowId,
                            )
                            if (updated != action.expectedRows) throw IdentityBindingTransactionDiverged()
                        }
                        is ResolutionAction.InsertFresh -> {
                            dao.insertOwners(listOf(action.owner))
                            dao.insertBindings(listOf(action.row))
                        }
                    }
                }
                AndroidProviderIdentityResolution.Bound(resolved)
            })
        } catch (_: SQLiteConstraintException) {
            divergence(AndroidProviderIdentityFailure.STORAGE_CONSTRAINT)
        } catch (_: IdentityBindingTransactionDiverged) {
            divergence(AndroidProviderIdentityFailure.TRANSACTION_DIVERGED)
        }
    }

    private fun divergence(
        reason: AndroidProviderIdentityFailure,
        kind: AndroidRowKind? = null,
    ): AndroidProviderIdentityResolution.Divergence {
        runCatching { onFailure(reason, kind) }
        return AndroidProviderIdentityResolution.Divergence
    }

    private fun allocateFreshIdentity(
        canonicalContactId: String,
        rawContactId: Long,
        providerRowId: Long,
        accountName: AndroidProviderAccountName,
        kind: AndroidRowKind,
        stagedIds: Set<String>,
    ): Pair<AndroidDurableValueBinding, ResolutionAction.InsertFresh> {
        repeat(MAX_ALLOCATION_ATTEMPTS) {
            val canonicalValueId = "android-value-${UUID.randomUUID()}"
            if (canonicalValueId !in stagedIds &&
                dao.getOwner(account.value, canonicalContactId, canonicalValueId) == null
            ) {
                val owner = AndroidProviderIdentityOwnerEntity(account.value, canonicalContactId, canonicalValueId)
                val row = AndroidProviderRowBindingEntity(
                    accountId = account.value,
                    canonicalContactId = canonicalContactId,
                    providerEpoch = providerEpoch,
                    rawContactLocator = rawContactId,
                    bindingPrimaryId = canonicalValueId,
                    role = PRIMARY_ROLE,
                    canonicalValueId = canonicalValueId,
                    androidAccountName = accountName.value,
                    kind = kind.name,
                    dataRowLocator = providerRowId,
                    state = STATE_ATTACHED,
                )
                return AndroidDurableValueBinding(canonicalValueId, emptyMap()) to
                    ResolutionAction.InsertFresh(owner, row)
            }
        }
        throw IdentityBindingTransactionDiverged()
    }

    private fun validClaim(claim: AndroidProviderIdentityClaim): Boolean =
        validScope(claim.canonicalContactId, claim.rawContactId) &&
            claim.providerRowId > 0 &&
            (claim.claimedCanonicalValueId == null ||
                claim.claimedCanonicalValueId.isBlank() ||
                validId(claim.claimedCanonicalValueId)) &&
            validLinked(claim.kind, claim.claimedLinkedCanonicalValueIds)

    private fun validBinding(kind: AndroidRowKind, binding: AndroidDurableValueBinding): Boolean =
        validId(binding.canonicalValueId) &&
            validLinked(kind, binding.linkedCanonicalValueIds) &&
            binding.canonicalValueId !in binding.linkedCanonicalValueIds.values

    private fun validLinked(kind: AndroidRowKind, linked: Map<AndroidLinkedValueRole, String>): Boolean {
        val allowed = when (kind) {
            AndroidRowKind.STRUCTURED_NAME -> setOf(AndroidLinkedValueRole.PHONETIC_NAME)
            AndroidRowKind.ORGANIZATION -> setOf(AndroidLinkedValueRole.TITLE, AndroidLinkedValueRole.ROLE)
            else -> emptySet()
        }
        return linked.keys.all { it in allowed } &&
            linked.values.all(::validId) &&
            linked.values.distinct().size == linked.size
    }

    private fun validScope(canonicalContactId: String, rawContactId: Long): Boolean =
        canonicalContactId.isNotBlank() && canonicalContactId.length <= MAX_ID_LENGTH && rawContactId > 0

    private fun validId(value: String): Boolean = value.isNotBlank() && value.length <= MAX_ID_LENGTH

    private fun AndroidPendingProviderBinding.toRows(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        rawContactId: Long,
        dataRowLocator: Long?,
        state: String,
    ): List<AndroidProviderRowBindingEntity> = buildList {
        add(
            row(
                accountName,
                canonicalContactId,
                rawContactId,
                binding.canonicalValueId,
                PRIMARY_ROLE,
                binding.canonicalValueId,
                dataRowLocator,
                state,
            ),
        )
        binding.linkedCanonicalValueIds.entries.sortedBy { it.key.name }.forEach { (role, id) ->
            add(
                row(
                    accountName,
                    canonicalContactId,
                    rawContactId,
                    binding.canonicalValueId,
                    role.name,
                    id,
                    dataRowLocator,
                    state,
                ),
            )
        }
    }

    private fun AndroidPendingProviderBinding.row(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        rawContactId: Long,
        primaryId: String,
        role: String,
        canonicalValueId: String,
        dataRowLocator: Long?,
        state: String,
    ) = AndroidProviderRowBindingEntity(
        accountId = account.value,
        canonicalContactId = canonicalContactId,
        providerEpoch = providerEpoch,
        rawContactLocator = rawContactId,
        bindingPrimaryId = primaryId,
        role = role,
        canonicalValueId = canonicalValueId,
        androidAccountName = accountName.value,
        kind = kind.name,
        dataRowLocator = dataRowLocator,
        state = state,
    )

    private fun List<AndroidProviderRowBindingEntity>.toDurableBinding(
        accountName: AndroidProviderAccountName,
        kind: AndroidRowKind,
        expectedState: String,
    ): AndroidDurableValueBinding? {
        if (isEmpty() || any {
                it.accountId != account.value ||
                    it.providerEpoch != providerEpoch ||
                    it.androidAccountName != accountName.value ||
                    it.kind != kind.name ||
                    it.state != expectedState
            }
        ) return null
        val primary = singleOrNull { it.role == PRIMARY_ROLE } ?: return null
        if (any { it.bindingPrimaryId != primary.canonicalValueId }) return null
        val linked = linkedMapOf<AndroidLinkedValueRole, String>()
        filterNot { it.role == PRIMARY_ROLE }.forEach { row ->
            val role = AndroidLinkedValueRole.entries.firstOrNull { it.name == row.role } ?: return null
            if (linked.put(role, row.canonicalValueId) != null) return null
        }
        return AndroidDurableValueBinding(primary.canonicalValueId, linked)
            .takeIf { validBinding(kind, it) }
    }

    private fun List<AndroidProviderRowBindingEntity>.matches(
        expected: List<AndroidProviderRowBindingEntity>,
        allowAttached: Boolean,
    ): Boolean {
        if (size != expected.size) return false
        return sortedBy { it.role }.zip(expected.sortedBy { it.role }).all { (actual, wanted) ->
            actual.copy(
                dataRowLocator = wanted.dataRowLocator,
                state = wanted.state,
            ) == wanted && (actual.state == STATE_PENDING || allowAttached && actual.state == STATE_ATTACHED)
        }
    }

    private fun MutableSet<String>.addAllDistinct(values: List<String>): Boolean {
        if (values.distinct().size != values.size || values.any { it in this }) return false
        addAll(values)
        return true
    }

    private sealed interface ResolutionAction {
        data class AttachPending(
            val claim: AndroidProviderIdentityClaim,
            val expectedRows: Int,
        ) : ResolutionAction

        data class InsertFresh(
            val owner: AndroidProviderIdentityOwnerEntity,
            val row: AndroidProviderRowBindingEntity,
        ) : ResolutionAction
    }

    private class IdentityBindingTransactionDiverged : RuntimeException()

    private class ProjectionBindingMove(
        val id: String, val old: Long, val current: Long, val kind: AndroidRowKind, val rows: Int,
    ) {
        override fun toString(): String = "ProjectionBindingMove(REDACTED)"
    }

    private companion object {
        const val PRIMARY_ROLE = "PRIMARY"
        const val STATE_PENDING = "PENDING"
        const val STATE_ATTACHED = "ATTACHED"
        const val MAX_ID_LENGTH = 4_096
        const val MAX_BINDINGS_PER_CONTACT = 128
        const val MAX_ALLOCATION_ATTEMPTS = 4
    }
}

/** Pending non-photo rows share one verified batch; photos retain their journal boundary. */
internal fun permitsProjectionBindingRelocation(kind: AndroidRowKind, pending: Boolean): Boolean =
    kind == AndroidRowKind.STRUCTURED_NAME || pending && kind != AndroidRowKind.PHOTO
