package com.patmanak.contako.data.local

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.repository.SaveValidationIssue

/**
 * Transaction-bound canonical mutation boundary for observations originating in ContactsProvider.
 *
 * Calls MUST be made from the same [ContakoDatabase] transaction that claims the Android
 * projection-ledger revision. This keeps the ledger claim, canonical aggregate, preservation
 * payload, affected group assignments, and outbox rows atomic without allowing this component to
 * announce a commit that is still owned by its caller.
 */
internal class RoomAndroidCanonicalMutationStore(
    private val database: ContakoDatabase,
    private val account: AccountScope,
    private val repository: RoomContactRepository = RoomContactRepository(database),
) {
    init {
        require(repository.isBackedBy(database)) {
            "The canonical mutation store and repository must share one ContakoDatabase"
        }
    }

    suspend fun applyContactDelta(
        expectedCanonicalRevision: Long,
        contact: CanonicalContact,
    ): AndroidCanonicalMutationResult {
        requireTransaction()
        if (contact.accountId != account.value) {
            return AndroidCanonicalMutationResult.Rejected(
                setOf(SaveValidationIssue.ACCOUNT_SCOPE_MISMATCH),
            )
        }
        if (contact.id.isBlank()) {
            return AndroidCanonicalMutationResult.Rejected(
                setOf(SaveValidationIssue.BLANK_CONTACT_ID),
            )
        }
        return repository.applyContactDeltaInCurrentTransaction(
            expectedCanonicalRevision = expectedCanonicalRevision,
            contact = contact,
        ).toAndroidResult()
    }

    suspend fun applyDeletion(
        expectedCanonicalRevision: Long,
        contactId: String,
    ): AndroidCanonicalMutationResult {
        requireTransaction()
        if (contactId.isBlank()) {
            return AndroidCanonicalMutationResult.Rejected(
                setOf(SaveValidationIssue.BLANK_CONTACT_ID),
            )
        }
        return repository.applyDeletionInCurrentTransaction(
            expectedCanonicalRevision = expectedCanonicalRevision,
            accountId = account.value,
            contactId = contactId,
        ).toAndroidResult()
    }

    /** Revision-only guard used when an Android observation changes no canonical value. */
    suspend fun isCurrentRevision(
        expectedCanonicalRevision: Long,
        contactId: String,
    ): Boolean {
        requireTransaction()
        if (contactId.isBlank()) return false
        return database.contactDao().get(account.value, contactId)?.contact?.revision ==
            expectedCanonicalRevision
    }

    /**
     * Reserves a canonical identifier for a raw contact that does not yet have enough information
     * to be decoded. The shell is intentionally tombstoned and blocked, and creates no outbox row.
     * A later [applyContactDelta] with expected revision zero activates it atomically.
     */
    suspend fun stageAndroidCreatedShell(canonicalContactId: String): AndroidCanonicalMutationResult {
        requireTransaction()
        if (canonicalContactId.isBlank()) {
            return AndroidCanonicalMutationResult.Rejected(
                setOf(SaveValidationIssue.BLANK_CONTACT_ID),
            )
        }

        val shell = CanonicalContact(
            accountId = account.value,
            id = canonicalContactId,
            revision = STAGED_SHELL_REVISION,
            updatedAtEpochMillis = 0L,
            actionRequiredReasons = setOf(STAGED_SHELL_REASON),
            pendingMutationRevision = null,
            conflictState = STAGED_SHELL_REASON,
            isDeleted = true,
        )
        val shellEntity = shell.toEntity()
        if (database.contactDao().insertIfAbsent(shellEntity) != INSERT_CONFLICT) {
            return AndroidCanonicalMutationResult.Applied(shell)
        }

        val existing = database.contactDao().get(account.value, canonicalContactId)
            ?: return AndroidCanonicalMutationResult.Stale
        val payload = database.contactPayloadDao().get(ownerKey(account.value, canonicalContactId))
        return if (existing.contact == shellEntity && existing.values.isEmpty() && payload == null) {
            AndroidCanonicalMutationResult.Applied(shell)
        } else {
            AndroidCanonicalMutationResult.Stale
        }
    }

    private fun requireTransaction() {
        check(database.inTransaction()) { "A caller-owned Room transaction is required" }
    }

    private companion object {
        const val INSERT_CONFLICT = -1L
        const val STAGED_SHELL_REVISION = 0L
        const val STAGED_SHELL_REASON = "ANDROID_INGESTION_STAGED"
    }
}

internal sealed interface AndroidCanonicalMutationResult {
    data class Applied(val contact: CanonicalContact) : AndroidCanonicalMutationResult
    data object Stale : AndroidCanonicalMutationResult
    data class Rejected(
        val issues: Set<SaveValidationIssue>,
    ) : AndroidCanonicalMutationResult
}

private fun RoomCanonicalContactMutationResult.toAndroidResult(): AndroidCanonicalMutationResult =
    when (this) {
        is RoomCanonicalContactMutationResult.Applied -> AndroidCanonicalMutationResult.Applied(contact)
        is RoomCanonicalContactMutationResult.Rejected -> AndroidCanonicalMutationResult.Rejected(issues)
        RoomCanonicalContactMutationResult.Stale -> AndroidCanonicalMutationResult.Stale
    }
