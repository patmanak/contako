package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.policy.ContactValueSupport
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.flow.map

/** One contact, metadata-only, reactive local reads. No provider, gateway or writer is a dependency. */
internal class RoomContactSyncDetailStore(private val database: ContakoDatabase) {
    private val dao get() = database.contactSyncEvidenceDao()

    fun observe(accountId: String, contactId: String) = dao.observeInvalidation(accountId, contactId).map {
        load(accountId, contactId)
    }

    suspend fun load(accountId: String, contactId: String): ContactSyncDetail? = database.withTransaction {
        val contact = database.contactDao().getEntity(accountId, contactId) ?: return@withTransaction null
        val proton = dao.get(accountId, contactId, "PROTON")
        val android = dao.get(accountId, contactId, "ANDROID")
        val intent = database.outboxDao().get(accountId, "CONTACT", contactId)
        val bodyIntent = intent?.takeUnless { it.operation == "ASSIGNMENTS" }
        val conflict = contact.conflictState != null || database.contactConflictDao().get(accountId, contactId) != null
        val importFailed = contact.remoteContactId?.let { database.remoteHydrationObligationDao().get(accountId, it) } != null
        val account = database.androidProjectionLedgerDao().getAccount(accountId)
        val ledger = database.androidProjectionLedgerDao().get(accountId, contactId)
        val membership = database.androidGroupProjectionDao().getMembership(accountId, contactId)
        val issues = listOfNotNull(database.androidContactIssueDao().get(accountId, contactId, "INGEST"),
            database.androidContactIssueDao().get(accountId, contactId, "PROJECTION"))
            .filter { it.providerEpoch == account?.providerEpoch }
        var localOnlyCount = 0
        var afterId: String? = null
        // One stable transaction and an exact candidate count bound the number of pages. This
        // remains scoped to one contact even for a rich import with thousands of local fields.
        val candidates = dao.localComplementCandidates(accountId, contactId)
        repeat((candidates + 127) / 128) {
            val page = dao.localComplementMetadata(accountId, contactId, afterId)
            localOnlyCount += page.count { row ->
                val kind = ContactValueKind.valueOf(row.kind)
                ContactValueSupport.isLocalOnly(ContactValue("metadata-only", kind, "", row.label, 0,
                    metadata = StringMapCodec.decode(row.metadataEncoding), preservationKey = row.preservationKey))
            }
            afterId = page.lastOrNull()?.id
        }
        val protonCurrent = proton != null && proton.canonicalRevision == contact.revision &&
            proton.sourceIdentity == contact.remoteContactId && proton.sourceVersion == contact.remoteVersion &&
            (proton.kind == ContactSyncEvidenceKind.CONFIRMED_ABSENCE.name) == contact.isDeleted
        val androidAvailable = account?.androidAccountName != null && !account.providerRepairPending
        val androidCurrent = if (android == null || account == null || ledger == null) false else {
            val common = android.canonicalRevision == contact.revision && android.sourceIdentity == contact.remoteContactId &&
                android.sourceIdentity == ledger.sourceIdentity && android.providerEpoch == account.providerEpoch &&
                android.providerEpoch == ledger.providerEpoch && android.androidAccountName == account.androidAccountName &&
                android.ledgerRevision == ledger.revision && ledger.pendingProjectionFingerprint == null
            if (android.kind == ContactSyncEvidenceKind.ANDROID_ABSENCE.name) common && contact.isDeleted &&
                ledger.projectionState == "DETACHED" && ledger.tombstoneState == "REMOTE_CONVERGED" &&
                (ledger.rawContactLocator == null || ledger.rawContactLocator == android.rawContactLocator)
            else common && !contact.isDeleted && ledger.projectionState == "CLEAN" && ledger.tombstoneState == "NONE" &&
                ledger.rawContactLocator == android.rawContactLocator && android.rawContactLocator != null &&
                ledger.canonicalProjectionFingerprint == android.canonicalFingerprint &&
                ledger.androidBaselineFingerprint == android.baselineFingerprint &&
                dao.contactBaselineFingerprint(accountId, contactId) == android.baselineFingerprint &&
                membership != null && membership.providerEpoch == android.providerEpoch &&
                membership.rawContactLocator == android.rawContactLocator && membership.projectionState == "CLEAN" &&
                membership.pendingProjectionFingerprint == null && membership.revision == android.membershipRevision &&
                membership.preferredEmailValueId == android.preferredEmailId &&
                membership.canonicalProjectionFingerprint == android.membershipFingerprint &&
                membership.androidBaselineFingerprint == android.membershipBaselineFingerprint &&
                dao.membershipBaselineIntegrity(accountId, contactId) == android.membershipBaselineIntegrity &&
                (android.photoReceiptKey == null || dao.photoReceiptMetadata(accountId, contactId)?.key() == android.photoReceiptKey)
        }
        val androidPending = ledger != null && if (contact.isDeleted)
            ledger.projectionState != "DETACHED" || ledger.tombstoneState != "REMOTE_CONVERGED"
        else ledger.projectionState != "CLEAN" || ledger.pendingProjectionFingerprint != null
        ContactSyncDetail(contact.id, contact.displayName.ifBlank { listOf(contact.firstName, contact.lastName).filter(String::isNotBlank).joinToString(" ") },
            contact.revision, contact.isDeleted,
            contactPeerDetail(proton, protonCurrent, conflict,
                actionRequired = importFailed || bodyIntent?.state == "ACTION_REQUIRED" || contact.actionRequiredReasonsEncoding.isNotEmpty(),
                pending = bodyIntent != null || (contact.pendingMutationRevision != null && intent?.operation != "ASSIGNMENTS")),
            contactPeerDetail(android, androidCurrent, actionRequired = issues.isNotEmpty(),
                pending = androidPending || (!contact.isDeleted && ledger == null && androidAvailable),
                unavailable = !androidAvailable),
            localOnlyCount, intent?.operation == "ASSIGNMENTS" || dao.knownGroupChangesPending(accountId, contactId))
    }
}

/** Pure precedence; retained dated evidence is historical whenever its exact context no longer matches. */
internal fun contactPeerDetail(evidence: ContactSyncEvidenceEntity?, current: Boolean, conflict: Boolean = false,
    actionRequired: Boolean = false, pending: Boolean = false, unavailable: Boolean = false): ContactPeerSyncDetail {
    val known = evidence?.let { ContactSyncEvidence(it.canonicalRevision, it.confirmedAtEpochMillis,
        ContactSyncEvidenceKind.valueOf(it.kind)) }
    val state = when {
        conflict -> ContactPeerSyncState.CONFLICT
        actionRequired -> ContactPeerSyncState.ACTION_REQUIRED
        pending -> ContactPeerSyncState.PENDING
        unavailable -> ContactPeerSyncState.UNAVAILABLE
        evidence == null -> ContactPeerSyncState.UNKNOWN
        current -> ContactPeerSyncState.CONFIRMED
        else -> ContactPeerSyncState.HISTORICAL
    }
    return ContactPeerSyncDetail(state, known)
}
