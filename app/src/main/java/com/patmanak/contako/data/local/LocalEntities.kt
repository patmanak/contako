package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts",
    primaryKeys = ["account_id", "id"],
    indices = [
        Index(value = ["owner_key"], unique = true),
        Index(value = ["account_id", "is_deleted", "sort_name"]),
    ],
)
internal data class ContactEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    val id: String,
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "first_name") val firstName: String,
    @ColumnInfo(name = "last_name") val lastName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "sort_name") val sortName: String,
    val revision: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "remote_contact_id") val remoteContactId: String?,
    @ColumnInfo(name = "remote_vcard_uid") val remoteVCardUid: String?,
    @ColumnInfo(name = "remote_version") val remoteVersion: String?,
    @ColumnInfo(name = "action_required_reasons") val actionRequiredReasonsEncoding: String,
    @ColumnInfo(name = "pending_mutation_revision") val pendingMutationRevision: Long?,
    @ColumnInfo(name = "conflict_state") val conflictState: String?,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
)

@Entity(
    tableName = "contact_values",
    primaryKeys = ["account_id", "contact_id", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["owner_key"],
            childColumns = ["owner_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["owner_key"]),
        Index(value = ["account_id", "contact_id", "kind", "position"]),
    ],
)
internal data class ContactValueEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    val id: String,
    val kind: String,
    val value: String,
    val label: String?,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "is_primary") val isPrimary: Boolean,
    @ColumnInfo(name = "components_encoding") val componentsEncoding: String,
    @ColumnInfo(name = "metadata_encoding") val metadataEncoding: String,
    @ColumnInfo(name = "binary_reference") val binaryReference: String?,
    @ColumnInfo(name = "preservation_key") val preservationKey: String?,
)

@Entity(
    tableName = "contact_payloads",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["owner_key"],
            childColumns = ["owner_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ContactPayloadEntity(
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "raw_properties_encoding") val rawPropertiesEncoding: String,
    @ColumnInfo(name = "remote_baseline") val remoteBaseline: String?,
)

@Entity(
    tableName = "contact_groups",
    primaryKeys = ["account_id", "id"],
    indices = [
        Index(value = ["owner_key"], unique = true),
        Index(value = ["account_id", "is_deleted", "name"]),
    ],
)
internal data class ContactGroupEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    val id: String,
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    val name: String,
    val color: String,
    @ColumnInfo(name = "display_order") val displayOrder: Int,
    @ColumnInfo(name = "is_visible") val isVisible: Boolean,
    val revision: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "remote_label_id") val remoteLabelId: String?,
    @ColumnInfo(name = "remote_version") val remoteVersion: String?,
    @ColumnInfo(name = "pending_mutation_revision") val pendingMutationRevision: Long?,
    @ColumnInfo(name = "conflict_state") val conflictState: String?,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
)

@Entity(
    tableName = "group_memberships",
    primaryKeys = ["account_id", "group_id", "contact_id", "email_value_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactGroupEntity::class,
            parentColumns = ["owner_key"],
            childColumns = ["group_owner_key"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactValueEntity::class,
            parentColumns = ["account_id", "contact_id", "id"],
            childColumns = ["account_id", "contact_id", "email_value_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["group_owner_key"]),
        Index(value = ["account_id", "contact_id", "email_value_id"]),
    ],
)
internal data class GroupMembershipEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "group_owner_key") val groupOwnerKey: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "email_value_id") val emailValueId: String,
)

@Entity(
    tableName = "outbox_mutations",
    primaryKeys = ["account_id", "aggregate_type", "aggregate_id"],
    indices = [Index(value = ["account_id", "next_attempt_at_epoch_millis"])],
)
internal data class OutboxMutationEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "aggregate_type") val aggregateType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    val operation: String,
    val revision: Long,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at_epoch_millis") val nextAttemptAtEpochMillis: Long = 0,
    @ColumnInfo(name = "error_category") val errorCategory: String? = null,
    @ColumnInfo(name = "blocked_reason") val blockedReason: String? = null,
    @ColumnInfo(name = "remote_identity") val remoteIdentity: String? = null,
    @ColumnInfo(name = "remote_version") val remoteVersion: String? = null,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    val state: String = DurableMutationState.PENDING.name,
    @ColumnInfo(name = "requires_reconciliation") val requiresReconciliation: Boolean = false,
    @ColumnInfo(name = "last_attempt_at_epoch_millis") val lastAttemptAtEpochMillis: Long? = null,
    @ColumnInfo(name = "device_elapsed_realtime_millis") val deviceElapsedRealtimeMillis: Long = 0,
    @ColumnInfo(name = "server_offset_millis") val serverOffsetMillis: Long? = null,
    @ColumnInfo(name = "calibration_age_millis") val calibrationAgeMillis: Long? = null,
    @ColumnInfo(name = "round_trip_millis") val roundTripMillis: Long? = null,
    @ColumnInfo(name = "server_precision_millis") val serverPrecisionMillis: Long? = null,
    @ColumnInfo(name = "uncertainty_millis") val uncertaintyMillis: Long? = null,
    @ColumnInfo(name = "interval_earliest_epoch_millis") val intervalEarliestEpochMillis: Long? = null,
    @ColumnInfo(name = "interval_latest_epoch_millis") val intervalLatestEpochMillis: Long? = null,
    @ColumnInfo(name = "clock_jump_detected") val clockJumpDetected: Boolean = false,
) {
    override fun toString(): String =
        "OutboxMutationEntity(REDACTED, operation=$operation, revision=$revision, " +
            "attemptCount=$attemptCount, state=$state, requiresReconciliation=$requiresReconciliation)"
}

@Entity(tableName = "contact_inventory_checkpoints")
internal data class ContactInventoryCheckpointEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val generation: Long,
    @ColumnInfo(name = "event_cursor") val eventCursor: String? = null,
) {
    override fun toString(): String = "ContactInventoryCheckpointEntity(REDACTED, generation=$generation)"
}

@Entity(
    tableName = "contact_inventory_entries",
    primaryKeys = ["account_id", "contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactInventoryCheckpointEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ContactInventoryEntryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "remote_version") val remoteVersion: String,
    // Nullable since D-096: a public-directory inventory carries no server size or modification
    // time, and change detection then relies on the version fingerprint alone.
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "modified_at_epoch_seconds") val modifiedAtEpochSeconds: Long?,
) {
    override fun toString(): String = "ContactInventoryEntryEntity(REDACTED)"
}

@Entity(
    tableName = "contact_inventory_groups",
    primaryKeys = ["account_id", "contact_id", "group_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactInventoryEntryEntity::class,
            parentColumns = ["account_id", "contact_id"],
            childColumns = ["account_id", "contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ContactInventoryGroupEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
) {
    override fun toString(): String = "ContactInventoryGroupEntity(REDACTED)"
}

@Entity(
    tableName = "contact_inventory_email_memberships",
    primaryKeys = ["account_id", "contact_id", "email_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactInventoryEntryEntity::class,
            parentColumns = ["account_id", "contact_id"],
            childColumns = ["account_id", "contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ContactInventoryEmailMembershipEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "email_id") val emailId: String,
) {
    override fun toString(): String = "ContactInventoryEmailMembershipEntity(REDACTED)"
}

@Entity(
    tableName = "contact_inventory_email_groups",
    primaryKeys = ["account_id", "contact_id", "email_id", "group_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactInventoryEmailMembershipEntity::class,
            parentColumns = ["account_id", "contact_id", "email_id"],
            childColumns = ["account_id", "contact_id", "email_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ContactInventoryEmailGroupEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "email_id") val emailId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
) {
    override fun toString(): String = "ContactInventoryEmailGroupEntity(REDACTED)"
}

@Entity(tableName = "sync_account_status")
internal data class SyncAccountStatusEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val state: String,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "last_success_at_epoch_millis") val lastSuccessAtEpochMillis: Long?,
    @ColumnInfo(name = "pending_mutation_count") val pendingMutationCount: Int,
    @ColumnInfo(name = "action_required_count") val actionRequiredCount: Int,
    @ColumnInfo(name = "action_reason") val actionReason: String?,
    @ColumnInfo(name = "blocked_since_epoch_millis") val blockedSinceEpochMillis: Long?,
    @ColumnInfo(name = "notification_claimed_for_block_epoch_millis")
    val notificationClaimedForBlockEpochMillis: Long?,
) {
    override fun toString(): String =
        "SyncAccountStatusEntity(REDACTED, state=$state, pendingMutationCount=$pendingMutationCount, " +
            "actionRequiredCount=$actionRequiredCount, actionReason=$actionReason)"
}

@Entity(tableName = "full_repair_progress")
internal data class FullRepairProgressEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val revision: Long,
    val phase: String,
    @ColumnInfo(name = "phase_rank") val phaseRank: Int,
    @ColumnInfo(name = "completed_units") val completedUnits: Long,
    @ColumnInfo(name = "total_units") val totalUnits: Long?,
    @ColumnInfo(name = "started_at_epoch_millis") val startedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "non_wifi_confirmed") val nonWifiConfirmed: Boolean,
    @ColumnInfo(name = "cancellation_requested") val cancellationRequested: Boolean,
) {
    override fun toString(): String =
        "FullRepairProgressEntity(REDACTED, revision=$revision, phase=$phase, " +
            "completedUnits=$completedUnits, totalUnits=$totalUnits, " +
            "cancellationRequested=$cancellationRequested)"
}

@Entity(tableName = "android_projection_accounts")
internal data class AndroidProjectionAccountEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val revision: Long,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "android_account_name") val androidAccountName: String? = null,
    @ColumnInfo(name = "provider_repair_pending") val providerRepairPending: Boolean = false,
) {
    init {
        require(accountId.isNotBlank())
        require(revision >= 0)
        require(providerEpoch >= 0)
        require(androidAccountName == null || androidAccountName.isNotBlank())
    }

    override fun toString(): String =
        "AndroidProjectionAccountEntity(REDACTED, revision=$revision, providerEpoch=$providerEpoch)"
}

/**
 * Durable Android interoperability ledger. The provider row value is only a replaceable locator;
 * durable identity is the account-scoped canonical contact key in the primary key.
 */
@Entity(
    tableName = "android_projection_ledger",
    primaryKeys = ["account_id", "canonical_contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidProjectionAccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["account_id", "id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "provider_epoch", "raw_contact_locator"], unique = true),
        Index(value = ["account_id", "source_identity"], unique = true),
    ],
)
internal data class AndroidProjectionLedgerEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    val revision: Long,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "raw_contact_locator") val rawContactLocator: Long?,
    @ColumnInfo(name = "source_identity") val sourceIdentity: String?,
    @ColumnInfo(name = "canonical_projection_fingerprint") val canonicalProjectionFingerprint: String?,
    @ColumnInfo(name = "android_baseline_fingerprint") val androidBaselineFingerprint: String?,
    @ColumnInfo(name = "pending_projection_fingerprint") val pendingProjectionFingerprint: String?,
    @ColumnInfo(name = "observed_android_fingerprint") val observedAndroidFingerprint: String?,
    @ColumnInfo(name = "projection_state") val projectionState: String,
    @ColumnInfo(name = "ingestion_state") val ingestionState: String,
    @ColumnInfo(name = "tombstone_state") val tombstoneState: String,
    @ColumnInfo(name = "adoption_state") val adoptionState: String,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalContactId.isNotBlank())
        require(revision >= 0)
        require(providerEpoch >= 0)
        require(rawContactLocator == null || rawContactLocator > 0)
        require(sourceIdentity == null || sourceIdentity.isNotBlank())
        listOfNotNull(
            canonicalProjectionFingerprint,
            androidBaselineFingerprint,
            pendingProjectionFingerprint,
            observedAndroidFingerprint,
        ).forEach { require(SHA_256_HEX.matches(it)) }
    }

    override fun toString(): String =
        "AndroidProjectionLedgerEntity(REDACTED, revision=$revision, " +
            "projectionState=$projectionState, ingestionState=$ingestionState, " +
            "tombstoneState=$tombstoneState, adoptionState=$adoptionState)"

    private companion object {
        val SHA_256_HEX = Regex("[0-9a-f]{64}")
    }
}

@Entity(
    tableName = "android_provider_identity_owners",
    primaryKeys = ["account_id", "canonical_contact_id", "canonical_value_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["account_id", "id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["account_id", "canonical_contact_id"])],
)
internal data class AndroidProviderIdentityOwnerEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    @ColumnInfo(name = "canonical_value_id") val canonicalValueId: String,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalContactId.isNotBlank())
        require(canonicalValueId.isNotBlank())
    }

    override fun toString(): String = "AndroidProviderIdentityOwnerEntity(REDACTED)"
}

@Entity(
    tableName = "android_provider_row_bindings",
    primaryKeys = [
        "account_id",
        "canonical_contact_id",
        "provider_epoch",
        "raw_contact_locator",
        "binding_primary_id",
        "role",
    ],
    foreignKeys = [
        ForeignKey(
            entity = AndroidProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_contact_id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AndroidProviderIdentityOwnerEntity::class,
            parentColumns = ["account_id", "canonical_contact_id", "canonical_value_id"],
            childColumns = ["account_id", "canonical_contact_id", "canonical_value_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "canonical_contact_id"]),
        Index(value = ["account_id", "canonical_contact_id", "canonical_value_id"]),
        Index(
            value = ["account_id", "canonical_contact_id", "provider_epoch", "canonical_value_id"],
            unique = true,
        ),
        Index(
            value = [
                "account_id",
                "canonical_contact_id",
                "provider_epoch",
                "raw_contact_locator",
                "data_row_locator",
                "role",
            ],
            unique = true,
        ),
    ],
)
internal data class AndroidProviderRowBindingEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "raw_contact_locator") val rawContactLocator: Long,
    @ColumnInfo(name = "binding_primary_id") val bindingPrimaryId: String,
    val role: String,
    @ColumnInfo(name = "canonical_value_id") val canonicalValueId: String,
    @ColumnInfo(name = "android_account_name") val androidAccountName: String,
    val kind: String,
    @ColumnInfo(name = "data_row_locator") val dataRowLocator: Long?,
    val state: String,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalContactId.isNotBlank())
        require(providerEpoch >= 0)
        require(rawContactLocator > 0)
        require(bindingPrimaryId.isNotBlank())
        require(role.isNotBlank())
        require(canonicalValueId.isNotBlank())
        require(androidAccountName.isNotBlank())
        require(kind.isNotBlank())
        require(dataRowLocator == null || dataRowLocator > 0)
        require(state == "PENDING" && dataRowLocator == null || state == "ATTACHED" && dataRowLocator != null)
    }

    override fun toString(): String =
        "AndroidProviderRowBindingEntity(REDACTED, providerEpoch=$providerEpoch, role=$role, state=$state)"
}

/** Provider-neutral Android baseline. The blob never contains inline photo bytes. */
@Entity(
    tableName = "android_projection_baselines",
    primaryKeys = ["account_id", "canonical_contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_contact_id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class AndroidProjectionBaselineEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    val fingerprint: String,
    @ColumnInfo(name = "encoded_snapshot", typeAffinity = ColumnInfo.BLOB)
    val encodedSnapshot: ByteArray,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalContactId.isNotBlank())
        require(SHA_256_HEX.matches(fingerprint))
        require(encodedSnapshot.size <= MAX_BASELINE_BYTES)
    }

    override fun toString(): String = "AndroidProjectionBaselineEntity(REDACTED)"

    private companion object {
        const val MAX_BASELINE_BYTES = 2 * 1_024 * 1_024
        val SHA_256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** Durable identity and observation state for one account-scoped Android Groups row. */
@Entity(
    tableName = "android_group_projection_ledger",
    primaryKeys = ["account_id", "canonical_group_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidProjectionAccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactGroupEntity::class,
            parentColumns = ["account_id", "id"],
            childColumns = ["account_id", "canonical_group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "provider_epoch", "group_row_locator"], unique = true),
        Index(value = ["account_id", "source_identity"], unique = true),
    ],
)
internal data class AndroidGroupProjectionLedgerEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_group_id") val canonicalGroupId: String,
    val revision: Long,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "group_row_locator") val groupRowLocator: Long?,
    @ColumnInfo(name = "provider_version") val providerVersion: Long? = null,
    @ColumnInfo(name = "source_identity") val sourceIdentity: String?,
    @ColumnInfo(name = "canonical_projection_fingerprint") val canonicalProjectionFingerprint: String?,
    @ColumnInfo(name = "android_baseline_fingerprint") val androidBaselineFingerprint: String?,
    @ColumnInfo(name = "pending_projection_fingerprint") val pendingProjectionFingerprint: String?,
    @ColumnInfo(name = "projection_state") val projectionState: String,
    @ColumnInfo(name = "ingestion_state") val ingestionState: String,
    @ColumnInfo(name = "tombstone_state") val tombstoneState: String,
    @ColumnInfo(name = "adoption_state") val adoptionState: String,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalGroupId.isNotBlank())
        require(revision >= 0)
        require(providerEpoch >= 0)
        require(groupRowLocator == null || groupRowLocator > 0)
        require(providerVersion == null || groupRowLocator != null)
        require(providerVersion == null || providerVersion >= 0)
        require(sourceIdentity == null || sourceIdentity.isNotBlank())
        require(projectionState in ANDROID_PROJECTION_STATES)
        require(ingestionState in ANDROID_INGESTION_STATES)
        require(tombstoneState in ANDROID_TOMBSTONE_STATES)
        require(adoptionState in ANDROID_ADOPTION_STATES)
        require((projectionState == "WRITE_PENDING") == (pendingProjectionFingerprint != null))
        require((adoptionState == "AWAITING_REMOTE_ID") == (sourceIdentity == null))
        require(adoptionState != "ADOPTED" || groupRowLocator != null)
        require(tombstoneState == "NONE" || projectionState == "DETACHED")
        listOfNotNull(
            canonicalProjectionFingerprint,
            androidBaselineFingerprint,
            pendingProjectionFingerprint,
        ).forEach { require(ANDROID_SHA_256_HEX.matches(it)) }
    }

    override fun toString(): String =
        "AndroidGroupProjectionLedgerEntity(REDACTED, revision=$revision, " +
            "projectionState=$projectionState, ingestionState=$ingestionState, " +
            "tombstoneState=$tombstoneState, adoptionState=$adoptionState)"
}

/**
 * Provider-neutral Groups-row baseline. [fingerprint] is the full encoded-blob integrity digest;
 * the ledger stores the separate semantic projection fingerprints.
 */
@Entity(
    tableName = "android_group_projection_baselines",
    primaryKeys = ["account_id", "canonical_group_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidGroupProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_group_id"],
            childColumns = ["account_id", "canonical_group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class AndroidGroupProjectionBaselineEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_group_id") val canonicalGroupId: String,
    val fingerprint: String,
    @ColumnInfo(name = "encoded_snapshot", typeAffinity = ColumnInfo.BLOB)
    val encodedSnapshot: ByteArray,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalGroupId.isNotBlank())
        require(ANDROID_SHA_256_HEX.matches(fingerprint))
        require(encodedSnapshot.size <= MAX_GROUP_BASELINE_BYTES)
    }

    override fun toString(): String = "AndroidGroupProjectionBaselineEntity(REDACTED)"

    private companion object {
        const val MAX_GROUP_BASELINE_BYTES = 128 * 1_024
    }
}

/** Contact-level Android group-membership observation state for the selected preferred email. */
@Entity(
    tableName = "android_group_membership_projection_ledger",
    primaryKeys = ["account_id", "canonical_contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_contact_id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "provider_epoch", "raw_contact_locator"], unique = true),
    ],
)
internal data class AndroidGroupMembershipProjectionLedgerEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    val revision: Long,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "raw_contact_locator") val rawContactLocator: Long?,
    @ColumnInfo(name = "preferred_email_value_id") val preferredEmailValueId: String?,
    @ColumnInfo(name = "canonical_projection_fingerprint") val canonicalProjectionFingerprint: String?,
    @ColumnInfo(name = "android_baseline_fingerprint") val androidBaselineFingerprint: String?,
    @ColumnInfo(name = "pending_projection_fingerprint") val pendingProjectionFingerprint: String?,
    @ColumnInfo(name = "projection_state") val projectionState: String,
    @ColumnInfo(name = "ingestion_state") val ingestionState: String,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalContactId.isNotBlank())
        require(revision >= 0)
        require(providerEpoch >= 0)
        require(rawContactLocator == null || rawContactLocator > 0)
        require(preferredEmailValueId == null || preferredEmailValueId.isNotBlank())
        require(projectionState in ANDROID_PROJECTION_STATES)
        require(ingestionState in ANDROID_INGESTION_STATES)
        require((projectionState == "WRITE_PENDING") == (pendingProjectionFingerprint != null))
        listOfNotNull(
            canonicalProjectionFingerprint,
            androidBaselineFingerprint,
            pendingProjectionFingerprint,
        ).forEach { require(ANDROID_SHA_256_HEX.matches(it)) }
    }

    override fun toString(): String =
        "AndroidGroupMembershipProjectionLedgerEntity(REDACTED, revision=$revision, " +
            "projectionState=$projectionState, ingestionState=$ingestionState)"
}

/**
 * Complete membership baseline, including meaningful empty-set and no-email states.
 * [fingerprint] covers the complete encoded blob, including replaceable locator mappings.
 */
@Entity(
    tableName = "android_group_membership_baselines",
    primaryKeys = ["account_id", "canonical_contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidGroupMembershipProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_contact_id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class AndroidGroupMembershipBaselineEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    val fingerprint: String,
    @ColumnInfo(name = "encoded_snapshot", typeAffinity = ColumnInfo.BLOB)
    val encodedSnapshot: ByteArray,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalContactId.isNotBlank())
        require(ANDROID_SHA_256_HEX.matches(fingerprint))
        require(encodedSnapshot.size <= MAX_MEMBERSHIP_BASELINE_BYTES)
    }

    override fun toString(): String = "AndroidGroupMembershipBaselineEntity(REDACTED)"

    private companion object {
        const val MAX_MEMBERSHIP_BASELINE_BYTES = 4 * 1_024 * 1_024
    }
}

/**
 * Durable proof of the exact command that advanced one membership observation transaction.
 * The fingerprint is domain-separated and covers identifiers, revisions, locators, and the
 * complete expected group vectors; no contact payload is stored here.
 */
@Entity(
    tableName = "android_group_membership_commit_receipts",
    primaryKeys = ["account_id", "canonical_contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidGroupMembershipProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_contact_id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class AndroidGroupMembershipCommitReceiptEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "expected_account_revision") val expectedAccountRevision: Long,
    @ColumnInfo(name = "expected_membership_ledger_revision") val expectedMembershipLedgerRevision: Long,
    @ColumnInfo(name = "committed_account_revision") val committedAccountRevision: Long,
    @ColumnInfo(name = "committed_membership_ledger_revision") val committedMembershipLedgerRevision: Long,
    @ColumnInfo(name = "command_fingerprint") val commandFingerprint: String,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalContactId.isNotBlank())
        require(providerEpoch >= 0)
        require(expectedAccountRevision >= 0)
        require(expectedMembershipLedgerRevision >= 0)
        require(committedAccountRevision == Math.incrementExact(expectedAccountRevision))
        require(committedMembershipLedgerRevision == Math.incrementExact(expectedMembershipLedgerRevision))
        require(ANDROID_SHA_256_HEX.matches(commandFingerprint))
    }

    override fun toString(): String = "AndroidGroupMembershipCommitReceiptEntity(REDACTED)"
}

/** Exact replay proof for one Android Groups-row observation transaction. */
@Entity(
    tableName = "android_group_observation_commit_receipts",
    primaryKeys = ["account_id", "canonical_group_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidGroupProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_group_id"],
            childColumns = ["account_id", "canonical_group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class AndroidGroupObservationCommitReceiptEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_group_id") val canonicalGroupId: String,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "expected_account_revision") val expectedAccountRevision: Long,
    @ColumnInfo(name = "expected_group_ledger_revision") val expectedGroupLedgerRevision: Long,
    @ColumnInfo(name = "committed_account_revision") val committedAccountRevision: Long,
    @ColumnInfo(name = "committed_group_ledger_revision") val committedGroupLedgerRevision: Long,
    @ColumnInfo(name = "command_fingerprint") val commandFingerprint: String,
    @ColumnInfo(name = "post_state_fingerprint") val postStateFingerprint: String,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalGroupId.isNotBlank())
        require(providerEpoch >= 0)
        require(expectedAccountRevision >= 0)
        require(expectedGroupLedgerRevision >= 0)
        require(committedAccountRevision == Math.incrementExact(expectedAccountRevision))
        require(committedGroupLedgerRevision == Math.incrementExact(expectedGroupLedgerRevision))
        require(ANDROID_SHA_256_HEX.matches(commandFingerprint))
        require(ANDROID_SHA_256_HEX.matches(postStateFingerprint))
    }

    override fun toString(): String = "AndroidGroupObservationCommitReceiptEntity(REDACTED)"
}

/** Exact replay proof for one unified contact and same-snapshot membership commit. */
@Entity(
    tableName = "android_unified_observation_commit_receipts",
    primaryKeys = ["account_id", "canonical_contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_contact_id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class AndroidUnifiedObservationCommitReceiptEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "raw_contact_locator") val rawContactLocator: Long,
    @ColumnInfo(name = "raw_contact_version") val rawContactVersion: Long,
    @ColumnInfo(name = "expected_account_revision") val expectedAccountRevision: Long,
    @ColumnInfo(name = "committed_account_revision") val committedAccountRevision: Long,
    @ColumnInfo(name = "committed_contact_ledger_revision") val committedContactLedgerRevision: Long,
    @ColumnInfo(name = "committed_membership_ledger_revision") val committedMembershipLedgerRevision: Long,
    @ColumnInfo(name = "command_fingerprint") val commandFingerprint: String,
    @ColumnInfo(name = "post_state_fingerprint") val postStateFingerprint: String,
) {
    init {
        require(accountId.isNotBlank() && canonicalContactId.isNotBlank())
        require(providerEpoch >= 0 && rawContactLocator > 0 && rawContactVersion >= 0)
        require(expectedAccountRevision >= 0)
        require(committedAccountRevision == Math.incrementExact(expectedAccountRevision))
        require(committedContactLedgerRevision >= 0 && committedMembershipLedgerRevision >= 0)
        require(ANDROID_SHA_256_HEX.matches(commandFingerprint))
        require(ANDROID_SHA_256_HEX.matches(postStateFingerprint))
    }

    override fun toString(): String = "AndroidUnifiedObservationCommitReceiptEntity(REDACTED)"
}

/**
 * Crash-resumption journal for one exact Android Groups provider mutation.
 *
 * PREPARED is committed before touching ContactsProvider. COMMITTED records the provider outcome
 * before the group ledger is advanced. The row deliberately contains claims and fingerprints,
 * never contact values, credentials, or a provider-returned title.
 */
@Entity(
    tableName = "android_group_provider_write_journal",
    primaryKeys = ["account_id", "canonical_group_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidGroupProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_group_id"],
            childColumns = ["account_id", "canonical_group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["account_id", "state"])],
)
internal data class AndroidGroupProviderWriteJournalEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_group_id") val canonicalGroupId: String,
    @ColumnInfo(name = "android_account_name") val androidAccountName: String,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    val operation: String,
    val state: String,
    @ColumnInfo(name = "expected_account_revision") val expectedAccountRevision: Long,
    @ColumnInfo(name = "expected_canonical_group_revision") val expectedCanonicalGroupRevision: Long,
    @ColumnInfo(name = "expected_group_ledger_revision") val expectedGroupLedgerRevision: Long,
    @ColumnInfo(name = "expected_group_row_locator") val expectedGroupRowLocator: Long?,
    @ColumnInfo(name = "expected_provider_version") val expectedProviderVersion: Long?,
    @ColumnInfo(name = "expected_source_identity") val expectedSourceIdentity: String?,
    @ColumnInfo(name = "source_identity_after_write") val sourceIdentityAfterWrite: String?,
    @ColumnInfo(name = "expected_deleted") val expectedDeleted: Boolean,
    @ColumnInfo(name = "desired_semantic_fingerprint") val desiredSemanticFingerprint: String?,
    @ColumnInfo(name = "desired_snapshot_integrity_fingerprint") val desiredSnapshotIntegrityFingerprint: String?,
    @ColumnInfo(name = "desired_snapshot", typeAffinity = ColumnInfo.BLOB) val desiredSnapshot: ByteArray?,
    @ColumnInfo(name = "command_fingerprint") val commandFingerprint: String,
    @ColumnInfo(name = "result_group_row_locator") val resultGroupRowLocator: Long?,
    @ColumnInfo(name = "result_provider_version") val resultProviderVersion: Long?,
    @ColumnInfo(name = "result_source_identity") val resultSourceIdentity: String?,
    @ColumnInfo(name = "result_deleted") val resultDeleted: Boolean?,
    @ColumnInfo(name = "result_provider_state_fingerprint") val resultProviderStateFingerprint: String?,
    @ColumnInfo(name = "completed_account_revision") val completedAccountRevision: Long?,
    @ColumnInfo(name = "completed_group_ledger_revision") val completedGroupLedgerRevision: Long?,
) {
    init {
        require(accountId.isNotBlank())
        require(canonicalGroupId.isNotBlank())
        require(androidAccountName.isNotBlank())
        require(providerEpoch >= 0)
        require(operation in ANDROID_GROUP_PROVIDER_OPERATIONS)
        require(state in ANDROID_GROUP_PROVIDER_JOURNAL_STATES)
        require(expectedAccountRevision >= 0)
        require(expectedCanonicalGroupRevision >= 0)
        require(expectedGroupLedgerRevision >= 0)
        require((expectedGroupRowLocator == null) == (expectedProviderVersion == null))
        require(expectedGroupRowLocator == null || expectedGroupRowLocator > 0)
        require(expectedProviderVersion == null || expectedProviderVersion >= 0)
        require(expectedSourceIdentity == null || expectedSourceIdentity.isNotBlank())
        require(sourceIdentityAfterWrite == null || sourceIdentityAfterWrite.isNotBlank())
        require(ANDROID_SHA_256_HEX.matches(commandFingerprint))
        require(
            listOf(desiredSemanticFingerprint, desiredSnapshotIntegrityFingerprint, desiredSnapshot)
                .all { it == null } ||
                listOf(desiredSemanticFingerprint, desiredSnapshotIntegrityFingerprint, desiredSnapshot)
                    .all { it != null },
        )
        listOfNotNull(desiredSemanticFingerprint, desiredSnapshotIntegrityFingerprint)
            .forEach { require(ANDROID_SHA_256_HEX.matches(it)) }
        require(desiredSnapshot == null || desiredSnapshot.size <= 128 * 1_024)
        if (state != "COMMITTED") {
            require(resultGroupRowLocator == null)
            require(resultProviderVersion == null)
            require(resultSourceIdentity == null)
            require(resultDeleted == null)
            require(resultProviderStateFingerprint == null)
            require(completedAccountRevision == null)
            require(completedGroupLedgerRevision == null)
        } else if (resultDeleted == true) {
            require(operation == "DELETE")
            require(resultGroupRowLocator == null)
            require(resultProviderVersion == null)
            require(resultSourceIdentity == null)
        } else {
            require(operation != "DELETE")
            require(resultDeleted == false)
            require(resultGroupRowLocator != null && resultGroupRowLocator > 0)
            require(resultProviderVersion != null && resultProviderVersion >= 0)
            require(resultSourceIdentity == null || resultSourceIdentity.isNotBlank())
        }
        if (state == "COMMITTED") require(ANDROID_SHA_256_HEX.matches(requireNotNull(resultProviderStateFingerprint)))
        require((completedAccountRevision == null) == (completedGroupLedgerRevision == null))
        if (completedAccountRevision != null) {
            require(completedAccountRevision == Math.incrementExact(expectedAccountRevision))
            require(completedGroupLedgerRevision == Math.incrementExact(expectedGroupLedgerRevision))
        }
    }

    override fun toString(): String =
        "AndroidGroupProviderWriteJournalEntity(REDACTED, operation=$operation, state=$state)"
}

/** Crash-resumption proof for one full-resolution RawContacts/display_photo stream. */
@Entity(
    tableName = "android_photo_provider_write_journal",
    primaryKeys = ["account_id", "canonical_contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidProjectionLedgerEntity::class,
            parentColumns = ["account_id", "canonical_contact_id"],
            childColumns = ["account_id", "canonical_contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["account_id", "state"])],
)
internal data class AndroidPhotoProviderWriteJournalEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    @ColumnInfo(name = "android_account_name") val androidAccountName: String,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "raw_contact_locator") val rawContactLocator: Long,
    @ColumnInfo(name = "expected_raw_contact_version") val expectedRawContactVersion: Long,
    @ColumnInfo(name = "expected_source_identity") val expectedSourceIdentity: String,
    @ColumnInfo(name = "expected_canonical_revision") val expectedCanonicalRevision: Long,
    @ColumnInfo(name = "expected_ledger_revision") val expectedLedgerRevision: Long,
    @ColumnInfo(name = "canonical_value_id") val canonicalValueId: String,
    @ColumnInfo(name = "binary_reference") val binaryReference: String,
    @ColumnInfo(name = "content_size") val contentSize: Long,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    val state: String,
    @ColumnInfo(name = "result_raw_contact_version") val resultRawContactVersion: Long?,
) {
    init {
        require(accountId.isNotBlank() && canonicalContactId.isNotBlank() && androidAccountName.isNotBlank())
        require(providerEpoch >= 0 && rawContactLocator > 0 && expectedRawContactVersion >= 0)
        require(expectedSourceIdentity.isNotBlank())
        require(expectedCanonicalRevision >= 0 && expectedLedgerRevision >= 0)
        require(canonicalValueId.isNotBlank() && binaryReference.isNotBlank())
        require(contentSize in 1..10L * 1_024 * 1_024)
        require(ANDROID_SHA_256_HEX.matches(contentSha256))
        require(state in setOf("PREPARED", "COMMITTED", "REPAIR_REQUIRED"))
        require((state == "COMMITTED") == (resultRawContactVersion != null))
        require(resultRawContactVersion == null || resultRawContactVersion >= expectedRawContactVersion)
    }

    override fun toString(): String =
        "AndroidPhotoProviderWriteJournalEntity(REDACTED, state=$state, contentSize=$contentSize)"
}

private val ANDROID_SHA_256_HEX = Regex("[0-9a-f]{64}")
private val ANDROID_PROJECTION_STATES = setOf("DETACHED", "WRITE_PENDING", "CLEAN", "REPAIR_REQUIRED")
private val ANDROID_INGESTION_STATES = setOf("NONE", "BASELINED", "CANONICAL_DELTA_COMMITTED")
private val ANDROID_TOMBSTONE_STATES = setOf("NONE", "CANONICAL_COMMITTED", "REMOTE_CONVERGED")
private val ANDROID_ADOPTION_STATES = setOf("AWAITING_REMOTE_ID", "SOURCE_ID_PENDING", "ADOPTED")
private val ANDROID_GROUP_PROVIDER_OPERATIONS = setOf("CREATE", "ADOPT", "UPDATE", "ACKNOWLEDGE", "DELETE")
private val ANDROID_GROUP_PROVIDER_JOURNAL_STATES = setOf("PREPARED", "COMMITTED", "REPAIR_REQUIRED")

internal enum class AggregateType { CONTACT, GROUP }
internal enum class MutationOperation { UPSERT, DELETE, ASSIGNMENTS }
internal enum class DurableMutationState { PENDING, IN_FLIGHT, ACKNOWLEDGED, ACTION_REQUIRED }
