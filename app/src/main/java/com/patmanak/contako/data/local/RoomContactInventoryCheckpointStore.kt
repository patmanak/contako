package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailGroupMembership
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.proton.ContactInventoryCheckpoint
import com.patmanak.contako.data.proton.ContactInventoryCheckpointStore
import com.patmanak.contako.data.proton.VersionedContactInventoryCheckpoint

/**
 * Durable account-scoped inventory checkpoint store.
 *
 * Generation advancement and complete entry replacement share one Room transaction. A false result
 * means that another committed plan won the generation race; no row from the stale candidate is
 * written. Callers MUST present the planner's completed reconciliation proof before reaching this
 * boundary. This class performs no network operation and never emits checkpoint payload diagnostics.
 */
internal class RoomContactInventoryCheckpointStore(
    private val database: ContakoDatabase,
    private val checkpointHook: InventoryCheckpointWriteHook = InventoryCheckpointWriteHook.NONE,
) : ContactInventoryCheckpointStore {
    private val dao: ContactInventoryCheckpointDao
        get() = database.contactInventoryCheckpointDao()

    override suspend fun load(account: AccountScope): VersionedContactInventoryCheckpoint? =
        database.withTransaction {
            val accountId = account.value
            val header = dao.getCheckpoint(accountId) ?: return@withTransaction null
            require(header.generation >= 0)
            val entries = dao.getEntries(accountId)
            val groupsByContact = dao.getGroups(accountId).groupBy(ContactInventoryGroupEntity::contactId)
            val emailGroups = dao.getEmailGroups(accountId).groupBy {
                ContactEmailKey(it.contactId, it.emailId)
            }
            val membershipsByContact = dao.getEmailMemberships(accountId)
                .groupBy(ContactInventoryEmailMembershipEntity::contactId)

            val baselines = entries.map { entry ->
                ContactInventoryBaseline(
                    id = RemoteContactId(entry.contactId),
                    displayName = entry.displayName,
                    version = RemoteVersion(entry.remoteVersion),
                    sizeBytes = entry.sizeBytes,
                    modifiedAtEpochSeconds = entry.modifiedAtEpochSeconds,
                    groupIds = groupsByContact[entry.contactId].orEmpty().map {
                        RemoteGroupId(it.groupId)
                    },
                    emailGroupMemberships = membershipsByContact[entry.contactId].orEmpty().map { membership ->
                        RemoteEmailGroupMembership(
                            emailId = RemoteEmailId(membership.emailId),
                            groupIds = emailGroups[ContactEmailKey(entry.contactId, membership.emailId)]
                                .orEmpty()
                                .map { RemoteGroupId(it.groupId) },
                        )
                    },
                )
            }
            VersionedContactInventoryCheckpoint(
                generation = header.generation,
                checkpoint = ContactInventoryCheckpoint(baselines, header.eventCursor),
            )
        }

    override suspend fun compareAndSet(
        account: AccountScope,
        expectedGeneration: Long?,
        checkpoint: ContactInventoryCheckpoint,
    ): Boolean {
        checkpointHook.onCheckpoint(InventoryCheckpointWriteCheckpoint.BEFORE_TRANSACTION)
        val accountId = account.value
        val nextGeneration = expectedGeneration?.let { Math.addExact(it, 1L) } ?: 0L
        val committed = database.withTransaction {
            val generationAdvanced = if (expectedGeneration == null) {
                dao.insertInitialCheckpoint(ContactInventoryCheckpointEntity(accountId, nextGeneration, checkpoint.eventCursor)) != -1L
            } else {
                dao.compareAndSetGeneration(accountId, expectedGeneration, nextGeneration, checkpoint.eventCursor) == 1
            }
            if (!generationAdvanced) return@withTransaction false

            checkpointHook.onCheckpoint(InventoryCheckpointWriteCheckpoint.AFTER_GENERATION_CAS)
            dao.deleteEntries(accountId)
            checkpointHook.onCheckpoint(InventoryCheckpointWriteCheckpoint.AFTER_ENTRY_DELETE)

            val entities = checkpoint.toEntities(accountId)
            dao.insertEntries(entities.entries)
            dao.insertGroups(entities.groups)
            dao.insertEmailMemberships(entities.emailMemberships)
            dao.insertEmailGroups(entities.emailGroups)
            checkpointHook.onCheckpoint(InventoryCheckpointWriteCheckpoint.AFTER_ENTRY_INSERT)
            true
        }
        if (committed) checkpointHook.onCheckpoint(InventoryCheckpointWriteCheckpoint.AFTER_COMMIT)
        return committed
    }

    private fun ContactInventoryCheckpoint.toEntities(accountId: String): CheckpointEntities {
        val entries = mutableListOf<ContactInventoryEntryEntity>()
        val groups = mutableListOf<ContactInventoryGroupEntity>()
        val emailMemberships = mutableListOf<ContactInventoryEmailMembershipEntity>()
        val emailGroups = mutableListOf<ContactInventoryEmailGroupEntity>()

        this.entries.forEach { baseline ->
            val contactId = baseline.id.value
            entries += ContactInventoryEntryEntity(
                accountId = accountId,
                contactId = contactId,
                displayName = baseline.displayName,
                remoteVersion = baseline.version.value,
                sizeBytes = baseline.sizeBytes,
                modifiedAtEpochSeconds = baseline.modifiedAtEpochSeconds,
            )
            baseline.groupIds.forEach { group ->
                groups += ContactInventoryGroupEntity(accountId, contactId, group.value)
            }
            baseline.emailGroupMemberships.forEach { membership ->
                val emailId = membership.emailId.value
                emailMemberships += ContactInventoryEmailMembershipEntity(accountId, contactId, emailId)
                membership.groupIds.forEach { group ->
                    emailGroups += ContactInventoryEmailGroupEntity(accountId, contactId, emailId, group.value)
                }
            }
        }
        return CheckpointEntities(entries, groups, emailMemberships, emailGroups)
    }
}

internal enum class InventoryCheckpointWriteCheckpoint {
    BEFORE_TRANSACTION,
    AFTER_GENERATION_CAS,
    AFTER_ENTRY_DELETE,
    AFTER_ENTRY_INSERT,
    AFTER_COMMIT,
}

internal fun interface InventoryCheckpointWriteHook {
    fun onCheckpoint(checkpoint: InventoryCheckpointWriteCheckpoint)

    companion object {
        val NONE = InventoryCheckpointWriteHook { }
    }
}

private data class ContactEmailKey(val contactId: String, val emailId: String)

private data class CheckpointEntities(
    val entries: List<ContactInventoryEntryEntity>,
    val groups: List<ContactInventoryGroupEntity>,
    val emailMemberships: List<ContactInventoryEmailMembershipEntity>,
    val emailGroups: List<ContactInventoryEmailGroupEntity>,
)
