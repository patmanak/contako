package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.AndroidProjectionSkipObserver
import com.patmanak.contako.data.sync.AndroidCleanProjectionPresenceVerifier
import com.patmanak.contako.data.sync.AndroidProjectionRepairObserver
import com.patmanak.contako.data.sync.AndroidProjectionReplanObserver
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.sync.ProductionAndroidProjectionItemExecutor
import com.patmanak.contako.data.sync.RoomBoundedAndroidProjectionCoordinator
import com.patmanak.contako.data.sync.RoomAndroidProjectionWriteLedger

internal fun productionAndroidProjectionCoordinator(
    database: ContakoDatabase,
    contentResolver: ContentResolver,
    binaryLoader: AndroidProjectionBinaryLoader = AndroidProjectionBinaryLoader { null },
    skipObserver: AndroidProjectionSkipObserver = AndroidProjectionSkipObserver { _, _ -> },
    repairObserver: AndroidProjectionRepairObserver = AndroidProjectionRepairObserver { },
    replanObserver: AndroidProjectionReplanObserver = AndroidProjectionReplanObserver { },
    providerReplanObserver: AndroidProviderProjectionReplanObserver =
        AndroidProviderProjectionReplanObserver { },
): RoomBoundedAndroidProjectionCoordinator {
    val writeLedger = RoomAndroidProjectionWriteLedger(database)
    val reader = AndroidContactsProviderReader(contentResolver)
    val inlinePhotoLoader = AndroidInlinePhotoBinaryLoader(binaryLoader)
    val displayPhotoLoader = AndroidDisplayPhotoBinaryLoader(binaryLoader)
    val photoWriter = RoomAndroidPhotoProviderWriteCoordinator(database, contentResolver, reader, displayPhotoLoader)
    val mapper = CanonicalAndroidContactMapper(
        photoBytesAvailable = { reference -> reference != null && inlinePhotoLoader.load(reference) != null },
    )
    return RoomBoundedAndroidProjectionCoordinator(
        database,
        ProductionAndroidProjectionItemExecutor(
            database,
            reader,
            AndroidGroupsProviderReader(contentResolver),
            AndroidContactsProviderWriter(
                contentResolver,
                inlinePhotoLoader,
                membershipWriteAuthorizer = writeLedger.authorizer,
                replanObserver = providerReplanObserver,
                unclaimedDataRowAuthorizer = writeLedger.unclaimedDataRowAuthorizer,
            ),
            AndroidRawContactLifecycleGateway(contentResolver),
            photoWriter,
            displayPhotoLoader,
            repairObserver = repairObserver,
            replanObserver = replanObserver,
            mapper = mapper,
        ),
        skipObserver = skipObserver,
        groupProjectionCoordinator = ProductionAndroidCanonicalGroupProjectionCoordinator(database, contentResolver),
        canonicalMapper = mapper,
        presenceVerifier = AndroidCleanProjectionPresenceVerifier { context, candidates ->
            val metadata = reader.readOwnedRawContactMetadata(
                AndroidProviderAccountName(context.androidAccountName),
                candidates.mapNotNull { it.rawContactLocator }.toSet(),
            )
            cleanProjectionIdsWithOwnedMetadata(candidates, metadata)
        },
    )
}

/** A locator alone, or an aggregate match, is insufficient proof of this binding. */
internal fun cleanProjectionIdsWithOwnedMetadata(
    candidates: List<com.patmanak.contako.data.local.AndroidProjectionLedgerEntity>,
    metadata: List<AndroidOwnedRawContact>,
): Set<String> {
    val byLocator = metadata.groupBy { it.rawContactId }
    return candidates.mapNotNull { ledger ->
        val raw = byLocator[ledger.rawContactLocator]?.singleOrNull() ?: return@mapNotNull null
        if (raw.dirty || raw.deleted || raw.sourceIdentity != ledger.sourceIdentity ||
            raw.canonicalContactIdClaim != ledger.canonicalContactId || ledger.sourceIdentity == null
        ) null else ledger.canonicalContactId
    }.toSet()
}
