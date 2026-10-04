package com.patmanak.contako.data.sync

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidLedgerCasResult
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidCompleteGroupCatalog
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipLocatorMapping
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipWritePlanner
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipWritePlanException
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipWritePlanFailure
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotContextException
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotContextFailure
import com.patmanak.contako.data.android.mapping.AndroidGroupProjectionPolicy
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidTrustedGroupBinding
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.AndroidContactsProviderReader
import com.patmanak.contako.data.android.provider.AndroidContactsProviderWriter
import com.patmanak.contako.data.android.provider.AndroidDurableValueBinding
import com.patmanak.contako.data.android.provider.AndroidEnsureRawContactResult
import com.patmanak.contako.data.android.provider.AndroidDeleteRawContactResult
import com.patmanak.contako.data.android.provider.AndroidExpectedSourceIdentity
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipDecodeException
import com.patmanak.contako.data.android.provider.AndroidGroupsProviderReader
import com.patmanak.contako.data.android.provider.AndroidPendingBindingRegistration
import com.patmanak.contako.data.android.provider.AndroidPendingProviderBinding
import com.patmanak.contako.data.android.provider.AndroidProjectionBinaryLoader
import com.patmanak.contako.data.android.provider.AndroidPhotoProviderWriteResult
import com.patmanak.contako.data.android.provider.AndroidPhotoProviderStaleCategory
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidProviderBoundaryException
import com.patmanak.contako.data.android.provider.AndroidProviderFailureCategory
import com.patmanak.contako.data.android.provider.AndroidProviderIdentityFailure
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouter
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouterException
import com.patmanak.contako.data.android.provider.AndroidProviderProjectionResult
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodec
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodecException
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure
import com.patmanak.contako.data.android.provider.AndroidRawContactLifecycleException
import com.patmanak.contako.data.android.provider.AndroidRawContactLifecycleGateway
import com.patmanak.contako.data.android.provider.AndroidStableRawContactPageResult
import com.patmanak.contako.data.android.provider.RoomAndroidProviderIdentityResolver
import com.patmanak.contako.data.android.provider.RoomAndroidPhotoProviderWriteCoordinator
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.local.AndroidGroupMembershipBaselineEntity
import com.patmanak.contako.data.local.AndroidGroupMembershipProjectionLedgerEntity
import com.patmanak.contako.data.local.AggregateType
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.AndroidProviderAccountMutationLocks
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.MutationOperation
import com.patmanak.contako.data.local.toDomain
import kotlinx.coroutines.CancellationException

/** Production H04-04 single-contact projection. Every provider mutation has a durable pending intent. */
internal class ProductionAndroidProjectionItemExecutor(
    private val database: ContakoDatabase,
    private val contactsReader: AndroidContactsProviderReader,
    private val groupsReader: AndroidGroupsProviderReader,
    private val writer: AndroidContactsProviderWriter,
    private val lifecycle: AndroidRawContactLifecycleGateway,
    private val photoWriter: RoomAndroidPhotoProviderWriteCoordinator,
    private val binaryLoader: AndroidProjectionBinaryLoader,
    private val repairObserver: AndroidProjectionRepairObserver = AndroidProjectionRepairObserver { },
    private val replanObserver: AndroidProjectionReplanObserver = AndroidProjectionReplanObserver { },
    private val mapper: CanonicalAndroidContactMapper = CanonicalAndroidContactMapper(
        // A PHOTO row whose bytes cannot be resolved offline is rejected by the provider row codec
        // and would fail the whole projection page, so it is dropped from the desired snapshot.
        photoBytesAvailable = { reference -> reference != null && binaryLoader.load(reference) != null },
    ),
) : AndroidProjectionItemExecutor {
    override suspend fun project(
        context: AndroidInteroperabilityContext,
        ledger: AndroidProjectionLedgerEntity,
    ): AndroidBoundedPageResult = AndroidProviderAccountMutationLocks.withContactProjectionSlot(context.account.value) {
        try {
            projectLocked(context, ledger)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            AndroidBoundedPageResult.LocalPersistenceFailure
        } catch (failure: AndroidProviderBoundaryException) {
            repair(failure.category.toProjectionRepairCategory())
        } catch (_: AndroidRawContactLifecycleException) {
            repair(AndroidProjectionRepairCategory.RAW_CONTACT_LIFECYCLE)
        } catch (_: AndroidProviderMimeRouterException) {
            repair(AndroidProjectionRepairCategory.MIME_ROUTING)
        } catch (failure: AndroidProviderRowCodecException) {
            repair(failure.category.toProjectionRepairCategory())
        } catch (_: AndroidGroupMembershipDecodeException) {
            repair(AndroidProjectionRepairCategory.MEMBERSHIP_DECODING)
        } catch (_: AndroidGroupSnapshotContextException) {
            repair(AndroidProjectionRepairCategory.MEMBERSHIP_STATE)
        } catch (failure: AndroidGroupMembershipWritePlanException) {
            repair(failure.category.toProjectionRepairCategory())
        } catch (_: IllegalArgumentException) {
            repair(AndroidProjectionRepairCategory.INVALID_ARGUMENT)
        } catch (_: IllegalStateException) {
            repair(AndroidProjectionRepairCategory.INVARIANT_VIOLATION)
        }
    }

    /**
     * Seeds the empty membership ledger and baseline for a contact adopted from Proton.
     *
     * Both were created only by the Android-ingestion path, so a Proton-adopted contact reached the
     * membership checks with nothing durable and stayed repair-required forever. The seed states
     * "no Android group membership observed yet"; the write planner reconciles it against the
     * desired canonical membership. Idempotent, and never overwrites existing state.
     */
    private suspend fun ensureMembershipLedger(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        rawContactLocator: Long,
        canonical: CanonicalContact,
    ) {
        val groupDao = database.androidGroupProjectionDao()
        if (groupDao.getMembership(context.account.value, canonicalContactId) != null) return

        val preferredEmailValueId = CanonicalPrimaryValuePolicy.preferredEmail(canonical)?.id
        val emptyBaseline = AndroidGroupMembershipSnapshot.create(
            accountId = context.account.value,
            canonicalContactId = canonicalContactId,
            preferredEmailValueId = preferredEmailValueId,
            membershipAvailability = if (preferredEmailValueId == null) {
                AndroidGroupMembershipAvailability.NO_EMAIL
            } else {
                AndroidGroupMembershipAvailability.AVAILABLE
            },
            locatorMappings = emptyList(),
        )
        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(emptyBaseline)

        groupDao.insertMembership(
            AndroidGroupMembershipProjectionLedgerEntity(
                accountId = context.account.value,
                canonicalContactId = canonicalContactId,
                revision = 0,
                providerEpoch = context.providerEpoch,
                rawContactLocator = rawContactLocator,
                preferredEmailValueId = preferredEmailValueId,
                canonicalProjectionFingerprint = null,
                androidBaselineFingerprint = emptyBaseline.semanticFingerprint().sha256Hex,
                pendingProjectionFingerprint = null,
                projectionState = AndroidProjectionWriteState.CLEAN.name,
                ingestionState = AndroidIngestionState.NONE.name,
            ),
        )
        groupDao.upsertMembershipBaseline(
            AndroidGroupMembershipBaselineEntity(
                accountId = context.account.value,
                canonicalContactId = canonicalContactId,
                fingerprint = AndroidGroupMembershipSnapshotBinaryCodec
                    .integrityFingerprint(encoded).sha256Hex,
                encodedSnapshot = encoded,
            ),
        )
    }

    private suspend fun projectLocked(
        context: AndroidInteroperabilityContext,
        pageLedger: AndroidProjectionLedgerEntity,
    ): AndroidBoundedPageResult {
        val accountName = AndroidProviderAccountName(context.androidAccountName)
        var ledger = database.androidProjectionLedgerDao().get(context.account.value, pageLedger.canonicalContactId)
            ?: return repair(AndroidProjectionRepairCategory.LOCAL_LEDGER_STATE)
        if (ledger != pageLedger || ledger.providerEpoch != context.providerEpoch
        ) return replan(AndroidProjectionReplanCategory.LEDGER_CONTEXT)
        val canonicalEntity = database.contactDao().get(context.account.value, ledger.canonicalContactId)
            ?: return repair(AndroidProjectionRepairCategory.CANONICAL_CONTACT_STATE)
        val canonical = try {
            canonicalEntity.toDomain()
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.CANONICAL_DECODING)
        }
        if (canonical.isDeleted) return projectDeletion(context, ledger)
        if (ledger.tombstoneState != AndroidTombstoneState.NONE.name) {
            return repair(AndroidProjectionRepairCategory.CANONICAL_CONTACT_STATE)
        }
        if (ledger.sourceIdentity == null) {
            val outbox = database.outboxDao().get(
                context.account.value,
                AggregateType.CONTACT.name,
                ledger.canonicalContactId,
            )
            return if (
                ledger.adoptionState == AndroidAdoptionState.AWAITING_REMOTE_ID.name &&
                ledger.rawContactLocator != null && canonical.remoteContactId == null &&
                canonical.pendingMutationRevision != null &&
                outbox?.revision == canonical.pendingMutationRevision &&
                outbox.operation == MutationOperation.UPSERT.name && outbox.remoteIdentity == null
            ) {
                // The Android-created contact is durably queued for its first remote identity.
                // Projection must not report a stale provider state before the outbox is drained.
                AndroidBoundedPageResult.Applied
            } else {
                replan(AndroidProjectionReplanCategory.LEDGER_CONTEXT)
            }
        }
        if (canonical.remoteContactId != ledger.sourceIdentity) {
            return repair(AndroidProjectionRepairCategory.CANONICAL_CONTACT_STATE)
        }
        val desiredContact = try {
            mapper.project(canonical)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.CONTACT_MAPPING)
        }
        val desiredPhoto = desiredContact.rows.singleOrNull { it.kind == AndroidRowKind.PHOTO }

        if (ledger.rawContactLocator == null) {
            if (ledger.adoptionState != AndroidAdoptionState.SOURCE_ID_PENDING.name) {
                return repair(AndroidProjectionRepairCategory.RAW_CONTACT_ADOPTION)
            }
            val ensured = lifecycle.ensureOwnedRawContact(
                accountName, ledger.canonicalContactId, ledger.sourceIdentity,
            )
            val handle = when (ensured) {
                is AndroidEnsureRawContactResult.Existing -> ensured.handle
                is AndroidEnsureRawContactResult.Created -> ensured.handle
                is AndroidEnsureRawContactResult.RecoveredAfterLostAcknowledgement -> ensured.handle
            }
            when (RoomAndroidProjectionLedger(database).acknowledgeAdoption(
                context.account,
                ledger.canonicalContactId,
                ledger.revision,
                AndroidRawContactLocator(context.providerEpoch, handle.rawContactId),
            )) {
                AndroidLedgerCasResult.Stale ->
                    return replan(AndroidProjectionReplanCategory.ADOPTION_ACKNOWLEDGEMENT)
                is AndroidLedgerCasResult.Updated -> Unit
            }
            ledger = database.androidProjectionLedgerDao().get(context.account.value, ledger.canonicalContactId)
                ?: return repair(AndroidProjectionRepairCategory.RAW_CONTACT_ADOPTION)
        }
        val rawContactId = ledger.rawContactLocator
            ?: return repair(AndroidProjectionRepairCategory.RAW_CONTACT_ADOPTION)
        // A contact adopted from Proton has no membership ledger yet: only the Android-ingestion
        // path created one. Both it and its baseline are required further down, and
        // RoomAndroidProjectionWriteLedger.prepare requires the ledger too, so a Proton-adopted
        // contact could never be projected. Seed the empty starting state here.
        ensureMembershipLedger(context, ledger.canonicalContactId, rawContactId, canonical)
        val currentObservation = try {
            readExact(accountName, rawContactId)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.PROVIDER_OBSERVATION)
        } ?: return replan(AndroidProjectionReplanCategory.CURRENT_OBSERVATION)
        // Never adopt a projection baseline over an uncommitted native edit, even for a no-op
        // write plan. The ingestion stage must durably consume and acknowledge DIRTY first.
        if (currentObservation.rawContact.dirty) {
            // An unresolved first-photo recovery is contact-local. Preserve its dirty row and
            // all proof guards; let the bounded coordinator continue with other contacts.
            val photoRecoveryPending = ledger.androidBaselineFingerprint == null &&
                ledger.pendingProjectionFingerprint != null && desiredPhoto != null &&
                database.androidGroupProjectionDao().getPhotoProviderWriteJournal(
                    context.account.value, canonical.id,
                ) != null
            return if (photoRecoveryPending) repair(AndroidProjectionRepairCategory.INTERRUPTED_PHOTO_RECOVERY_UNVERIFIED)
                else replan(AndroidProjectionReplanCategory.CURRENT_OBSERVATION_DIRTY)
        }
        val adoptingAndroidCreatedRawContact =
            ledger.adoptionState == AndroidAdoptionState.SOURCE_ID_PENDING.name &&
                currentObservation.rawContact.sourceIdentity == null
        if (currentObservation.rawContact.deleted ||
            currentObservation.rawContact.canonicalContactIdClaim != ledger.canonicalContactId ||
            (!adoptingAndroidCreatedRawContact &&
                currentObservation.rawContact.sourceIdentity != ledger.sourceIdentity)
        ) return repair(AndroidProjectionRepairCategory.PROVIDER_OWNERSHIP)
        if (!photoWriter.reconcileCommittedIdentity(context, canonical.id, canonical.revision, currentObservation, desiredPhoto)) {
            return repair(AndroidProjectionRepairCategory.PROVIDER_IDENTITY_REGISTRATION)
        }
        val current = try {
            decodeContact(context, accountName, canonical, currentObservation, desiredContact, AndroidProjectionDecodeStage.CURRENT)
        } catch (failure: AndroidProviderRowCodecException) {
            return repair(failure.category.toProjectionRepairCategory())
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.CURRENT_CONTACT_DECODING)
        } ?: return repair(AndroidProjectionRepairCategory.CONTACT_DECODING)
        val contactPlan = try {
            mapper.planProjection(current, canonical)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.CONTACT_PLANNING)
        }

        val membershipBaselineEntity = database.androidGroupProjectionDao().getMembershipBaseline(
            context.account.value, ledger.canonicalContactId,
        ) ?: return repair(AndroidProjectionRepairCategory.MEMBERSHIP_BASELINE_MISSING)
        if (AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(
                membershipBaselineEntity.encodedSnapshot,
            ).sha256Hex != membershipBaselineEntity.fingerprint
        ) return repair(AndroidProjectionRepairCategory.MEMBERSHIP_BASELINE_INTEGRITY)
        val membershipBaseline = try {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(membershipBaselineEntity.encodedSnapshot)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.MEMBERSHIP_BASELINE_DECODING)
        }
        val membershipLedger = database.androidGroupProjectionDao().getMembership(
            context.account.value, ledger.canonicalContactId,
        ) ?: return repair(AndroidProjectionRepairCategory.MEMBERSHIP_BASELINE_MISSING)
        if (membershipBaseline.semanticFingerprint().sha256Hex != membershipLedger.androidBaselineFingerprint) {
            return repair(AndroidProjectionRepairCategory.MEMBERSHIP_LEDGER_FINGERPRINT)
        }
        if (membershipLedger.providerEpoch != context.providerEpoch ||
            membershipLedger.rawContactLocator != rawContactId
        ) return repair(AndroidProjectionRepairCategory.MEMBERSHIP_LEDGER_BINDING)
        try {
            membershipBaseline.requireCurrentCanonicalContext(canonical)
        } catch (failure: AndroidGroupSnapshotContextException) {
            if (!isRecoverableMembershipProjectionContextFailure(failure.category)) {
                return repair(AndroidProjectionRepairCategory.MEMBERSHIP_CANONICAL_CONTEXT)
            }
            // Proton reconciliation may replace the canonical value identity of the preferred
            // email without changing the provider group rows. The old baseline is still valid
            // evidence for those rows, but it must not authorize a mutation by itself. Continue
            // only for that closed context change: decode the current provider memberships below,
            // plan against the current canonical preferred email, then atomically replace both the
            // membership ledger fingerprint and baseline during write completion.
        }

        val catalog = try {
            readCompleteCatalog(context, accountName)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.GROUP_CATALOG)
        }
        val trusted = try {
            trustedGroups(context, accountName)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.GROUP_CATALOG)
        } ?: return repair(AndroidProjectionRepairCategory.GROUP_STATE)
        val desiredMembership = try {
            AndroidGroupProjectionPolicy().projectMemberships(
                canonical, database.contactGroupDao().getAll(context.account.value).map { it.toDomain() },
            )
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.GROUP_MAPPING)
        }
        // Plan from the provider state observed in this pass, not solely from the last completed
        // baseline. If a prior provider batch committed but its return/ledger completion was lost,
        // the observed rows already equal the desired state and the retry becomes a zero-write
        // verification. Planning from the old baseline would insert the memberships a second time.
        val currentMembership = try {
            decodeMembership(context, ledger.canonicalContactId, rawContactId, currentObservation, catalog, trusted)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.CURRENT_MEMBERSHIP_DECODING)
        } ?: return repair(AndroidProjectionRepairCategory.MEMBERSHIP_STATE)
        val membershipPlan = try {
            AndroidGroupMembershipWritePlanner().plan(
                context.account,
                accountName,
                context.providerEpoch,
                canonical,
                desiredMembership,
                currentMembership,
                catalog,
                trusted,
            )
        } catch (failure: AndroidGroupMembershipWritePlanException) {
            return repair(failure.category.toProjectionRepairCategory())
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.MEMBERSHIP_PLANNING)
        }
        val desiredMembershipSnapshot = try {
            AndroidGroupMembershipSnapshot.create(
                context.account.value,
                ledger.canonicalContactId,
                desiredMembership.preferredEmailValueId,
                desiredMembership.availability,
                desiredMembership.canonicalGroupIds.mapIndexed { index, groupId ->
                    val existing = currentMembership.locatorMappings.singleOrNull { it.canonicalGroupId == groupId }
                    existing ?: trusted.singleOrNull { it.canonicalGroupId == groupId }?.let { binding ->
                        AndroidGroupMembershipLocatorMapping(
                            groupId,
                            binding.groupRowId,
                            PENDING_DATA_ROW_LOCATOR - index,
                        )
                    } ?: return repair(AndroidProjectionRepairCategory.GROUP_STATE)
                },
            )
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.MEMBERSHIP_SNAPSHOT)
        }
        val writeLedger = RoomAndroidProjectionWriteLedger(database, mapper)
        val alreadyPrepared = ledger.projectionState == AndroidProjectionWriteState.WRITE_PENDING.name &&
            ledger.pendingProjectionFingerprint == contactPlan.fingerprint.sha256Hex &&
            membershipLedger.projectionState == AndroidProjectionWriteState.WRITE_PENDING.name &&
            membershipLedger.pendingProjectionFingerprint == desiredMembershipSnapshot.semanticFingerprint().sha256Hex
        val preparedContactRevision: Long
        val preparedMembershipRevision: Long
        if (alreadyPrepared) {
            preparedContactRevision = ledger.revision
            preparedMembershipRevision = membershipLedger.revision
        } else {
            // A pending write whose fingerprint no longer matches describes an older desired state:
            // the canonical contact changed, or the projection now produces different rows, between
            // the prepare and this pass. That is recoverable, not a defect. Treating it as
            // repair-required left every such entry stuck in WRITE_PENDING forever, which is what
            // kept photos from ever reaching the provider. Reset it so prepare can run again.
            if (ledger.projectionState == AndroidProjectionWriteState.WRITE_PENDING.name ||
                membershipLedger.projectionState == AndroidProjectionWriteState.WRITE_PENDING.name
            ) {
                return if (writeLedger.abandonStalePreparation(
                        context,
                        ledger.canonicalContactId,
                        ledger.revision,
                        membershipLedger.revision,
                    )
                ) {
                    // Replan rather than continuing: the reset moved both ledger revisions, so this
                    // pass's snapshot is stale by construction.
                    replan(AndroidProjectionReplanCategory.STALE_PREPARATION_RESET)
                } else {
                    repair(AndroidProjectionRepairCategory.WRITE_PREPARATION)
                }
            }
            val preparation = try {
                writeLedger.prepare(
                    context,
                    ledger.canonicalContactId,
                    ledger.revision,
                    canonical.revision,
                    membershipLedger.revision,
                    contactPlan.desired,
                    desiredMembershipSnapshot,
                )
            } catch (_: IllegalArgumentException) {
                return repair(AndroidProjectionRepairCategory.WRITE_LEDGER)
            }
            when (preparation) {
                AndroidProjectionWriteLedgerResult.Stale ->
                    return replan(AndroidProjectionReplanCategory.WRITE_PREPARATION)
                AndroidProjectionWriteLedgerResult.RepairRequired ->
                    return repair(AndroidProjectionRepairCategory.WRITE_PREPARATION)
                AndroidProjectionWriteLedgerResult.Applied -> Unit
            }
            preparedContactRevision = Math.incrementExact(ledger.revision)
            preparedMembershipRevision = Math.incrementExact(membershipLedger.revision)
        }
        val resolver = RoomAndroidProviderIdentityResolver(database, context.account, context.providerEpoch)
        val pending = contactPlan.operations.mapNotNull { operation ->
            val row = (operation as? com.patmanak.contako.data.android.mapping.AndroidRowOperation.Insert)?.desired
                ?: return@mapNotNull null
            AndroidPendingProviderBinding(
                row.kind,
                AndroidDurableValueBinding(row.identity.canonicalValueId, row.linkedCanonicalValueIds),
            )
        }
        if (resolver.registerPendingBindings(accountName, ledger.canonicalContactId, rawContactId, pending) !=
            AndroidPendingBindingRegistration.Registered
        ) return repair(AndroidProjectionRepairCategory.PROVIDER_IDENTITY_REGISTRATION)
        // Seed/update the bounded inline Data/Photo row before opening display_photo. Some OEM
        // providers accept the display-photo pipe for a missing row but persist only an empty
        // placeholder. The inline operation establishes the row and its canonical identity; the
        // journaled stream below then replaces its full-size payload.
        val binderPlan = contactPlan
        val providerWrite = try {
            val expectedProviderSource = if (adoptingAndroidCreatedRawContact) {
                AndroidExpectedSourceIdentity.Missing
            } else {
                AndroidExpectedSourceIdentity.Present(requireNotNull(ledger.sourceIdentity))
            }
            writer.applyProjectionPlan(
                accountName,
                rawContactId,
                currentObservation.rawContact.version,
                expectedProviderSource,
                sourceIdentityAfterWrite = ledger.sourceIdentity.takeIf { adoptingAndroidCreatedRawContact },
                plan = binderPlan,
                membershipPlan = membershipPlan,
            )
        } catch (failure: AndroidProviderBoundaryException) {
            return repair(failure.category.toProjectionRepairCategory())
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.PROVIDER_WRITE)
        }
        when (providerWrite) {
            AndroidProviderProjectionResult.ReplanRequired ->
                return replan(AndroidProjectionReplanCategory.PROVIDER_WRITE)
            is AndroidProviderProjectionResult.Applied,
            AndroidProviderProjectionResult.NoChangeValidated,
            -> Unit
        }
        var post = try {
            readExact(accountName, rawContactId)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.POST_WRITE_OBSERVATION)
        } ?: return replan(AndroidProjectionReplanCategory.POST_WRITE_OBSERVATION)
        var committedPhotoSha256: String? = null
        if (desiredPhoto != null) {
            val reference = desiredPhoto.binaryReference
                ?: return repair(AndroidProjectionRepairCategory.PHOTO_PAYLOAD)
            val bytes = try {
                binaryLoader.load(reference)
            } catch (_: RuntimeException) {
                null
            } ?: return repair(AndroidProjectionRepairCategory.PHOTO_PAYLOAD)
            if (bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES) {
                return repair(AndroidProjectionRepairCategory.PHOTO_PAYLOAD)
            }
            committedPhotoSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            when (val photoResult = photoWriter.write(
                context, ledger.canonicalContactId, canonical.revision, preparedContactRevision,
                accountName, rawContactId, post.rawContact.version, requireNotNull(ledger.sourceIdentity), desiredPhoto,
            )) {
                is AndroidPhotoProviderWriteResult.Committed -> Unit
                is AndroidPhotoProviderWriteResult.Stale ->
                    return replan(photoResult.category.toProjectionReplanCategory())
                is AndroidPhotoProviderWriteResult.RepairRequired -> {
                    runCatching { repairObserver.onPhotoFailure(photoResult.category) }
                    return repair(AndroidProjectionRepairCategory.PHOTO_WRITE)
                }
            }
            post = try {
                readExact(accountName, rawContactId)
            } catch (_: IllegalArgumentException) {
                return repair(AndroidProjectionRepairCategory.POST_WRITE_OBSERVATION)
            } ?: return replan(AndroidProjectionReplanCategory.POST_PHOTO_OBSERVATION)
        }
        if (post.rawContact.dirty) return replan(AndroidProjectionReplanCategory.POST_WRITE_OBSERVATION)
        if (post.rawContact.deleted || post.rawContact.canonicalContactIdClaim != ledger.canonicalContactId ||
            post.rawContact.sourceIdentity != ledger.sourceIdentity
        ) return repair(AndroidProjectionRepairCategory.POST_WRITE_IDENTITY)
        if (!photoWriter.reconcileCommittedIdentity(context, canonical.id, canonical.revision, post, desiredPhoto)) {
            return repair(AndroidProjectionRepairCategory.PROVIDER_IDENTITY_REGISTRATION)
        }
        val postContact = try {
            decodeContact(context, accountName, canonical, post, desiredContact, AndroidProjectionDecodeStage.POST_WRITE)
        } catch (failure: AndroidProviderRowCodecException) {
            return repair(failure.category.toProjectionRepairCategory())
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_DECODING)
        } ?: return repair(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VERIFICATION)
        val postFingerprint = try {
            mapper.fingerprint(mapper.normalizeGeneratedName(postContact, contactPlan.desired))
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.POST_WRITE_FINGERPRINT)
        }
        if (postFingerprint != contactPlan.fingerprint) {
            return repair(classifyAndroidProjectionMismatch(
                contactPlan.desired, postContact, mapper, repairObserver::onComponentMismatch,
            ))
        }
        val postMembership = try {
            decodeMembership(context, ledger.canonicalContactId, rawContactId, post, catalog, trusted)
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.POST_WRITE_MEMBERSHIP_DECODING)
        } ?: return repair(AndroidProjectionRepairCategory.POST_WRITE_MEMBERSHIP_VERIFICATION)
        if (postMembership.semanticFingerprint() != desiredMembershipSnapshot.semanticFingerprint()) {
            return repair(AndroidProjectionRepairCategory.POST_WRITE_MEMBERSHIP_VERIFICATION)
        }
        val completion = try {
            writeLedger.complete(
                context,
                ledger.canonicalContactId,
                canonical.revision,
                preparedContactRevision,
                preparedMembershipRevision,
                postContact,
                postMembership,
            )
        } catch (_: IllegalArgumentException) {
            return repair(AndroidProjectionRepairCategory.LEDGER_COMPLETION_ARGUMENT)
        }
        val result = when (completion) {
            AndroidProjectionWriteLedgerResult.Applied -> AndroidBoundedPageResult.Applied
            AndroidProjectionWriteLedgerResult.Stale -> replan(AndroidProjectionReplanCategory.LEDGER_COMPLETION)
            AndroidProjectionWriteLedgerResult.RepairRequired ->
                repair(AndroidProjectionRepairCategory.LEDGER_COMPLETION)
        }
        if (result == AndroidBoundedPageResult.Applied && committedPhotoSha256 != null) {
            photoWriter.cleanupCommitted(context.account.value, ledger.canonicalContactId, committedPhotoSha256)
        }
        return result
    }

    /**
     * Finalizes the same durable tombstone for every origin. Canonical deletion plus no pending
     * mutation/conflict is the reconciliation receipt; Android absence alone never authorizes it.
     * The bounded provider delete runs under the account projection slot and a Room transaction,
     * so canonical/account/ledger changes cannot invalidate its ownership proof mid-mutation.
     * If provider deletion commits but Room completion is lost, that same intent admits absence.
     */
    private suspend fun projectDeletion(
        context: AndroidInteroperabilityContext,
        expectedLedger: AndroidProjectionLedgerEntity,
    ): AndroidBoundedPageResult = database.withTransaction {
        val dao = database.androidProjectionLedgerDao()
        val account = dao.getAccount(context.account.value)
            ?: return@withTransaction repair(AndroidProjectionRepairCategory.LOCAL_LEDGER_STATE)
        val ledger = dao.get(context.account.value, expectedLedger.canonicalContactId)
        if (account.revision != context.accountRevision || account.providerEpoch != context.providerEpoch ||
            account.androidAccountName != context.androidAccountName || ledger != expectedLedger
        ) return@withTransaction replan(AndroidProjectionReplanCategory.LEDGER_CONTEXT)
        val canonical = database.contactDao().get(context.account.value, expectedLedger.canonicalContactId)?.contact
            ?: return@withTransaction repair(AndroidProjectionRepairCategory.CANONICAL_CONTACT_STATE)
        if (!canonical.isDeleted || canonical.remoteContactId != expectedLedger.sourceIdentity ||
            canonical.conflictState != null
        ) return@withTransaction repair(AndroidProjectionRepairCategory.CANONICAL_CONTACT_STATE)
        val pending = database.outboxDao().get(
            context.account.value, AggregateType.CONTACT.name, canonical.id,
        )
        if (canonical.pendingMutationRevision != null || pending != null) {
            // Preserve Android DELETED rows until the remote delete is acknowledged/classified.
            return@withTransaction AndroidBoundedPageResult.Applied
        }
        if (expectedLedger.tombstoneState == AndroidTombstoneState.REMOTE_CONVERGED.name) {
            return@withTransaction AndroidBoundedPageResult.Applied
        }
        val accountName = AndroidProviderAccountName(context.androidAccountName)
        val source = expectedLedger.sourceIdentity?.let(AndroidExpectedSourceIdentity::Present)
            ?: AndroidExpectedSourceIdentity.Missing
        val handle = lifecycle.findOwnedRawContactForDeletion(accountName, canonical.id, source)
        if (handle != null) {
            if (handle.rawContactId != expectedLedger.rawContactLocator) {
                return@withTransaction repair(AndroidProjectionRepairCategory.PROVIDER_OWNERSHIP)
            }
            when (lifecycle.deleteOwnedRawContact(
                accountName, canonical.id, handle.rawContactId, handle.version, source,
            )) {
                AndroidDeleteRawContactResult.Stale ->
                    return@withTransaction replan(AndroidProjectionReplanCategory.CURRENT_OBSERVATION)
                AndroidDeleteRawContactResult.Deleted,
                AndroidDeleteRawContactResult.RecoveredAfterLostAcknowledgement,
                AndroidDeleteRawContactResult.AbsentRequiresDurableIntent -> Unit
            }
        }
        // Retain the tombstone and historical locator as retry evidence, never as a live row.
        check(dao.update(expectedLedger.copy(
            revision = Math.incrementExact(expectedLedger.revision),
            projectionState = AndroidProjectionWriteState.DETACHED.name,
            tombstoneState = AndroidTombstoneState.REMOTE_CONVERGED.name,
            pendingProjectionFingerprint = null,
            observedAndroidFingerprint = null,
        )) == 1)
        AndroidBoundedPageResult.Applied
    }

    private fun repair(category: AndroidProjectionRepairCategory): AndroidBoundedPageResult {
        repairObserver.onRepair(category)
        return AndroidBoundedPageResult.RepairRequired
    }

    private fun replan(category: AndroidProjectionReplanCategory): AndroidBoundedPageResult {
        replanObserver.onReplan(category)
        return AndroidBoundedPageResult.ReplanRequired
    }

    private fun readExact(accountName: AndroidProviderAccountName, rawContactId: Long) =
        when (val read = contactsReader.readStableRawContact(accountName, rawContactId)) {
            AndroidStableRawContactPageResult.ReplanRequired -> null
            is AndroidStableRawContactPageResult.Stable -> read.page.observations.singleOrNull()
        }

    private suspend fun decodeContact(
        context: AndroidInteroperabilityContext,
        accountName: AndroidProviderAccountName,
        canonical: CanonicalContact,
        observation: com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation,
        desired: AndroidContactSnapshot,
        stage: AndroidProjectionDecodeStage,
    ) = AndroidProviderMimeRouter().route(observation.rawContact.rawContactId, observation.dataRows).let { route ->
        if (route.unsupportedOwnedRows.rows.isNotEmpty()) null
        else {
            val desiredPhoto = desired.rows.singleOrNull { it.kind == AndroidRowKind.PHOTO }
            var identityFailure: Pair<AndroidProviderIdentityFailure, AndroidRowKind?>? = null
            val resolver = RoomAndroidProviderIdentityResolver(database, context.account, context.providerEpoch) { reason, kind ->
                identityFailure = reason to kind
            }
            val photoCapture = com.patmanak.contako.data.android.provider.AndroidDurablePhotoCapture { _, _, _, _ ->
                desiredPhoto?.binaryReference ?: return@AndroidDurablePhotoCapture "provider-photo"
            }
            // Projection reconciliation must decode a provider Photo row whose bytes are absent,
            // but it must not pretend that row already carries the desired photo. The distinct
            // in-memory marker forces the plan to reseed the bounded inline payload before opening
            // display_photo. It is never persisted as canonical data; Android ingestion keeps the
            // codec's fail-closed default and cannot invent an Android-originated photo.
            val codec = AndroidProviderRowCodec(resolver, photoCapture, missingPhotoReference = { MISSING_PROVIDER_PHOTO_REFERENCE })
            try {
                try {
                    codec.decode(accountName, canonical.id, observation.rawContact.rawContactId, route.contactRows.rows)
                } catch (failure: AndroidProviderRowCodecException) {
                    if (failure.category != AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE ||
                        !resolver.recoverProjectionBindings(context, canonical.id, canonical.revision, observation,
                            desired, photoCapture, { MISSING_PROVIDER_PHOTO_REFERENCE },
                            confirmObservation = { readExact(accountName, observation.rawContact.rawContactId)?.rawContact == observation.rawContact },
                            onRejected = { detail -> runCatching { repairObserver.onBindingRecoveryFailure(stage, detail) } })
                    ) throw failure
                    identityFailure = null
                    codec.decode(accountName, canonical.id, observation.rawContact.rawContactId, route.contactRows.rows)
                }
            } catch (failure: AndroidProviderRowCodecException) {
                // Successful recovery is not a failure event. Detail never counts as another
                // affected contact and cannot change the existing repair/retry decision.
                runCatching { repairObserver.onRowFailure(stage, failure.category, identityFailure?.first, identityFailure?.second) }
                throw failure
            }
        }
    }

    private suspend fun decodeMembership(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        rawContactId: Long,
        observation: com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation,
        catalog: AndroidCompleteGroupCatalog,
        trusted: List<AndroidTrustedGroupBinding>,
    ): AndroidGroupMembershipSnapshot? {
        val rows = AndroidProviderMimeRouter().route(rawContactId, observation.dataRows).groupMembershipRows.rows
        val trustedByLocator = trusted.associateBy(AndroidTrustedGroupBinding::groupRowId)
        val catalogByLocator = catalog.rows.associateBy { it.groupRowId }
        val mappings = rows.map { row ->
            val locator = row.stringSlots.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val binding = trustedByLocator[locator] ?: return null
            val catalogRow = catalogByLocator[locator] ?: return null
            if (catalogRow.deleted || catalogRow.canonicalGroupIdClaim != binding.canonicalGroupId ||
                catalogRow.version != binding.expectedProviderVersion || catalogRow.sourceIdentity != binding.sourceIdentity
            ) return null
            AndroidGroupMembershipLocatorMapping(binding.canonicalGroupId, locator, row.dataRowId)
        }
        val canonical = database.contactDao().get(context.account.value, canonicalContactId)?.toDomain() ?: return null
        val desired = AndroidGroupProjectionPolicy().projectMemberships(
            canonical, database.contactGroupDao().getAll(context.account.value).map { it.toDomain() },
        )
        return AndroidGroupMembershipSnapshot.create(
            context.account.value, canonicalContactId, desired.preferredEmailValueId, desired.availability, mappings,
        )
    }

    private fun readCompleteCatalog(
        context: AndroidInteroperabilityContext,
        accountName: AndroidProviderAccountName,
    ): AndroidCompleteGroupCatalog {
        val pages = mutableListOf<com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage>()
        var after = 0L
        do {
            val page = groupsReader.readGroupPage(accountName, after, 100, includeDeleted = true)
            pages += page
            if (pages.size > MAX_GROUP_PAGES) throw IllegalArgumentException("Bound exceeded")
            after = page.nextAfterGroupRowId ?: 0L
        } while (after != 0L)
        return AndroidCompleteGroupCatalog.fromExhaustivePages(context.account, context.providerEpoch, pages)
    }

    private suspend fun trustedGroups(
        context: AndroidInteroperabilityContext,
        accountName: AndroidProviderAccountName,
    ): List<AndroidTrustedGroupBinding>? = database.androidGroupProjectionDao().getAllGroups(context.account.value)
        .mapNotNull { group ->
            if (group.providerEpoch == context.providerEpoch &&
                group.tombstoneState == AndroidTombstoneState.REMOTE_CONVERGED.name &&
                group.projectionState == AndroidProjectionWriteState.DETACHED.name &&
                group.groupRowLocator == null
            ) {
                val canonical = database.contactGroupDao().get(context.account.value, group.canonicalGroupId)?.group
                if (isCompletedAndroidGroupDeletion(group, canonical, context.providerEpoch)) return@mapNotNull null
                return null
            }
            if (group.providerEpoch != context.providerEpoch || group.tombstoneState != "NONE" ||
                group.adoptionState != AndroidAdoptionState.ADOPTED.name ||
                group.projectionState != AndroidProjectionWriteState.CLEAN.name
            ) return null
            AndroidTrustedGroupBinding(
                context.account,
                accountName,
                context.providerEpoch,
                group.canonicalGroupId,
                group.groupRowLocator ?: return null,
                group.providerVersion ?: return null,
                group.sourceIdentity,
            )
        }

    private companion object {
        const val MAX_GROUP_PAGES = 6
        const val MAX_PHOTO_BYTES = 10 * 1_024 * 1_024
        // Used only while preparing a semantic fingerprint; never sent to ContactsProvider.
        const val PENDING_DATA_ROW_LOCATOR = Long.MAX_VALUE
        const val MISSING_PROVIDER_PHOTO_REFERENCE = "provider://missing-photo"
    }
}

internal fun isRecoverableMembershipProjectionContextFailure(
    failure: AndroidGroupSnapshotContextFailure,
): Boolean = failure == AndroidGroupSnapshotContextFailure.PREFERRED_EMAIL_CONTEXT_MISMATCH

private fun AndroidProviderFailureCategory.toProjectionRepairCategory(): AndroidProjectionRepairCategory = when (this) {
    AndroidProviderFailureCategory.PERMISSION_DENIED -> AndroidProjectionRepairCategory.PROVIDER_PERMISSION_DENIED
    AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE -> AndroidProjectionRepairCategory.PROVIDER_UNAVAILABLE
    AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA -> AndroidProjectionRepairCategory.PROVIDER_MALFORMED_DATA
    AndroidProviderFailureCategory.BOUND_EXCEEDED -> AndroidProjectionRepairCategory.PROVIDER_BOUND_EXCEEDED
    AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH ->
        AndroidProjectionRepairCategory.PROVIDER_ACCOUNT_SCOPE_MISMATCH
}

private fun AndroidProviderRowCodecFailure.toProjectionRepairCategory(): AndroidProjectionRepairCategory = when (this) {
    AndroidProviderRowCodecFailure.ACCOUNT_SCOPE_MISMATCH,
    AndroidProviderRowCodecFailure.MIXED_RAW_CONTACTS,
    AndroidProviderRowCodecFailure.DUPLICATE_PROVIDER_ROW,
    AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY,
    AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE,
    AndroidProviderRowCodecFailure.VALUE_ID_ALLOCATION_FAILED,
    -> AndroidProjectionRepairCategory.ROW_DECODING_IDENTITY

    AndroidProviderRowCodecFailure.DUPLICATE_SINGLETON,
    AndroidProviderRowCodecFailure.GROUP_MEMBERSHIP_REQUIRES_SEPARATE_CODEC,
    AndroidProviderRowCodecFailure.UNSUPPORTED_ROW_KIND,
    -> AndroidProjectionRepairCategory.ROW_DECODING_CARDINALITY

    AndroidProviderRowCodecFailure.PHOTO_CAPTURE_FAILED ->
        AndroidProjectionRepairCategory.ROW_DECODING_PHOTO

    AndroidProviderRowCodecFailure.MALFORMED_ROW ->
        AndroidProjectionRepairCategory.ROW_DECODING_MALFORMED_ROW
    AndroidProviderRowCodecFailure.PHOTO_BINARY_MISSING ->
        AndroidProjectionRepairCategory.ROW_DECODING_PHOTO_BINARY_MISSING
    AndroidProviderRowCodecFailure.UNEXPECTED_BINARY ->
        AndroidProjectionRepairCategory.ROW_DECODING_UNEXPECTED_BINARY
    AndroidProviderRowCodecFailure.MALFORMED_TYPE ->
        AndroidProjectionRepairCategory.ROW_DECODING_MALFORMED_TYPE
    AndroidProviderRowCodecFailure.MALFORMED_DATE ->
        AndroidProjectionRepairCategory.ROW_DECODING_MALFORMED_DATE
    AndroidProviderRowCodecFailure.MALFORMED_ORDER ->
        AndroidProjectionRepairCategory.ROW_DECODING_MALFORMED_ORDER
    AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES ->
        AndroidProjectionRepairCategory.ROW_DECODING_MALFORMED_LINKS
    AndroidProviderRowCodecFailure.BOUND_EXCEEDED ->
        AndroidProjectionRepairCategory.ROW_DECODING_BOUND_EXCEEDED
}

private fun AndroidGroupMembershipWritePlanFailure.toProjectionRepairCategory(): AndroidProjectionRepairCategory =
    when (this) {
        AndroidGroupMembershipWritePlanFailure.ACCOUNT_SCOPE_MISMATCH,
        AndroidGroupMembershipWritePlanFailure.CONTACT_IDENTITY_MISMATCH,
        -> AndroidProjectionRepairCategory.GROUP_STATE_SCOPE

        AndroidGroupMembershipWritePlanFailure.STALE_CANONICAL_CONTEXT ->
            AndroidProjectionRepairCategory.GROUP_STATE_STALE_CONTEXT
        AndroidGroupMembershipWritePlanFailure.UNTRUSTED_GROUP_IDENTITY ->
            AndroidProjectionRepairCategory.GROUP_STATE_UNTRUSTED_IDENTITY
        AndroidGroupMembershipWritePlanFailure.DUPLICATE_LOCATOR ->
            AndroidProjectionRepairCategory.GROUP_STATE_DUPLICATE_LOCATOR
        AndroidGroupMembershipWritePlanFailure.BOUND_EXCEEDED ->
            AndroidProjectionRepairCategory.GROUP_STATE_BOUND_EXCEEDED
    }

private fun AndroidPhotoProviderStaleCategory.toProjectionReplanCategory(): AndroidProjectionReplanCategory =
    when (this) {
        AndroidPhotoProviderStaleCategory.JOURNAL_PREPARATION ->
            AndroidProjectionReplanCategory.PHOTO_JOURNAL_PREPARATION
        AndroidPhotoProviderStaleCategory.PRE_WRITE_OBSERVATION ->
            AndroidProjectionReplanCategory.PHOTO_PRE_WRITE_OBSERVATION
        AndroidPhotoProviderStaleCategory.PRE_WRITE_VERSION ->
            AndroidProjectionReplanCategory.PHOTO_PRE_WRITE_VERSION
        AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_UPDATE ->
            AndroidProjectionReplanCategory.PHOTO_PRE_WRITE_UPDATE
        AndroidPhotoProviderStaleCategory.PRE_WRITE_INLINE_BLOB ->
            AndroidProjectionReplanCategory.PHOTO_PRE_WRITE_INLINE_BLOB
        AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_PAYLOAD ->
            AndroidProjectionReplanCategory.PHOTO_PRE_WRITE_PAYLOAD
        AndroidPhotoProviderStaleCategory.POST_STREAM_OBSERVATION ->
            AndroidProjectionReplanCategory.PHOTO_POST_STREAM_OBSERVATION
        AndroidPhotoProviderStaleCategory.POST_STREAM_PHOTO_ROW ->
            AndroidProjectionReplanCategory.PHOTO_POST_STREAM_PHOTO_ROW
        AndroidPhotoProviderStaleCategory.POST_STREAM_DISPLAY_PHOTO ->
            AndroidProjectionReplanCategory.PHOTO_POST_STREAM_DISPLAY_PHOTO
        AndroidPhotoProviderStaleCategory.POST_BIND_OBSERVATION ->
            AndroidProjectionReplanCategory.PHOTO_POST_BIND_OBSERVATION
        AndroidPhotoProviderStaleCategory.JOURNAL_COMMIT ->
            AndroidProjectionReplanCategory.PHOTO_JOURNAL_COMMIT
    }
