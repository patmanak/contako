package com.patmanak.contako.data.sync

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.local.AndroidContactIssueEntity
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContactEntity
import com.patmanak.contako.data.local.ContakoDatabase
import kotlinx.coroutines.CancellationException

internal enum class AndroidContactIssueOrigin { INGEST, PROJECTION }

/**
 * Retains an identified Android failure for local presentation, independently of the global
 * pass reason and the projection ledger's last successful CLEAN state. Callers still own all
 * retry, acknowledgement and write decisions. No stored identity enters diagnostics.
 */
internal class RoomAndroidContactIssueStore(private val database: ContakoDatabase) {
    suspend fun recordIngestFailure(
        context: AndroidInteroperabilityContext,
        raw: AndroidOwnedRawContact,
        reason: AndroidIngestActionRequiredReason,
    ): Boolean = presentationTransaction {
        val contact = trustedIngestContact(context, raw) ?: return@presentationTransaction false
        record(context, contact, AndroidContactIssueOrigin.INGEST, reason.name)
        true
    }

    suspend fun recordIngestFailure(
        context: AndroidInteroperabilityContext,
        raw: AndroidOwnedRawContact,
        reason: AndroidExistingContactPlanRepairReason,
    ): Boolean = presentationTransaction {
        val contact = trustedIngestContact(context, raw) ?: return@presentationTransaction false
        record(context, contact, AndroidContactIssueOrigin.INGEST, reason.name)
        true
    }

    /** Invoke only after this exact source-owned observation has been accepted. */
    suspend fun clearIngestFailure(
        context: AndroidInteroperabilityContext,
        raw: AndroidOwnedRawContact,
    ): Boolean = presentationTransaction {
        val contact = trustedIngestContact(context, raw) ?: return@presentationTransaction false
        database.androidContactIssueDao().delete(context.account.value, contact.id,
            AndroidContactIssueOrigin.INGEST.name, context.providerEpoch) > 0
    }

    suspend fun recordProjectionFailure(
        context: AndroidInteroperabilityContext,
        ledger: AndroidProjectionLedgerEntity,
        result: AndroidBoundedPageResult,
    ): Boolean = presentationTransaction {
        val reason = when (result) {
            AndroidBoundedPageResult.RepairRequired -> "REPAIR_REQUIRED"
            AndroidBoundedPageResult.ReplanRequired -> "REPLAN_REQUIRED"
            else -> return@presentationTransaction false
        }
        if (!currentAccount(context) || ledger.accountId != context.account.value ||
            ledger.providerEpoch != context.providerEpoch
        ) return@presentationTransaction false
        val current = database.androidProjectionLedgerDao().get(context.account.value, ledger.canonicalContactId)
            ?: return@presentationTransaction false
        // The executor may have advanced the ledger revision, but a rebound identity is not
        // the same failed item. Never attach a failure using only a provider row locator.
        if (current.providerEpoch != ledger.providerEpoch || current.sourceIdentity != ledger.sourceIdentity ||
            ledger.rawContactLocator != null && current.rawContactLocator != ledger.rawContactLocator
        ) return@presentationTransaction false
        val contact = database.contactDao().getEntity(context.account.value, ledger.canonicalContactId)
            ?: return@presentationTransaction false
        if (contact.remoteContactId != current.sourceIdentity) return@presentationTransaction false
        record(context, contact, AndroidContactIssueOrigin.PROJECTION, reason)
        true
    }

    /** Invoke only after successful projection or a current, source-owned presence proof. */
    suspend fun clearProjectionFailure(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
    ): Boolean = presentationTransaction {
        if (!currentAccount(context)) return@presentationTransaction false
        val ledger = database.androidProjectionLedgerDao().get(context.account.value, canonicalContactId)
            ?: return@presentationTransaction false
        val contact = database.contactDao().getEntity(context.account.value, canonicalContactId)
            ?: return@presentationTransaction false
        if (ledger.providerEpoch != context.providerEpoch || contact.remoteContactId != ledger.sourceIdentity) {
            return@presentationTransaction false
        }
        var removed = database.androidContactIssueDao().delete(context.account.value, canonicalContactId,
            AndroidContactIssueOrigin.PROJECTION.name, context.providerEpoch) > 0
        if (contact.isDeleted && contact.pendingMutationRevision == null &&
            ledger.tombstoneState == AndroidTombstoneState.REMOTE_CONVERGED.name &&
            ledger.projectionState == AndroidProjectionWriteState.DETACHED.name) {
            removed = database.androidContactIssueDao().delete(context.account.value, canonicalContactId,
                AndroidContactIssueOrigin.INGEST.name, context.providerEpoch) > 0 || removed
        }
        removed
    }

    private suspend fun record(
        context: AndroidInteroperabilityContext,
        contact: ContactEntity,
        origin: AndroidContactIssueOrigin,
        reason: String,
    ) = database.androidContactIssueDao().upsert(AndroidContactIssueEntity(
        context.account.value, contact.id, origin.name, context.providerEpoch, contact.revision, reason,
    ))

    private suspend fun trustedIngestContact(
        context: AndroidInteroperabilityContext,
        raw: AndroidOwnedRawContact,
    ): ContactEntity? {
        if (!currentAccount(context) || raw.deleted) return null
        val id = raw.canonicalContactIdClaim ?: return null
        val source = raw.sourceIdentity ?: return null
        val dao = database.androidProjectionLedgerDao()
        val ledger = dao.get(context.account.value, id) ?: return null
        if (ledger != dao.getByRawContactLocator(context.account.value, context.providerEpoch, raw.rawContactId) ||
            ledger.providerEpoch != context.providerEpoch || ledger.rawContactLocator != raw.rawContactId ||
            ledger.sourceIdentity != source || ledger.adoptionState != AndroidAdoptionState.ADOPTED.name ||
            ledger.tombstoneState != AndroidTombstoneState.NONE.name
        ) return null
        val canonical = database.contactDao().getEntity(context.account.value, id) ?: return null
        return canonical.takeIf { !it.isDeleted && it.remoteContactId == source }
    }

    private suspend fun currentAccount(context: AndroidInteroperabilityContext): Boolean {
        val dao = database.androidProjectionLedgerDao()
        val account = dao.getAccount(context.account.value) ?: return false
        return account.revision == context.accountRevision && account.providerEpoch == context.providerEpoch &&
            account.androidAccountName == context.androidAccountName &&
            dao.countOtherAccountsBoundToAndroidName(context.account.value, context.androidAccountName) == 0
    }

    private suspend fun presentationTransaction(block: suspend () -> Boolean): Boolean = try {
        database.withTransaction { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        // Presentation persistence must never grant or reject existing mutation intent.
        false
    }
}
