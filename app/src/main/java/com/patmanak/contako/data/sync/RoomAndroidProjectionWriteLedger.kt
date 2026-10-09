package com.patmanak.contako.data.sync

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipWritePlan
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.AndroidExpectedSourceIdentity
import com.patmanak.contako.data.android.provider.AndroidGroupMembershipWriteAuthorizer
import com.patmanak.contako.data.android.provider.AndroidUnclaimedDataRowAuthorizer
import com.patmanak.contako.data.local.AndroidGroupMembershipBaselineEntity
import com.patmanak.contako.data.local.AndroidProjectionBaselineEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.toDomain

internal sealed interface AndroidProjectionWriteLedgerResult {
    data object Applied : AndroidProjectionWriteLedgerResult
    data object Stale : AndroidProjectionWriteLedgerResult
    data object RepairRequired : AndroidProjectionWriteLedgerResult
}

/** Durable contact/membership write intent and exact post-provider reconciliation. */
internal class RoomAndroidProjectionWriteLedger(
    private val database: ContakoDatabase,
    private val mapper: CanonicalAndroidContactMapper = CanonicalAndroidContactMapper(),
) {
    suspend fun prepare(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        expectedContactLedgerRevision: Long,
        expectedCanonicalRevision: Long,
        expectedMembershipRevision: Long,
        contactSnapshot: AndroidContactSnapshot,
        membershipSnapshot: AndroidGroupMembershipSnapshot,
    ): AndroidProjectionWriteLedgerResult = database.withTransaction {
        val account = database.androidProjectionLedgerDao().getAccount(context.account.value)
            ?: return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        if (account.revision != context.accountRevision || account.providerEpoch != context.providerEpoch ||
            account.androidAccountName != context.androidAccountName
        ) return@withTransaction AndroidProjectionWriteLedgerResult.Stale
        val contact = database.contactDao().get(context.account.value, canonicalContactId)?.toDomain()
            ?: return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        val ledger = database.androidProjectionLedgerDao().get(context.account.value, canonicalContactId)
            ?: return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        val membership = database.androidGroupProjectionDao().getMembership(context.account.value, canonicalContactId)
            ?: return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        if (contact.revision != expectedCanonicalRevision || contact.isDeleted ||
            ledger.revision != expectedContactLedgerRevision || ledger.providerEpoch != context.providerEpoch ||
            membership.revision != expectedMembershipRevision || membership.providerEpoch != context.providerEpoch ||
            membership.rawContactLocator != ledger.rawContactLocator
        ) return@withTransaction AndroidProjectionWriteLedgerResult.Stale
        try {
            membershipSnapshot.requireCurrentCanonicalContext(contact)
        } catch (_: IllegalArgumentException) {
            return@withTransaction AndroidProjectionWriteLedgerResult.Stale
        }
        val contactFingerprint = mapper.fingerprint(contactSnapshot).sha256Hex
        val membershipFingerprint = membershipSnapshot.semanticFingerprint().sha256Hex
        if (database.androidProjectionLedgerDao().compareAndSetRevision(
                context.account.value, canonicalContactId, expectedContactLedgerRevision,
            ) != 1 || database.androidGroupProjectionDao().compareAndSetMembershipRevision(
                context.account.value, canonicalContactId, expectedMembershipRevision,
            ) != 1
        ) return@withTransaction AndroidProjectionWriteLedgerResult.Stale
        check(database.androidProjectionLedgerDao().update(ledger.copy(
            revision = Math.incrementExact(expectedContactLedgerRevision),
            canonicalProjectionFingerprint = contactFingerprint,
            pendingProjectionFingerprint = contactFingerprint,
            projectionState = AndroidProjectionWriteState.WRITE_PENDING.name,
        )) == 1)
        check(database.androidGroupProjectionDao().updateMembership(membership.copy(
            revision = Math.incrementExact(expectedMembershipRevision),
            preferredEmailValueId = membershipSnapshot.preferredEmailValueId,
            canonicalProjectionFingerprint = membershipFingerprint,
            pendingProjectionFingerprint = membershipFingerprint,
            projectionState = AndroidProjectionWriteState.WRITE_PENDING.name,
        )) == 1)
        AndroidProjectionWriteLedgerResult.Applied
    }

    /**
     * Clears a pending write whose desired state is no longer current.
     *
     * A preparation records the fingerprint it intends to write. When the canonical contact changes
     * before the write completes, that fingerprint stops matching and the entry can neither resume
     * nor prepare again, so it stayed WRITE_PENDING permanently. Nothing partial is undone here:
     * the provider write either happened and will be re-observed, or it did not.
     *
     * Only the durable intent is dropped, so the next pass re-plans from current state.
     * Returns `false` when the ledger moved underneath, leaving recovery to that newer state.
     */
    suspend fun abandonStalePreparation(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        expectedContactLedgerRevision: Long,
        expectedMembershipRevision: Long,
    ): Boolean = database.withTransaction {
        val ledger = database.androidProjectionLedgerDao().get(context.account.value, canonicalContactId)
            ?: return@withTransaction false
        val membership = database.androidGroupProjectionDao().getMembership(context.account.value, canonicalContactId)
            ?: return@withTransaction false
        if (ledger.revision != expectedContactLedgerRevision ||
            membership.revision != expectedMembershipRevision ||
            ledger.providerEpoch != context.providerEpoch ||
            membership.providerEpoch != context.providerEpoch
        ) return@withTransaction false

        if (database.androidProjectionLedgerDao().compareAndSetRevision(
                context.account.value, canonicalContactId, expectedContactLedgerRevision,
            ) != 1 || database.androidGroupProjectionDao().compareAndSetMembershipRevision(
                context.account.value, canonicalContactId, expectedMembershipRevision,
            ) != 1
        ) return@withTransaction false

        // DIRTY, not CLEAN: the provider state is unknown, so the next pass must re-observe and
        // re-project rather than assume the contact is already correct.
        check(database.androidProjectionLedgerDao().update(ledger.copy(
            revision = Math.incrementExact(expectedContactLedgerRevision),
            pendingProjectionFingerprint = null,
            projectionState = AndroidProjectionWriteState.DETACHED.name,
        )) == 1)
        check(database.androidGroupProjectionDao().updateMembership(membership.copy(
            revision = Math.incrementExact(expectedMembershipRevision),
            pendingProjectionFingerprint = null,
            projectionState = AndroidProjectionWriteState.DETACHED.name,
        )) == 1)
        true
    }

    suspend fun complete(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        expectedCanonicalRevision: Long,
        expectedPreparedContactRevision: Long,
        expectedPreparedMembershipRevision: Long,
        observedContact: AndroidContactSnapshot,
        observedMembership: AndroidGroupMembershipSnapshot,
    ): AndroidProjectionWriteLedgerResult = database.withTransaction {
        val account = database.androidProjectionLedgerDao().getAccount(context.account.value)
            ?: return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        val contact = database.contactDao().get(context.account.value, canonicalContactId)?.toDomain()
            ?: return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        val ledger = database.androidProjectionLedgerDao().get(context.account.value, canonicalContactId)
            ?: return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        val membership = database.androidGroupProjectionDao().getMembership(context.account.value, canonicalContactId)
            ?: return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        if (account.revision != context.accountRevision || account.providerEpoch != context.providerEpoch ||
            account.androidAccountName != context.androidAccountName || contact.revision != expectedCanonicalRevision ||
            contact.isDeleted || ledger.revision != expectedPreparedContactRevision ||
            membership.revision != expectedPreparedMembershipRevision || ledger.providerEpoch != context.providerEpoch ||
            membership.providerEpoch != context.providerEpoch ||
            observedContact.canonicalContactId != canonicalContactId ||
            observedMembership.accountId != context.account.value ||
            observedMembership.canonicalContactId != canonicalContactId
        ) return@withTransaction AndroidProjectionWriteLedgerResult.Stale
        val contactFingerprint = mapper.fingerprint(observedContact).sha256Hex
        val verifiedProjectionFingerprint = mapper.fingerprint(
            mapper.projectionComparisonSnapshot(
                mapper.normalizeGeneratedName(observedContact, mapper.project(contact)),
            ),
        ).sha256Hex
        val membershipFingerprint = observedMembership.semanticFingerprint().sha256Hex
        if (ledger.pendingProjectionFingerprint != verifiedProjectionFingerprint ||
            membership.pendingProjectionFingerprint != membershipFingerprint
        ) return@withTransaction AndroidProjectionWriteLedgerResult.RepairRequired
        try {
            observedMembership.requireCurrentCanonicalContext(contact)
        } catch (_: IllegalArgumentException) {
            return@withTransaction AndroidProjectionWriteLedgerResult.Stale
        }
        if (database.androidProjectionLedgerDao().compareAndSetRevision(
                context.account.value, canonicalContactId, expectedPreparedContactRevision,
            ) != 1 || database.androidGroupProjectionDao().compareAndSetMembershipRevision(
                context.account.value, canonicalContactId, expectedPreparedMembershipRevision,
            ) != 1
        ) return@withTransaction AndroidProjectionWriteLedgerResult.Stale
        check(database.androidProjectionLedgerDao().update(ledger.copy(
            revision = Math.incrementExact(expectedPreparedContactRevision),
            androidBaselineFingerprint = contactFingerprint,
            pendingProjectionFingerprint = null,
            projectionState = AndroidProjectionWriteState.CLEAN.name,
            adoptionState = if (ledger.sourceIdentity != null && ledger.rawContactLocator != null) {
                AndroidAdoptionState.ADOPTED.name
            } else {
                ledger.adoptionState
            },
        )) == 1)
        val encodedContact = AndroidContactSnapshotBinaryCodec.encode(observedContact)
        database.androidProjectionLedgerDao().upsertBaseline(AndroidProjectionBaselineEntity(
            context.account.value, canonicalContactId, contactFingerprint, encodedContact,
        ))
        check(database.androidGroupProjectionDao().updateMembership(membership.copy(
            revision = Math.incrementExact(expectedPreparedMembershipRevision),
            androidBaselineFingerprint = membershipFingerprint,
            pendingProjectionFingerprint = null,
            projectionState = AndroidProjectionWriteState.CLEAN.name,
        )) == 1)
        val encodedMembership = AndroidGroupMembershipSnapshotBinaryCodec.encode(observedMembership)
        database.androidGroupProjectionDao().upsertMembershipBaseline(AndroidGroupMembershipBaselineEntity(
            context.account.value,
            canonicalContactId,
            AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(encodedMembership).sha256Hex,
            encodedMembership,
        ))
        AndroidProjectionWriteLedgerResult.Applied
    }

    val authorizer = AndroidGroupMembershipWriteAuthorizer { plan, rawContactId, _, source, sourceAfterWrite ->
        isCurrent(plan, rawContactId, source, sourceAfterWrite)
    }

    val unclaimedDataRowAuthorizer = AndroidUnclaimedDataRowAuthorizer { plan, rawContactId, identities ->
        database.withTransaction {
            val account = database.androidProjectionLedgerDao().getAccount(plan.account.value)
                ?: return@withTransaction false
            val ledger = database.androidProjectionLedgerDao().get(plan.account.value, plan.canonicalContactId)
                ?: return@withTransaction false
            if (account.androidAccountName != plan.androidAccountName.value ||
                account.providerEpoch != plan.providerEpoch || ledger.providerEpoch != plan.providerEpoch ||
                ledger.rawContactLocator != rawContactId || ledger.tombstoneState != "NONE" ||
                ledger.projectionState != AndroidProjectionWriteState.WRITE_PENDING.name ||
                ledger.pendingProjectionFingerprint == null || identities.isEmpty()
            ) return@withTransaction false
            identities.all { identity ->
                val binding = database.androidProviderIdentityDao().getBindingByCanonicalValueId(
                    plan.account.value, plan.canonicalContactId, plan.providerEpoch, identity.canonicalValueId,
                ) ?: return@all false
                binding.androidAccountName == plan.androidAccountName.value &&
                    binding.rawContactLocator == rawContactId && identity.providerRowId != null &&
                    binding.dataRowLocator == identity.providerRowId && binding.state == "ATTACHED" &&
                    binding.role == "PRIMARY" && binding.bindingPrimaryId == identity.canonicalValueId
            }
        }
    }

    private suspend fun isCurrent(
        plan: AndroidGroupMembershipWritePlan,
        rawContactId: Long,
        source: AndroidExpectedSourceIdentity,
        sourceAfterWrite: String?,
    ): Boolean = database.withTransaction {
        val intendedSourceIdentity = when (source) {
            AndroidExpectedSourceIdentity.Missing -> sourceAfterWrite ?: return@withTransaction false
            is AndroidExpectedSourceIdentity.Present -> {
                if (sourceAfterWrite != null && sourceAfterWrite != source.value) return@withTransaction false
                source.value
            }
        }
        val account = database.androidProjectionLedgerDao().getAccount(plan.account.value) ?: return@withTransaction false
        val contact = database.contactDao().get(plan.account.value, plan.canonicalContactId)?.toDomain()
            ?: return@withTransaction false
        val ledger = database.androidProjectionLedgerDao().get(plan.account.value, plan.canonicalContactId)
            ?: return@withTransaction false
        val membership = database.androidGroupProjectionDao().getMembership(plan.account.value, plan.canonicalContactId)
            ?: return@withTransaction false
        if (account.androidAccountName != plan.androidAccountName.value || account.providerEpoch != plan.providerEpoch ||
            ledger.providerEpoch != plan.providerEpoch || ledger.rawContactLocator != rawContactId ||
            ledger.sourceIdentity != intendedSourceIdentity || ledger.pendingProjectionFingerprint == null ||
            membership.providerEpoch != plan.providerEpoch || membership.rawContactLocator != rawContactId ||
            membership.pendingProjectionFingerprint == null || contact.isDeleted
        ) return@withTransaction false
        val desired = com.patmanak.contako.data.android.mapping.AndroidGroupProjectionPolicy().projectMemberships(
            contact,
            database.contactGroupDao().getAll(plan.account.value).map { it.toDomain() },
        )
        if (desired.preferredEmailValueId != plan.preferredEmailValueId ||
            desired.availability != plan.availability || desired.canonicalGroupIds != plan.desiredCanonicalGroupIds
        ) return@withTransaction false
        plan.assertedGroupBindings.all { binding ->
            val group = database.androidGroupProjectionDao().getGroup(plan.account.value, binding.canonicalGroupId)
                ?: return@all false
            group.providerEpoch == plan.providerEpoch && group.groupRowLocator == binding.groupRowLocator &&
                group.providerVersion == binding.expectedVersion && group.sourceIdentity == binding.sourceIdentity
        }
    }
}
