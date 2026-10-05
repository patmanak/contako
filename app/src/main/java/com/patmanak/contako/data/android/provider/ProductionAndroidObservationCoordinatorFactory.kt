package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.RoomAndroidGroupCommitRepairObserver
import com.patmanak.contako.data.local.RoomAndroidUnifiedCommitRepairObserver
import com.patmanak.contako.data.local.RoomAndroidUnifiedCommitReplanObserver
import com.patmanak.contako.data.local.RoomAndroidMembershipLedgerStaleObserver
import com.patmanak.contako.data.local.AndroidCanonicalContactRejectionObserver
import com.patmanak.contako.data.sync.AndroidBoundedObservationCoordinator
import com.patmanak.contako.data.sync.AndroidExistingContactPlanRepairObserver
import com.patmanak.contako.data.sync.AndroidIngestActionRequiredObserver
import com.patmanak.contako.data.sync.AndroidIngestReplanObserver
import com.patmanak.contako.data.sync.AndroidGroupObservationProviderWriteExecutor
import com.patmanak.contako.data.sync.ExistingProductionAndroidContactObservationCoordinator
import com.patmanak.contako.data.sync.GroupOnlyProductionAndroidBoundedObservationCoordinator
import com.patmanak.contako.data.sync.RoomAndroidGroupObservationProviderFinalizer
import com.patmanak.contako.data.sync.asObservationAcknowledger
import java.util.UUID

/** Framework-bound production composition for the completed H04-04 Groups ingestion path. */
internal fun productionGroupObservationCoordinator(
    database: ContakoDatabase,
    repository: RoomContactRepository,
    contentResolver: ContentResolver,
    groupIdFactory: () -> UUID = UUID::randomUUID,
    actionRequiredObserver: AndroidIngestActionRequiredObserver =
        AndroidIngestActionRequiredObserver { },
    replanObserver: AndroidIngestReplanObserver = AndroidIngestReplanObserver { },
    groupCommitRepairObserver: RoomAndroidGroupCommitRepairObserver =
        RoomAndroidGroupCommitRepairObserver { },
    existingContactPlanRepairObserver: AndroidExistingContactPlanRepairObserver =
        AndroidExistingContactPlanRepairObserver { },
    existingContactCommitRepairObserver: RoomAndroidUnifiedCommitRepairObserver =
        RoomAndroidUnifiedCommitRepairObserver { },
    existingContactCommitReplanObserver: RoomAndroidUnifiedCommitReplanObserver =
        RoomAndroidUnifiedCommitReplanObserver { },
    membershipLedgerStaleObserver: RoomAndroidMembershipLedgerStaleObserver =
        RoomAndroidMembershipLedgerStaleObserver { },
    canonicalContactRejectionObserver: AndroidCanonicalContactRejectionObserver =
        AndroidCanonicalContactRejectionObserver { },
): AndroidBoundedObservationCoordinator {
    val writes = RoomAndroidGroupProviderWriteCoordinator(database, contentResolver)
    val photoVerifier = AndroidPhotoReadbackVerifier(contentResolver)
    val photoLoader = AndroidDisplayPhotoBinaryLoader(CanonicalPhotoBinaryLoader)
    return GroupOnlyProductionAndroidBoundedObservationCoordinator.compose(
        database,
        repository,
        groupIdFactory,
        RoomAndroidGroupObservationProviderFinalizer(
            database,
            AndroidGroupObservationProviderWriteExecutor(writes::execute),
        ),
        ExistingProductionAndroidContactObservationCoordinator(
            database,
            repository,
            AndroidContactsProviderWriter(contentResolver).asObservationAcknowledger(),
            actionRequiredObserver = actionRequiredObserver,
            replanObserver = replanObserver,
            existingPlanRepairObserver = existingContactPlanRepairObserver,
            existingCommitRepairObserver = existingContactCommitRepairObserver,
            existingCommitReplanObserver = existingContactCommitReplanObserver,
            membershipLedgerStaleObserver = membershipLedgerStaleObserver,
            canonicalContactRejectionObserver = canonicalContactRejectionObserver,
            interruptedPhotoProof = { account, observation, journal ->
                val source = runCatching { photoLoader.load(journal.binaryReference) }.getOrNull()
                val receipt = database.androidGroupProjectionDao().getPhotoProjectionReceipt(
                    journal.accountId, journal.canonicalContactId)
                com.patmanak.contako.data.sync.interruptedAndroidPhotoProofFailure(
                    source, journal.contentSize, journal.contentSha256,
                ) { bytes ->
                    if (receipt != null) {
                        receipt.sourceSha256 == journal.contentSha256 && receipt.sourceSize == journal.contentSize &&
                            receipt.binaryReference == journal.binaryReference &&
                            receipt.canonicalValueId == journal.canonicalValueId && journal.state == "COMMITTED" &&
                            receipt.rawContactVersion == journal.resultRawContactVersion &&
                            observation.rawContact.version == receipt.rawContactVersion &&
                            receipt.matchesPhoto(
                                com.patmanak.contako.data.sync.AndroidInteroperabilityContext(
                                    com.patmanak.contako.data.gateway.AccountScope(journal.accountId),
                                    journal.androidAccountName, 0, journal.providerEpoch),
                                journal.canonicalContactId, observation)
                    } else photoVerifier.matches(account, observation, bytes)
                }
            },
        ),
        actionRequiredObserver,
        groupCommitRepairObserver,
    )
}
