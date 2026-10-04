package com.patmanak.contako.data.sync

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionFingerprint
import com.patmanak.contako.data.android.AndroidProjectionLedgerSnapshot
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidCompleteGroupCatalog
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipLocatorMapping
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipObservationDecoder
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidTrustedGroupBinding
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.AndroidContactsProviderWriter
import com.patmanak.contako.data.android.provider.AndroidDurablePhotoCapture
import com.patmanak.contako.data.android.provider.AndroidExpectedSourceIdentity
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidProviderAcknowledgementResult
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouter
import com.patmanak.contako.data.android.provider.AndroidProviderPhotoReferenceEncoder
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouterException
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouterFailure
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodec
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodecException
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.android.provider.RoomAndroidProviderIdentityResolver
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomAndroidCanonicalMutationStore
import com.patmanak.contako.data.local.RoomAndroidGroupMembershipObservationCommand
import com.patmanak.contako.data.local.RoomAndroidGroupMembershipObservationCommitter
import com.patmanak.contako.data.local.RoomAndroidMembershipLedgerStaleObserver
import com.patmanak.contako.data.local.RoomAndroidUnifiedObservationCommand
import com.patmanak.contako.data.local.RoomAndroidUnifiedObservationCommitter
import com.patmanak.contako.data.local.RoomAndroidUnifiedCommitRepairObserver
import com.patmanak.contako.data.local.RoomAndroidUnifiedCommitReplanObserver
import com.patmanak.contako.data.local.RoomAndroidUnifiedObservationResult
import com.patmanak.contako.data.local.RoomAndroidObservationCommitter
import com.patmanak.contako.data.local.AndroidCanonicalContactRejectionObserver
import com.patmanak.contako.data.local.RoomAndroidCreatedContactCommitter
import com.patmanak.contako.data.local.RoomAndroidCreatedRawContactAuthorization
import com.patmanak.contako.data.local.RoomAndroidCreatedMembershipObservation
import com.patmanak.contako.data.local.RoomAndroidCreatedUnifiedObservationResult
import com.patmanak.contako.data.local.RoomAndroidDeletedUnifiedObservationResult
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.RoomExpectedAndroidGroupBinding
import com.patmanak.contako.data.local.RoomExpectedContactGroupState
import com.patmanak.contako.data.local.toDomain
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal interface AndroidProductionContactObservationCoordinator {
    fun acceptGroupCatalog(context: AndroidInteroperabilityContext, pages: List<AndroidOwnedGroupRowPage>)

    suspend fun ingest(
        context: AndroidInteroperabilityContext,
        page: AndroidStableRawContactObservationPage,
    ): AndroidBoundedPageResult

    companion object {
        val FAIL_CLOSED = object : AndroidProductionContactObservationCoordinator {
            override fun acceptGroupCatalog(
                context: AndroidInteroperabilityContext,
                pages: List<AndroidOwnedGroupRowPage>,
            ) = Unit

            override suspend fun ingest(
                context: AndroidInteroperabilityContext,
                page: AndroidStableRawContactObservationPage,
            ) = AndroidBoundedPageResult.RepairRequired
        }
    }
}

internal fun interface AndroidContactObservationAcknowledger {
    suspend fun acknowledge(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        version: Long,
        canonicalContactIdClaim: String?,
        sourceIdentity: String?,
        canonicalContactIdClaimAfterWrite: String,
    ): AndroidProviderAcknowledgementResult
}

internal enum class AndroidContactObservationPath { CREATED, DELETED, EXISTING, INVALID }

/** Closed existing-contact planning causes; no row, identity, value, or exception crosses it. */
internal enum class AndroidExistingContactPlanRepairReason {
    LIFECYCLE_SHAPE,
    BINARY_CONTACT_ROW,
    UNSUPPORTED_PROVIDER_CUSTOM_FIELD,
    UNSUPPORTED_PROVIDER_IM,
    UNSUPPORTED_PROVIDER_SIP,
    UNSUPPORTED_PROVIDER_IDENTITY,
    UNSUPPORTED_PROVIDER_ANDROID_MIME,
    UNSUPPORTED_PROVIDER_VENDOR_MIME,
    UNSUPPORTED_PROVIDER_MULTIPLE_FAMILIES,
    ACCOUNT_MISSING,
    CONTACT_LEDGER_MISSING,
    CONTACT_LEDGER_LOCATOR_INDEX_MISMATCH,
    CONTACT_LEDGER_SOURCE_IDENTITY_MISMATCH,
    CONTACT_LEDGER_PROVIDER_EPOCH_MISMATCH,
    CONTACT_LEDGER_RAW_LOCATOR_MISMATCH,
    CONTACT_LEDGER_ADOPTION_STATE_MISMATCH,
    CONTACT_LEDGER_TOMBSTONE_STATE_MISMATCH,
    CANONICAL_CONTACT_MISSING,
    CANONICAL_REMOTE_IDENTITY_MISMATCH,
    CONTACT_BASELINE_NO_PHOTO_RECOVERY,
    CONTACT_BASELINE_PHOTO_JOURNAL_MISSING,
    CONTACT_BASELINE_PHOTO_RECOVERY_STATE_MISMATCH,
    CONTACT_BASELINE_PHOTO_PROOF_FAILED,
    CONTACT_BASELINE_PHOTO_SOURCE_UNAVAILABLE,
    CONTACT_BASELINE_PHOTO_SOURCE_SIZE_MISMATCH,
    CONTACT_BASELINE_PHOTO_SOURCE_DIGEST_MISMATCH,
    CONTACT_BASELINE_PHOTO_READBACK_MISMATCH,
    CONTACT_BASELINE_VALUES_DIFFER,
    CONTACT_BASELINE_MEMBERSHIPS_DIFFER,
    MEMBERSHIP_LEDGER_MISSING,
    GROUP_LEDGER_BINDING_INCOMPLETE,
    MEMBERSHIP_ROW_LOCATOR_INVALID,
    MEMBERSHIP_GROUP_BINDING_MISSING,
    MEMBERSHIP_CATALOG_MISMATCH,
    REQUIRED_GROUP_LEDGER_MISSING,
    REQUIRED_GROUP_BINDING_INCOMPLETE,
    MIME_ROUTING_FAILURE,
    ROW_CODEC_FAILURE,
    INVALID_ARGUMENT,
}

private enum class AndroidUnsupportedProviderRowFamily {
    CUSTOM_FIELD,
    IM,
    SIP,
    IDENTITY,
    ANDROID_MIME,
    VENDOR_MIME,
}

/** Payload-free classification for an already account-scoped provider route. */
internal fun existingContactUnsupportedRowsRepairReason(
    route: com.patmanak.contako.data.android.provider.AndroidProviderMimeRoute,
): AndroidExistingContactPlanRepairReason? {
    val families = route.unsupportedOwnedRows.rows.mapTo(linkedSetOf()) { row ->
        when (row.mimeType) {
            "vnd.android.cursor.item/contact_user_defined_field" ->
                AndroidUnsupportedProviderRowFamily.CUSTOM_FIELD
            "vnd.android.cursor.item/im" -> AndroidUnsupportedProviderRowFamily.IM
            "vnd.android.cursor.item/sip_address" -> AndroidUnsupportedProviderRowFamily.SIP
            "vnd.android.cursor.item/identity" -> AndroidUnsupportedProviderRowFamily.IDENTITY
            else -> if (row.mimeType.startsWith("vnd.android.cursor.item/")) {
                AndroidUnsupportedProviderRowFamily.ANDROID_MIME
            } else {
                AndroidUnsupportedProviderRowFamily.VENDOR_MIME
            }
        }
    }
    if (families.isEmpty()) return null
    if (families.size > 1) {
        return AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_MULTIPLE_FAMILIES
    }
    return when (families.single()) {
        AndroidUnsupportedProviderRowFamily.CUSTOM_FIELD ->
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_CUSTOM_FIELD
        AndroidUnsupportedProviderRowFamily.IM ->
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_IM
        AndroidUnsupportedProviderRowFamily.SIP ->
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_SIP
        AndroidUnsupportedProviderRowFamily.IDENTITY ->
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_IDENTITY
        AndroidUnsupportedProviderRowFamily.ANDROID_MIME ->
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_ANDROID_MIME
        AndroidUnsupportedProviderRowFamily.VENDOR_MIME ->
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_VENDOR_MIME
    }
}

/**
 * Reuses the durable baseline reference only for an unchanged standard provider photo.
 * A dirty, new, duplicated, or non-photo binary row still requires an explicit repair path.
 */
internal fun existingContactRetainedPhotoReference(
    route: com.patmanak.contako.data.android.provider.AndroidProviderMimeRoute,
    baseline: AndroidContactSnapshot,
    rawContactDirty: Boolean,
): String? {
    val binaryRows = route.contactRows.rows.filter { it.binarySlot != null }
    if (binaryRows.isEmpty()) return null
    if (rawContactDirty || binaryRows.size != 1 || !binaryRows.single().isStandardPhoto) return null
    return baseline.rows.singleOrNull { it.kind == AndroidRowKind.PHOTO }
        ?.binaryReference
        ?.takeIf(String::isNotBlank)
}

internal fun interface AndroidExistingContactPlanRepairObserver {
    fun onRepairRequired(reason: AndroidExistingContactPlanRepairReason)
    fun onRowCodecFailure(category: com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure) {}
    fun onBaselineMismatch(detail: AndroidInitialBaselineMismatch) {}
}

internal fun createdLifecycleRouteRequiresRepair(
    hasUnsupportedBinaryRow: Boolean,
    hasUnsupportedOwnedRow: Boolean,
): Boolean = hasUnsupportedBinaryRow || hasUnsupportedOwnedRow

/** Only payload-local failures may be quarantined; scope, ownership and catalog proofs stop the pass. */
internal fun isContactLocalPlanFailure(
    reason: AndroidExistingContactPlanRepairReason,
    codecFailure: AndroidProviderRowCodecFailure? = null,
): Boolean =
    reason == AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE && isContactLocalCodecFailure(codecFailure) ||
    reason in ISOLATED_PHOTO_PROOF_FAILURES || reason in setOf(
        AndroidExistingContactPlanRepairReason.BINARY_CONTACT_ROW,
        AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_CUSTOM_FIELD,
        AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_IM,
        AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_SIP,
        AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_IDENTITY,
        AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_ANDROID_MIME,
        AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_VENDOR_MIME,
        AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_MULTIPLE_FAMILIES,
        AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_VALUES_DIFFER,
    )

internal fun isContactLocalCodecFailure(reason: AndroidProviderRowCodecFailure?): Boolean = reason in setOf(
    AndroidProviderRowCodecFailure.DUPLICATE_SINGLETON,
    AndroidProviderRowCodecFailure.UNSUPPORTED_ROW_KIND,
    AndroidProviderRowCodecFailure.MALFORMED_ROW,
    AndroidProviderRowCodecFailure.PHOTO_BINARY_MISSING,
    AndroidProviderRowCodecFailure.UNEXPECTED_BINARY,
    AndroidProviderRowCodecFailure.MALFORMED_TYPE,
    AndroidProviderRowCodecFailure.MALFORMED_DATE,
    AndroidProviderRowCodecFailure.MALFORMED_ORDER,
    AndroidProviderRowCodecFailure.PHOTO_CAPTURE_FAILED,
    AndroidProviderRowCodecFailure.BOUND_EXCEEDED,
)

/** The returned reference is persisted with the canonical photo and outbox in the creation transaction. */
internal fun createdContactPhotoCapture() = AndroidDurablePhotoCapture { _, _, _, bytes ->
    requireNotNull(AndroidProviderPhotoReferenceEncoder.encode(bytes)) { "PHOTO_CAPTURE_UNSUPPORTED" }
}

internal fun com.patmanak.contako.data.android.provider.AndroidOwnedRawContact.observationPath() = when {
    deleted && canonicalContactIdClaim != null && sourceIdentity != null -> AndroidContactObservationPath.DELETED
    deleted -> AndroidContactObservationPath.INVALID
    canonicalContactIdClaim == null && sourceIdentity == null -> AndroidContactObservationPath.CREATED
    canonicalContactIdClaim != null && sourceIdentity != null -> AndroidContactObservationPath.EXISTING
    else -> AndroidContactObservationPath.INVALID
}

/** Production contact path. Unsupported data stays pending without stranding unrelated contacts. */
internal class ProductionAndroidContactObservationCoordinator(
    private val database: ContakoDatabase,
    private val repository: RoomContactRepository,
    private val acknowledger: AndroidContactObservationAcknowledger,
    private val mapper: CanonicalAndroidContactMapper = CanonicalAndroidContactMapper(),
    private val contactIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val actionRequiredObserver: AndroidIngestActionRequiredObserver =
        AndroidIngestActionRequiredObserver { },
    private val replanObserver: AndroidIngestReplanObserver = AndroidIngestReplanObserver { },
    private val existingPlanRepairObserver: AndroidExistingContactPlanRepairObserver =
        AndroidExistingContactPlanRepairObserver { },
    private val existingCommitRepairObserver: RoomAndroidUnifiedCommitRepairObserver =
        RoomAndroidUnifiedCommitRepairObserver { },
    private val existingCommitReplanObserver: RoomAndroidUnifiedCommitReplanObserver =
        RoomAndroidUnifiedCommitReplanObserver { },
    private val membershipLedgerStaleObserver: RoomAndroidMembershipLedgerStaleObserver =
        RoomAndroidMembershipLedgerStaleObserver { },
    private val canonicalContactRejectionObserver: AndroidCanonicalContactRejectionObserver =
        AndroidCanonicalContactRejectionObserver { },
    private val interruptedPhotoProof: (
        AndroidProviderAccountName, AndroidStableRawContactObservation,
        com.patmanak.contako.data.local.AndroidPhotoProviderWriteJournalEntity,
    ) -> AndroidExistingContactPlanRepairReason? = { _, _, _ -> AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_PROOF_FAILED },
) : AndroidProductionContactObservationCoordinator {
    @Volatile private var catalogSession: CatalogSession? = null

    override fun acceptGroupCatalog(
        context: AndroidInteroperabilityContext,
        pages: List<AndroidOwnedGroupRowPage>,
    ) {
        catalogSession = CatalogSession(
            context.account,
            context.androidAccountName,
            context.providerEpoch,
            AndroidCompleteGroupCatalog.fromExhaustivePages(context.account, context.providerEpoch, pages),
        )
    }

    override suspend fun ingest(
        context: AndroidInteroperabilityContext,
        page: AndroidStableRawContactObservationPage,
    ): AndroidBoundedPageResult {
        val catalog = catalogSession?.takeIf {
            it.account == context.account && it.androidAccountName == context.androidAccountName &&
                it.providerEpoch == context.providerEpoch
        }?.catalog ?: return replan(AndroidIngestReplanReason.CONTACT_CATALOG_STALE)
        var incompleteContacts = false
        for (observation in page.observations) {
            val lifecycle = ingestLifecycleObservation(context, observation)
            if (lifecycle != null) {
                if (lifecycle == AndroidBoundedPageResult.PartiallyApplied) {
                    incompleteContacts = true
                    continue
                }
                if (lifecycle != AndroidBoundedPageResult.Applied) return lifecycle
                continue
            }
            // ContactsProvider's DIRTY flag is the editable-account handoff contract. Planning
            // and recommitting a clean projected row adds no local intent; it also races the
            // projector's provider-version updates and can leave every no-change pass waiting to
            // retry. Projection independently verifies and repairs clean provider state.
            if (!observation.rawContact.dirty) continue
            val plan = when (val result = plan(context, catalog, observation)) {
                is ContactPlanResult.Ready -> result.command
                ContactPlanResult.Replan ->
                    return replan(AndroidIngestReplanReason.CONTACT_EXISTING_PLAN_STALE)
                is ContactPlanResult.Repair -> {
                    runCatching { existingPlanRepairObserver.onRepairRequired(result.reason) }
                    val failure = repair(AndroidIngestActionRequiredReason.CONTACT_EXISTING_PLAN)
                    if (isContactLocalPlanFailure(result.reason, result.codecFailure)) {
                        // No intent was committed or acknowledged for this row. Keep its
                        // journal/baseline/DIRTY state intact, but do not strand later rows.
                        incompleteContacts = true
                        continue
                    }
                    return failure
                }
                ContactPlanResult.LocalFailure -> return AndroidBoundedPageResult.LocalPersistenceFailure
            }
            val unified = RoomAndroidUnifiedObservationCommitter(
                database,
                RoomAndroidObservationCommitter(
                    context.account,
                    RoomAndroidProjectionLedger(database),
                    RoomAndroidCanonicalMutationStore(database, context.account, repository),
                    canonicalContactRejectionObserver,
                ),
                RoomAndroidGroupMembershipObservationCommitter(
                    database,
                    repository,
                    membershipLedgerStaleObserver = membershipLedgerStaleObserver,
                ),
                existingCommitRepairObserver,
                existingCommitReplanObserver,
            )
            when (unified.commit(plan)) {
                is RoomAndroidUnifiedObservationResult.Applied,
                is RoomAndroidUnifiedObservationResult.AlreadyCommitted,
                -> Unit
                RoomAndroidUnifiedObservationResult.ReplanRequired ->
                    return replan(AndroidIngestReplanReason.CONTACT_EXISTING_COMMIT_STALE)
                RoomAndroidUnifiedObservationResult.RepairRequired ->
                    return repair(AndroidIngestActionRequiredReason.CONTACT_EXISTING_COMMIT)
            }
            if (observation.rawContact.dirty) {
                val authorization = currentAcknowledgement(context, observation, plan)
                    ?: return replan(AndroidIngestReplanReason.CONTACT_ACK_AUTHORIZATION_STALE)
                when (acknowledger.acknowledge(
                    AndroidProviderAccountName(context.androidAccountName),
                    observation.rawContact.rawContactId,
                    observation.rawContact.version,
                    plan.contactLedger.canonicalContactId,
                    authorization,
                    plan.contactLedger.canonicalContactId,
                )) {
                    AndroidProviderAcknowledgementResult.Acknowledged -> Unit
                    AndroidProviderAcknowledgementResult.Stale ->
                        return replan(AndroidIngestReplanReason.CONTACT_PROVIDER_ACK_STALE)
                }
            }
        }
        return if (incompleteContacts) AndroidBoundedPageResult.PartiallyApplied else AndroidBoundedPageResult.Applied
    }

    private suspend fun ingestLifecycleObservation(
        context: AndroidInteroperabilityContext,
        observation: AndroidStableRawContactObservation,
    ): AndroidBoundedPageResult? {
        val raw = observation.rawContact
        val path = raw.observationPath()
        if (path == AndroidContactObservationPath.EXISTING) return null
        if (path == AndroidContactObservationPath.INVALID) {
            return repair(AndroidIngestActionRequiredReason.CONTACT_INVALID_IDENTITY_SHAPE)
        }
        if (path == AndroidContactObservationPath.DELETED && observation.dataRows.isNotEmpty()) {
            return repair(AndroidIngestActionRequiredReason.CONTACT_DELETED_WITH_DATA)
        }
        val route = try {
            AndroidProviderMimeRouter().route(raw.rawContactId, observation.dataRows)
        } catch (_: AndroidProviderMimeRouterException) {
            // Routing must prove complete ownership/cardinality before payload isolation.
            return repair(AndroidIngestActionRequiredReason.CONTACT_MIME_ROUTING)
        }
        if (observation.dataRows.any { it.binarySlot != null && !it.isStandardPhoto }) {
            return contactLocalRepair(AndroidIngestActionRequiredReason.CONTACT_BINARY_ROW)
        }
        if (createdLifecycleRouteRequiresRepair(
                hasUnsupportedBinaryRow = false,
                hasUnsupportedOwnedRow = route.unsupportedOwnedRows.rows.isNotEmpty(),
            )) {
            return contactLocalRepair(AndroidIngestActionRequiredReason.CONTACT_UNSUPPORTED_OWNED_MIME)
        }
        val unified = unified(context)
        val authorization = RoomAndroidCreatedRawContactAuthorization(
            context.account.value, context.providerEpoch, raw.rawContactId, raw.version,
            raw.canonicalContactIdClaim, raw.sourceIdentity, raw.deleted, raw.dirty,
        )
        var canonicalId: String
        when {
            path == AndroidContactObservationPath.CREATED -> {
                canonicalId = contactIdFactory()
                val result = try {
                    unified.commitCreated(
                        canonicalId,
                        authorization,
                        RoomAndroidCreatedContactCommitter(
                            database,
                            context.account,
                            RoomAndroidProjectionLedger(database),
                            RoomAndroidCanonicalMutationStore(database, context.account, repository),
                            mapper,
                        ),
                        RoomAndroidCreatedMembershipObservation(
                            catalogFor(context),
                            route.groupMembershipRows,
                            observation.dataRows,
                        ),
                    ) {
                        AndroidProviderRowCodec(
                            RoomAndroidProviderIdentityResolver(database, context.account, context.providerEpoch),
                            createdContactPhotoCapture(),
                        ).decode(
                            AndroidProviderAccountName(context.androidAccountName),
                            canonicalId,
                            raw.rawContactId,
                            route.contactRows.rows,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SQLiteException) {
                    return AndroidBoundedPageResult.LocalPersistenceFailure
                } catch (failure: AndroidProviderRowCodecException) {
                    return if (isContactLocalCodecFailure(failure.category)) {
                        contactLocalRepair(AndroidIngestActionRequiredReason.CONTACT_CREATED_ROW_CODEC)
                    } else repair(AndroidIngestActionRequiredReason.CONTACT_CREATED_ROW_CODEC)
                } catch (_: IllegalArgumentException) {
                    return repair(AndroidIngestActionRequiredReason.CONTACT_CREATED_ARGUMENT)
                }
                when (result) {
                    is RoomAndroidCreatedUnifiedObservationResult.Applied -> Unit
                    RoomAndroidCreatedUnifiedObservationResult.Replayed -> {
                        canonicalId = database.androidProjectionLedgerDao().getByRawContactLocator(
                            context.account.value, context.providerEpoch, raw.rawContactId,
                        )?.canonicalContactId
                            ?: return repair(AndroidIngestActionRequiredReason.CONTACT_CREATED_REPLAY_LEDGER)
                    }
                    RoomAndroidCreatedUnifiedObservationResult.ReplanRequired ->
                        return replan(AndroidIngestReplanReason.CONTACT_CREATED_COMMIT_STALE)
                    RoomAndroidCreatedUnifiedObservationResult.RepairRequired ->
                        return repair(AndroidIngestActionRequiredReason.CONTACT_CREATED_COMMIT)
                    RoomAndroidCreatedUnifiedObservationResult.RejectedPayload ->
                        return contactLocalRepair(AndroidIngestActionRequiredReason.CONTACT_CREATED_COMMIT)
                }
            }
            else -> {
                canonicalId = raw.canonicalContactIdClaim
                    ?: return repair(AndroidIngestActionRequiredReason.CONTACT_DELETED_IDENTITY)
                when (unified.commitDeleted(authorization)) {
                    is RoomAndroidDeletedUnifiedObservationResult.Applied,
                    RoomAndroidDeletedUnifiedObservationResult.Replayed,
                    -> Unit
                    RoomAndroidDeletedUnifiedObservationResult.ReplanRequired ->
                        return replan(AndroidIngestReplanReason.CONTACT_DELETED_COMMIT_STALE)
                    RoomAndroidDeletedUnifiedObservationResult.RepairRequired ->
                        return repair(AndroidIngestActionRequiredReason.CONTACT_DELETED_COMMIT)
                }
            }
        }
        if (!raw.dirty) return AndroidBoundedPageResult.Applied
        val durable = durableLifecycleAcknowledgement(context, raw, canonicalId)
            ?: return replan(AndroidIngestReplanReason.CONTACT_ACK_AUTHORIZATION_STALE)
        return when (acknowledger.acknowledge(
            AndroidProviderAccountName(context.androidAccountName), raw.rawContactId, raw.version,
            raw.canonicalContactIdClaim, raw.sourceIdentity, durable,
        )) {
            AndroidProviderAcknowledgementResult.Acknowledged -> AndroidBoundedPageResult.Applied
            AndroidProviderAcknowledgementResult.Stale ->
                replan(AndroidIngestReplanReason.CONTACT_PROVIDER_ACK_STALE)
        }
    }

    private fun catalogFor(context: AndroidInteroperabilityContext): AndroidCompleteGroupCatalog =
        catalogSession?.takeIf {
            it.account == context.account && it.androidAccountName == context.androidAccountName &&
                it.providerEpoch == context.providerEpoch
        }?.catalog ?: throw IllegalArgumentException("Missing complete group catalog")

    private fun unified(context: AndroidInteroperabilityContext) = RoomAndroidUnifiedObservationCommitter(
        database,
        RoomAndroidObservationCommitter(
            context.account,
            RoomAndroidProjectionLedger(database),
            RoomAndroidCanonicalMutationStore(database, context.account, repository),
            canonicalContactRejectionObserver,
        ),
        RoomAndroidGroupMembershipObservationCommitter(database, repository),
        existingCommitRepairObserver,
        existingCommitReplanObserver,
    )

    private suspend fun durableLifecycleAcknowledgement(
        context: AndroidInteroperabilityContext,
        raw: com.patmanak.contako.data.android.provider.AndroidOwnedRawContact,
        canonicalId: String,
    ): String? = try {
        database.withTransaction {
            val account = database.androidProjectionLedgerDao().getAccount(context.account.value)
                ?: return@withTransaction null
            val ledger = database.androidProjectionLedgerDao().get(context.account.value, canonicalId)
                ?: return@withTransaction null
            val receipt = database.androidProjectionLedgerDao().getUnifiedObservationCommitReceipt(
                context.account.value, canonicalId,
            ) ?: return@withTransaction null
            if (account.androidAccountName != context.androidAccountName ||
                account.providerEpoch != context.providerEpoch || ledger.providerEpoch != context.providerEpoch ||
                ledger.rawContactLocator != raw.rawContactId || ledger.sourceIdentity != raw.sourceIdentity ||
                receipt.providerEpoch != context.providerEpoch || receipt.rawContactLocator != raw.rawContactId ||
                receipt.rawContactVersion != raw.version
            ) null else canonicalId
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        null
    }

    private suspend fun plan(
        context: AndroidInteroperabilityContext,
        catalog: AndroidCompleteGroupCatalog,
        observation: AndroidStableRawContactObservation,
    ): ContactPlanResult {
        return try {
        val raw = observation.rawContact
        // Lifecycle observations are handled before this existing-contact planner.
        if (raw.deleted || raw.canonicalContactIdClaim == null || raw.sourceIdentity == null) {
            return planRepair(AndroidExistingContactPlanRepairReason.LIFECYCLE_SHAPE)
        }
        database.withTransaction {
            val account = database.androidProjectionLedgerDao().getAccount(context.account.value)
                ?: return@withTransaction planRepair(AndroidExistingContactPlanRepairReason.ACCOUNT_MISSING)
            if (account.androidAccountName != context.androidAccountName ||
                account.providerEpoch != context.providerEpoch
            ) return@withTransaction ContactPlanResult.Replan
            val locator = AndroidRawContactLocator(context.providerEpoch, raw.rawContactId)
            val entity = database.androidProjectionLedgerDao().get(context.account.value, raw.canonicalContactIdClaim)
                ?: return@withTransaction planRepair(AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_MISSING)
            val byLocator = database.androidProjectionLedgerDao().getByRawContactLocator(
                context.account.value, context.providerEpoch, raw.rawContactId,
            ) ?: return@withTransaction ContactPlanResult.Replan
            if (entity != byLocator) return@withTransaction planRepair(
                AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_LOCATOR_INDEX_MISMATCH,
            )
            if (entity.sourceIdentity != raw.sourceIdentity) return@withTransaction planRepair(
                AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_SOURCE_IDENTITY_MISMATCH,
            )
            if (entity.providerEpoch != context.providerEpoch) return@withTransaction planRepair(
                AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_PROVIDER_EPOCH_MISMATCH,
            )
            if (entity.rawContactLocator != raw.rawContactId) return@withTransaction planRepair(
                AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_RAW_LOCATOR_MISMATCH,
            )
            if (entity.adoptionState != AndroidAdoptionState.ADOPTED.name) return@withTransaction planRepair(
                AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_ADOPTION_STATE_MISMATCH,
            )
            if (entity.tombstoneState != AndroidTombstoneState.NONE.name) return@withTransaction planRepair(
                AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_TOMBSTONE_STATE_MISMATCH,
            )
            val canonical = database.contactDao().get(context.account.value, entity.canonicalContactId)?.toDomain()
                ?: return@withTransaction planRepair(
                    AndroidExistingContactPlanRepairReason.CANONICAL_CONTACT_MISSING,
                )
            if (canonical.isDeleted || canonical.remoteContactId != raw.sourceIdentity) {
                return@withTransaction planRepair(
                    AndroidExistingContactPlanRepairReason.CANONICAL_REMOTE_IDENTITY_MISMATCH,
                )
            }
            // Establish scope/ownership before classifying an incompatible payload as local.
            // A malformed source claim must not be hidden by an unrelated unsupported MIME.
            val route = AndroidProviderMimeRouter().route(raw.rawContactId, observation.dataRows)
            existingContactUnsupportedRowsRepairReason(route)?.let { return@withTransaction planRepair(it) }
            var baseline = RoomAndroidProjectionLedger(database).loadBaseline(
                context.account, entity.canonicalContactId,
            )
            val recoveryDesired = if (baseline == null) mapper.project(canonical) else null
            val recoveryJournal = if (recoveryDesired != null) {
                val journal = database.androidGroupProjectionDao().getPhotoProviderWriteJournal(
                    context.account.value, entity.canonicalContactId)
                // Keep recovery fail-closed while exposing the exact rejected boundary through
                // closed categories only; no contact values, locators or journal content.
                if (recoveryDesired.rows.none { it.kind == AndroidRowKind.PHOTO }) {
                    return@withTransaction planRepair(AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_NO_PHOTO_RECOVERY)
                }
                if (journal == null) {
                    return@withTransaction planRepair(AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_JOURNAL_MISSING)
                }
                if (!isInterruptedPhotoProjectionRecoverable(
                        context, entity, canonical.revision, raw, recoveryDesired, journal, mapper)) {
                    return@withTransaction planRepair(AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_RECOVERY_STATE_MISMATCH)
                }
                interruptedPhotoProof(AndroidProviderAccountName(context.androidAccountName), observation, journal)?.let {
                    return@withTransaction planRepair(it)
                }
                journal
            } else null
            val hasBinaryContactRow = route.contactRows.rows.any { it.binarySlot != null }
            val retainedPhotoReference = recoveryJournal?.binaryReference ?: baseline?.let {
                existingContactRetainedPhotoReference(route, it, raw.dirty)
            }
            val binaryContactRows = route.contactRows.rows.filter { it.binarySlot != null }
            if (hasBinaryContactRow && (
                    binaryContactRows.size != 1 ||
                        !binaryContactRows.single().isStandardPhoto
                    )
            ) {
                return@withTransaction planRepair(AndroidExistingContactPlanRepairReason.BINARY_CONTACT_ROW)
            }
            val membershipLedger = database.androidGroupProjectionDao().getMembership(
                context.account.value, entity.canonicalContactId,
            ) ?: return@withTransaction planRepair(
                AndroidExistingContactPlanRepairReason.MEMBERSHIP_LEDGER_MISSING,
            )

            val groupLedgers = database.androidGroupProjectionDao().getAllGroups(context.account.value)
            val groups = database.contactGroupDao().getAll(context.account.value)
            val canonicalGroups = groups.associate { it.group.id to it.group }
            val trusted = groupLedgers.mapNotNull { group ->
                if (isCompletedAndroidGroupDeletion(group, canonicalGroups[group.canonicalGroupId], context.providerEpoch)) {
                    return@mapNotNull null
                }
                val row = group.groupRowLocator ?: return@withTransaction planRepair(
                    AndroidExistingContactPlanRepairReason.GROUP_LEDGER_BINDING_INCOMPLETE,
                )
                val version = group.providerVersion ?: return@withTransaction planRepair(
                    AndroidExistingContactPlanRepairReason.GROUP_LEDGER_BINDING_INCOMPLETE,
                )
                AndroidTrustedGroupBinding(
                    context.account, AndroidProviderAccountName(context.androidAccountName),
                    context.providerEpoch, group.canonicalGroupId, row, version, group.sourceIdentity,
                )
            }
            val resolved = AndroidGroupMembershipObservationDecoder().decode(
                catalog, raw.rawContactId, route.groupMembershipRows, trusted,
            )
            val catalogByLocator = catalog.rows.associateBy { it.groupRowId }
            val groupIdByLocator = trusted.associate { it.groupRowId to it.canonicalGroupId }
            val mappings = route.groupMembershipRows.rows.map { row ->
                val groupRowId = row.stringSlots.firstOrNull()?.toLongOrNull()
                    ?: return@withTransaction planRepair(
                        AndroidExistingContactPlanRepairReason.MEMBERSHIP_ROW_LOCATOR_INVALID,
                    )
                val groupId = groupIdByLocator[groupRowId]
                    ?: return@withTransaction planRepair(
                        AndroidExistingContactPlanRepairReason.MEMBERSHIP_GROUP_BINDING_MISSING,
                    )
                if (catalogByLocator[groupRowId] == null || groupId !in resolved.canonicalGroupIds) {
                    return@withTransaction planRepair(
                        AndroidExistingContactPlanRepairReason.MEMBERSHIP_CATALOG_MISMATCH,
                    )
                }
                AndroidGroupMembershipLocatorMapping(groupId, groupRowId, row.dataRowId)
            }

            val codec = AndroidProviderRowCodec(
                RoomAndroidProviderIdentityResolver(database, context.account, context.providerEpoch),
                AndroidDurablePhotoCapture { _, _, _, bytes ->
                    retainedPhotoReference
                        ?: bytes.takeIf { raw.dirty }
                            ?.let(AndroidProviderPhotoReferenceEncoder::encode)
                        ?: error("PHOTO_CAPTURE_NOT_AUTHORIZED")
                },
                missingPhotoReference = { retainedPhotoReference },
            )
            val snapshot = codec.decode(
                AndroidProviderAccountName(context.androidAccountName),
                entity.canonicalContactId,
                raw.rawContactId,
                route.contactRows.rows,
            )
            if (baseline == null) {
                val desired = requireNotNull(recoveryDesired)
                // A native edit is ambiguous without a baseline; keep it in Android.
                baseline = verifiedInitialProjectionBaseline(desired, snapshot, mapper) {
                    existingPlanRepairObserver.onBaselineMismatch(it)
                }
                    ?: return@withTransaction planRepair(AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_VALUES_DIFFER)
            }
            val delta = mapper.applyControlledDelta(canonical, baseline, snapshot)
            // Membership context is checked against the canonical revision presented to the
            // membership committer. A contact edit that changes preferred email must replan.
            val preferredEmail = CanonicalPrimaryValuePolicy.preferredEmail(canonical)?.id
            if (CanonicalPrimaryValuePolicy.preferredEmail(delta.contact)?.id != preferredEmail) {
                return@withTransaction ContactPlanResult.Replan
            }
            val membershipSnapshot = AndroidGroupMembershipSnapshot.create(
                context.account.value,
                entity.canonicalContactId,
                preferredEmail,
                if (preferredEmail == null) AndroidGroupMembershipAvailability.NO_EMAIL
                else AndroidGroupMembershipAvailability.AVAILABLE,
                mappings,
            )
            if (recoveryJournal != null && membershipSnapshot.semanticFingerprint().sha256Hex !=
                membershipLedger.pendingProjectionFingerprint) {
                return@withTransaction planRepair(AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_MEMBERSHIPS_DIFFER)
            }
            val previousMembershipIds = database.androidGroupProjectionDao().getMembershipBaseline(
                context.account.value, entity.canonicalContactId,
            )?.let { AndroidGroupMembershipSnapshotBinaryCodec.decode(it.encodedSnapshot).canonicalGroupIds.toSet() }
                .orEmpty()
            val requiredGroupIds = previousMembershipIds + resolved.canonicalGroupIds
            val groupLedgerById = groupLedgers.associateBy { it.canonicalGroupId }
            val command = RoomAndroidUnifiedObservationCommand(
                contactLedger = entity.toSnapshot(),
                locator = locator,
                rawContactVersion = raw.version,
                expectedCanonicalContactRevision = canonical.revision,
                contactDelta = delta,
                contactSnapshot = snapshot,
                membership = RoomAndroidGroupMembershipObservationCommand(
                    account = context.account,
                    expectedAccountRevision = account.revision,
                    locator = locator,
                    canonicalContactId = entity.canonicalContactId,
                    expectedCanonicalContactRevision = canonical.revision,
                    expectedContactLedgerRevision = entity.revision,
                    expectedMembershipLedgerRevision = membershipLedger.revision,
                    expectedCanonicalGroupStates = groups.map {
                        RoomExpectedContactGroupState(it.group.id, it.group.revision, it.group.isDeleted)
                    }.sortedBy { it.groupId },
                    expectedAndroidGroupBindings = requiredGroupIds.map { id ->
                        val group = groupLedgerById[id] ?: return@withTransaction planRepair(
                            AndroidExistingContactPlanRepairReason.REQUIRED_GROUP_LEDGER_MISSING,
                        )
                        RoomExpectedAndroidGroupBinding(
                            id, group.revision,
                            group.groupRowLocator ?: return@withTransaction planRepair(
                                AndroidExistingContactPlanRepairReason.REQUIRED_GROUP_BINDING_INCOMPLETE,
                            ),
                            group.providerVersion ?: return@withTransaction planRepair(
                                AndroidExistingContactPlanRepairReason.REQUIRED_GROUP_BINDING_INCOMPLETE,
                            ),
                            group.sourceIdentity,
                        )
                    }.sortedBy { it.canonicalGroupId },
                    observedSnapshot = membershipSnapshot,
                ),
            )
            ContactPlanResult.Ready(command)
        }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            ContactPlanResult.LocalFailure
        } catch (_: AndroidProviderMimeRouterException) {
            planRepair(AndroidExistingContactPlanRepairReason.MIME_ROUTING_FAILURE)
        } catch (failure: AndroidProviderRowCodecException) {
            runCatching { existingPlanRepairObserver.onRowCodecFailure(failure.category) }
            ContactPlanResult.Repair(AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE, failure.category)
        } catch (_: IllegalArgumentException) {
            planRepair(AndroidExistingContactPlanRepairReason.INVALID_ARGUMENT)
        }
    }

    private fun planRepair(reason: AndroidExistingContactPlanRepairReason) = ContactPlanResult.Repair(reason)

    private fun repair(reason: AndroidIngestActionRequiredReason): AndroidBoundedPageResult.RepairRequired =
        AndroidBoundedPageResult.RepairRequired.also {
            runCatching { actionRequiredObserver.onActionRequired(reason) }
        }

    private fun contactLocalRepair(reason: AndroidIngestActionRequiredReason): AndroidBoundedPageResult {
        repair(reason)
        return AndroidBoundedPageResult.PartiallyApplied
    }

    private fun replan(reason: AndroidIngestReplanReason): AndroidBoundedPageResult.ReplanRequired =
        AndroidBoundedPageResult.ReplanRequired.also {
            runCatching { replanObserver.onReplan(reason) }
        }


    private suspend fun currentAcknowledgement(
        context: AndroidInteroperabilityContext,
        observation: AndroidStableRawContactObservation,
        command: RoomAndroidUnifiedObservationCommand,
    ): String? = try {
        database.withTransaction {
            val raw = observation.rawContact
            val account = database.androidProjectionLedgerDao().getAccount(context.account.value)
                ?: return@withTransaction null
            val ledger = database.androidProjectionLedgerDao().get(
                context.account.value, command.contactLedger.canonicalContactId,
            ) ?: return@withTransaction null
            val canonical = database.contactDao().get(
                context.account.value, command.contactLedger.canonicalContactId,
            )?.toDomain() ?: return@withTransaction null
            val source = raw.sourceIdentity ?: return@withTransaction null
            if (account.androidAccountName != context.androidAccountName ||
                account.providerEpoch != context.providerEpoch || ledger.providerEpoch != context.providerEpoch ||
                ledger.rawContactLocator != raw.rawContactId || ledger.sourceIdentity != source ||
                raw.canonicalContactIdClaim != ledger.canonicalContactId || canonical.remoteContactId != source ||
                canonical.isDeleted || ledger.tombstoneState != AndroidTombstoneState.NONE.name
            ) null else source
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        null
    }

    private data class CatalogSession(
        val account: AccountScope,
        val androidAccountName: String,
        val providerEpoch: Long,
        val catalog: AndroidCompleteGroupCatalog,
    )

    private sealed interface ContactPlanResult {
        data class Ready(val command: RoomAndroidUnifiedObservationCommand) : ContactPlanResult
        data object Replan : ContactPlanResult
        data class Repair(
            val reason: AndroidExistingContactPlanRepairReason,
            val codecFailure: AndroidProviderRowCodecFailure? = null,
        ) : ContactPlanResult
        data object LocalFailure : ContactPlanResult
    }
}

internal fun AndroidContactsProviderWriter.asObservationAcknowledger() =
    AndroidContactObservationAcknowledger { accountName, rawContactId, version, claim, sourceIdentity, claimAfter ->
        acknowledgeContactObservation(
            accountName,
            rawContactId,
            version,
            claim,
            sourceIdentity?.let(AndroidExpectedSourceIdentity::Present) ?: AndroidExpectedSourceIdentity.Missing,
            claimAfter,
        )
    }

internal typealias ExistingProductionAndroidContactObservationCoordinator =
    ProductionAndroidContactObservationCoordinator

private fun com.patmanak.contako.data.local.AndroidProjectionLedgerEntity.toSnapshot() =
    AndroidProjectionLedgerSnapshot(
        canonicalContactId,
        revision,
        providerEpoch,
        rawContactLocator?.let { AndroidRawContactLocator(providerEpoch, it) },
        sourceIdentity != null,
        canonicalProjectionFingerprint?.let(::AndroidProjectionFingerprint),
        androidBaselineFingerprint?.let(::AndroidProjectionFingerprint),
        pendingProjectionFingerprint?.let(::AndroidProjectionFingerprint),
        AndroidProjectionWriteState.valueOf(projectionState),
        AndroidIngestionState.valueOf(ingestionState),
        AndroidTombstoneState.valueOf(tombstoneState),
        AndroidAdoptionState.valueOf(adoptionState),
    )
